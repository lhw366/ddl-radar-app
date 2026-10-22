"""Run actual Android background/overlay tests. Dedicated emulator only; never wipes a phone."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PKG = 'com.ddlradar.app'
OUT = Path('runtime-evidence') / os.environ.get('ANDROID_SERIAL', 'emulator').replace(':', '-')
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, check=True, binary=False, timeout=40):
    result = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'adb {args}: {result.stderr.decode(errors="replace")}')
    return result.stdout if binary else result.stdout.decode(errors='replace').strip()


def capture(prefix):
    for label, args in {
        'logcat': ['logcat', '-d', '-v', 'threadtime', 'DdlTrace:I', 'DdlAlarm:W', '*:S'],
        'crash': ['logcat', '-d', '-b', 'crash', '-t', '1000'],
        'events': ['logcat', '-d', '-b', 'events', '-v', 'descriptive', '-t', '2000'],
        'alarm': ['shell', 'dumpsys', 'alarm'],
        'services': ['shell', 'dumpsys', 'activity', 'services', PKG],
        'windows': ['shell', 'dumpsys', 'window', 'windows'],
    }.items():
        (OUT / f'{prefix}-{label}.txt').write_text(adb(*args, check=False), encoding='utf-8')
    (OUT / f'{prefix}-screen.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True, check=False))
    for name in ['runtime-manual.png', 'missing-banner.png']:
        image = adb('exec-out', 'run-as', PKG, 'cat', 'files/' + name, binary=True, check=False)
        if image.startswith(b'\x89PNG'):
            (OUT / f'{prefix}-{name}').write_bytes(image)


def traces():
    raw = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/ddlr_trace.xml', check=False)
    try:
        value = ET.fromstring(raw).find("./string[@name='events']")
        return json.loads(value.text) if value is not None else []
    except (ET.ParseError, ValueError):
        return []


def prepare():
    adb('shell', 'pm', 'clear', PKG)
    adb('shell', 'pm', 'clear', PKG + '.test')
    adb('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('logcat', '-c')


def instrument(cls, expected):
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', f'{PKG}.{cls}',
                 f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner', timeout=180)
    (OUT / f'{cls}.txt').write_text(output, encoding='utf-8')
    # am instrument can exit zero even when the tests failed.
    assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -[12]\b|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', output), output
    assert len(re.findall(r'INSTRUMENTATION_STATUS_CODE: 0\b', output)) == expected, output


assert adb('get-state') == 'device'
assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'This script only runs on a disposable emulator.'
try:
    prepare()
    instrument('ReminderRuntimeTest', 2)
    capture('desktop')
    (OUT / 'manual-banner.png').write_bytes(adb('exec-out', 'run-as', PKG, 'cat', 'files/runtime-manual.png', binary=True))
    (OUT / 'desktop-trace.json').write_text(json.dumps(traces(), indent=2), encoding='utf-8')

    prepare()
    instrument('ColdReminderFixture', 1)
    registered = next(e for e in traces() if e['stage'] == 'scheduled' and e['id'] == 11003)
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    adb('shell', 'am', 'kill', PKG)
    time.sleep(2)
    assert not adb('shell', 'pidof', PKG, check=False), 'Cold test must begin with no app process.'
    # Use the user-setting override consumed by DeviceIdleController. Android
    # 16 restricts shell writes to non-allowlisted DeviceConfig flags.
    adb('shell', 'settings', 'put', 'global', 'device_idle_constants', 'min_time_to_alarm=0')
    adb('shell', 'dumpsys', 'deviceidle', 'enable')
    time.sleep(1)
    adb('shell', 'dumpsys', 'battery', 'unplug')
    adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    states = []
    idle_until = time.monotonic() + 10
    while time.monotonic() < idle_until:
        forced = adb('shell', 'dumpsys', 'deviceidle', 'force-idle', 'deep')
        state = adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep').strip()
        states.append({'command': forced, 'state': state})
        if state == 'IDLE':
            break
        if state == 'IDLE_MAINTENANCE':
            # Startup work may briefly enter a maintenance window. Advance
            # out of it and require the real state, not a substring of output.
            adb('shell', 'dumpsys', 'deviceidle', 'step', 'deep')
        time.sleep(0.2)
    (OUT / 'forced-idle.txt').write_text(json.dumps(states, indent=2), encoding='utf-8')
    (OUT / 'confirmed-idle-state.txt').write_text(state, encoding='utf-8')
    assert state == 'IDLE', f'Device did not enter deep idle: {states}'
    until = time.monotonic() + 65
    events = []
    while time.monotonic() < until:
        events = [e for e in traces() if e['id'] == 11003]
        if {'receiver', 'service', 'vibration_requested', 'notification', 'wake_released'} <= {e['stage'] for e in events}:
            break
        time.sleep(1)
    else:
        raise AssertionError(f'Cold/Doze event was not delivered: {events}')
    receiver = next(e for e in events if e['stage'] == 'receiver')
    assert receiver['pid'] != registered['pid'], 'System must restart the process to deliver this event.'
    (OUT / 'cold-doze-trace.json').write_text(json.dumps(events, indent=2), encoding='utf-8')
    capture('cold-doze')
    print('PASS: desktop banner layout, manual dismissal, 5-second expiry, cold process + Doze delivery')
finally:
    try:
        capture('final')
    except Exception as error:
        print('Capture failed:', error)
    adb('shell', 'settings', 'delete', 'global', 'device_idle_constants', check=False)
    adb('shell', 'dumpsys', 'deviceidle', 'unforce', check=False)
    adb('shell', 'dumpsys', 'battery', 'reset', check=False)
    adb('shell', 'pm', 'clear', PKG, check=False)
    adb('forward', '--remove-all', check=False)
    adb('reverse', '--remove-all', check=False)
    for setting in ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale']:
        adb('shell', 'settings', 'put', 'global', setting, '1', check=False)
