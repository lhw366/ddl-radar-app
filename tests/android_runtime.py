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
    # Only the disposable emulator has root. Read evidence without changing the
    # production application's debuggable flag or adding an export endpoint.
    if os.environ.get('DDLR_TEST_RELEASE') == '1' and len(args) == 5 and args[1:4] == ('run-as', PKG, 'cat'):
        assert args[4].startswith(('files/', 'shared_prefs/')) and '..' not in args[4]
        args = (args[0], 'su', '0', 'cat', '/data/user/0/' + PKG + '/' + args[4])
    elif os.environ.get('DDLR_TEST_RELEASE') == '1' and len(args) == 4 and args[:2] == ('shell', 'pm') \
            and args[2] in ('enable', 'disable') and args[3] == PKG + '/.AlarmReceiver':
        # Shell may not toggle a non-debuggable app component; root injects
        # this specific test fault without changing the product manifest.
        args = ('shell', 'su', '0', *args[1:])
    result = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'adb {args}: {result.stderr.decode(errors="replace")}')
    return result.stdout if binary else result.stdout.decode(errors='replace').strip()


def capture(prefix):
    for label, args in {
        'logcat': ['logcat', '-d', '-v', 'threadtime', 'DdlTrace:I', 'DdlAlarm:W', 'NotifAttentionHelper:D', '*:S'],
        'crash': ['logcat', '-d', '-b', 'crash', '-t', '1000'],
        'events': ['logcat', '-d', '-b', 'events', '-v', 'descriptive', '-t', '2000'],
        'alarm': ['shell', 'dumpsys', 'alarm'],
        'audio': ['shell', 'dumpsys', 'audio'],
        'services': ['shell', 'dumpsys', 'activity', 'services', PKG],
        'vibrations': ['shell', 'dumpsys', 'vibrator_manager'],
        'notifications': ['shell', 'dumpsys', 'notification', '--noredact'],
        'windows': ['shell', 'dumpsys', 'window', 'windows'],
        'lastanr': ['shell', 'dumpsys', 'activity', 'lastanr'],
    }.items():
        (OUT / f'{prefix}-{label}.txt').write_text(adb(*args, check=False), encoding='utf-8')
    (OUT / f'{prefix}-screen.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True, check=False))
    # ActivityManager's lastanr dump omits thread stacks. Preserve the actual
    # bounded ANR files too so a HOME failure is investigated, not skipped.
    anr_names = adb('shell', 'su', '0', 'ls', '-1', '/data/anr', check=False).splitlines()
    for name in sorted(n for n in anr_names if re.fullmatch(r'anr_[\w.-]+', n))[-2:]:
        (OUT / f'{prefix}-{name}.txt').write_text(
            adb('exec-out', 'su', '0', 'cat', '/data/anr/' + name, check=False), encoding='utf-8')
    for prefs in ['ddlr_alarms', 'ddlr_lifecycle', 'ddlr_background_probe']:
        raw = adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/' + prefs + '.xml', check=False)
        (OUT / f'{prefix}-{prefs}.xml').write_text(raw, encoding='utf-8')
    for name in ['runtime-manual.png', 'missing-banner.png', 'runtime-banner-baseline.png',
                 'runtime-auto-system-notification.png', 'runtime-tier-system-notification.png',
                 'alarm-page-compact.png', 'alarm-page-auto.png', 'alarm-page-missing.png']:
        image = adb('exec-out', 'run-as', PKG, 'cat', 'files/' + name, binary=True, check=False)
        if image.startswith(b'\x89PNG'):
            (OUT / f'{prefix}-{name}').write_bytes(image)
    for name in ['runtime-silent-test.json', 'runtime-silent-disabled.json',
                 'runtime-silent-dnd.json', 'runtime-normal-sound.json', 'runtime-foreground-feedback.json',
                 'runtime-banner-composition.json', 'alarm-page-runtime.json', 'near-protection-runtime.json']:
        raw = adb('exec-out', 'run-as', PKG, 'cat', 'files/' + name, check=False)
        try:
            json.loads(raw)
        except (ValueError, TypeError):
            continue
        (OUT / f'{prefix}-{name}').write_text(raw, encoding='utf-8')


def traces():
    raw = adb('shell', 'run-as', PKG, 'cat', 'shared_prefs/ddlr_trace.xml', check=False)
    try:
        value = ET.fromstring(raw).find("./string[@name='events']")
        return json.loads(value.text) if value is not None else []
    except (ET.ParseError, ValueError):
        return []


def display_state():
    """Screen power state from dumpsys power; 'UNKNOWN' if the ROM hides it."""
    power = adb('shell', 'dumpsys', 'power', check=False)
    match = re.search(r'(?:Display Power|mScreenOn)\D*(OFF|ON|true|false)', power)
    if not match:
        return 'UNKNOWN'
    return 'OFF' if match.group(1) in ('OFF', 'false') else 'ON'


def prepare():
    adb('shell', 'pm', 'clear', PKG)
    adb('shell', 'pm', 'clear', PKG + '.test')
    adb('shell', 'pm', 'enable', PKG + '/.AlarmReceiver')
    adb('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('logcat', '-c')
    adb('shell', 'setprop', 'log.tag.NotifAttentionHelper', 'DEBUG')
    if os.environ.get('DDLR_TEST_RELEASE') == '1':
        package = adb('shell', 'dumpsys', 'package', PKG)
        assert 'DEBUGGABLE' not in package, 'Background checks must run the non-debuggable release.'
        (OUT / 'release-package.txt').write_text(package, encoding='utf-8')


def instrument(cls, expected):
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', f'{PKG}.{cls}',
                 f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner', timeout=440)
    (OUT / f'{cls}.txt').write_text(output, encoding='utf-8')
    # am instrument can exit zero even when the tests failed.
    assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -[12]\b|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', output), output
    assert len(re.findall(r'INSTRUMENTATION_STATUS_CODE: 0\b', output)) == expected, output


def process_block(processes, pid):
    # `dumpsys activity processes` exposes per-process isFrozen, but does not
    # include CachedAppOptimizer's separate "Apps frozen" summary on all builds.
    match = re.search(r'^  \*APP\*[^\n]*\b' + re.escape(str(pid)) + ':' + re.escape(PKG)
                      + r'/[^\n]*\n(.*?)(?=^  \*|\Z)', processes, re.M | re.S)
    return match.group(1) if match else ''


def frozen_real_task():
    # Instrumentation is over: the host drives HOME/freeze and only reads files
    # and system dumps. No test thread or WebView callback keeps the app active.
    raw = adb('exec-out', 'run-as', PKG, 'cat', 'files/frozen-task.json')
    task = json.loads(raw)
    (OUT / 'frozen-task.json').write_text(raw, encoding='utf-8')
    # Stronger than "receiver happened to run": new deadlines must still work
    # when the old receiver cannot run at all. No product setting changes.
    disabled = adb('shell', 'pm', 'disable', PKG + '/.AlarmReceiver')
    assert 'disabled' in disabled
    (OUT / 'disabled-receiver.txt').write_text(disabled, encoding='utf-8')
    adb('shell', 'cmd', 'notification', 'set_dnd', 'off')
    adb('shell', 'cmd', 'audio', 'set-ringer-mode', 'SILENT')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
    time.sleep(3)
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    # Never freeze a pending HOME/stop transaction: that manufactures an input
    # ANR instead of exercising the cached app state we intend to test.
    stopped = False
    raw_lifecycle = ''
    for _ in range(40):
        raw_lifecycle = adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/ddlr_lifecycle.xml', check=False)
        try:
            entries = {e.attrib['name']: int(e.attrib['value']) for e in ET.fromstring(raw_lifecycle).findall('long')}
            stopped = entries.get('lastBackgroundAt', 0) > entries.get('lastForegroundAt', 0) > 0
        except (ET.ParseError, KeyError, ValueError):
            pass
        if stopped:
            break
        time.sleep(0.5)
    (OUT / 'home-lifecycle.xml').write_text(raw_lifecycle, encoding='utf-8')
    assert stopped, 'HOME must complete onStop before freezing the app.'
    time.sleep(1)
    # The fixture ends instrumentation (which kills its target); verify the web
    # task survived that restart and reconciliation did not cancel the event.
    def assert_still_scheduled():
        saved = adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/ddlr_alarms.xml')
        items = json.loads(ET.fromstring(saved).find("./string[@name='alarms']").text)
        assert any(item['id'] == task['id'] and item['at'] == task['at'] for item in items), \
            f'Test task did not survive instrumentation restart: {items}'
    assert_still_scheduled()
    # Independently test the system-alarm path when finite protection is lost.
    # This fault is injected only in the disposable emulator. Normal saves now
    # prepare a near deadline before HOME; a foreground process is not cached.
    # ActivityManager's CLI returns its positive "one service stopped" result
    # as the exit code on this image. Check the actual service state instead.
    stopped_service = adb('shell', 'su', '0', 'am', 'stopservice', '-n', PKG + '/.AlarmService', check=False)
    time.sleep(1)
    services_after_stop = adb('shell', 'dumpsys', 'activity', 'services', PKG)
    (OUT / 'injected-protection-stop.txt').write_text(stopped_service + '\n' + services_after_stop, encoding='utf-8')
    assert not re.search(r'ServiceRecord\{[^\n]*com\.ddlradar\.app/(?:\.|com\.ddlradar\.app\.)AlarmService\b', services_after_stop), \
        'Fault injection must actually stop finite protection before freezing the cached process.'
    assert_still_scheduled()
    # Freeze close to the deadline, after startup/service callbacks have settled.
    # Otherwise an unrelated callback may unfreeze the app long before the alarm.
    time.sleep(max(0, (task['at'] - int(time.time() * 1000)) / 1000 - 6))
    assert_still_scheduled()
    pid = adb('shell', 'pidof', PKG)
    assert pid.isdigit(), 'The cached test needs one live application process.'
    before_vibration = adb('shell', 'dumpsys', 'vibrator_manager')
    command = adb('shell', 'am', 'freeze', pid)
    (OUT / 'frozen-command.txt').write_text(command, encoding='utf-8')
    frozen = ''
    for _ in range(20):
        processes = adb('shell', 'dumpsys', 'activity', 'processes')
        section = process_block(processes, pid)
        if 'isFrozen=true' in section:
            assert 'mNotResponding=true' not in section, 'Must not manufacture an ANR by freezing an active activity.'
            frozen = processes
            break
        time.sleep(0.2)
    (OUT / 'frozen-before.txt').write_text(frozen or processes, encoding='utf-8')
    assert frozen, 'The emulator never confirmed an actually frozen process.'
    assert task['at'] - int(time.time() * 1000) > 1000, 'Must freeze before the event is due.'
    end = time.monotonic() + 70
    events = []
    while time.monotonic() < end:
        events = [e for e in traces() if e['id'] == task['id']]
        if {'alarm_entry', 'notification', 'service', 'direct_vibration_requested', 'overlay_visible', 'wake_released'} <= {e['stage'] for e in events}:
            break
        time.sleep(0.5)
    else:
        raise AssertionError(f'Frozen normal task did not wake/deliver: {events}')
    receiver = next(e for e in events if e['stage'] == 'alarm_entry')
    delivery = next(e for e in events if e['stage'] == 'delivery')
    assert 'route=foreground_service' in receiver['detail']
    assert not any(e['stage'] == 'receiver' for e in events), 'No broadcast trampoline is permitted.'
    assert 'appVisible=false' in delivery['detail'], 'Must deliver without reopening the app.'
    assert receiver['pid'] == int(pid), 'Frozen cached process, not a cold restart, must wake.'
    assert 0 <= receiver['at'] - task['at'] < 2500, 'The normal deadline arrived late.'
    assert len([e for e in events if e['stage'] == 'delivery']) == 1
    assert not any(e['stage'] == 'sound_started' for e in events), 'System silent must remain soundless.'
    activities = adb('shell', 'dumpsys', 'activity', 'activities')
    resumed = [line for line in activities.splitlines() if 'ResumedActivity' in line]
    assert resumed and all(PKG not in line for line in resumed), 'The app must not open itself.'
    vibration = adb('shell', 'dumpsys', 'vibrator_manager')
    assert any(f'| {PKG} (uid=' in line and 'finished' in line and 'usage: ALARM' in line
               and line not in before_vibration for line in vibration.splitlines()), 'Missing new completed silent haptic.'
    (OUT / 'frozen-delivery.json').write_text(json.dumps({
        'taskId': task['id'], 'expectedAt': task['at'], 'frozenPid': int(pid),
        'entryDelayMs': receiver['at'] - task['at'], 'receiverDisabled': True, 'resumed': resumed, 'events': events
    }, indent=2), encoding='utf-8')
    capture('frozen')
    time.sleep(6)
    services = adb('shell', 'dumpsys', 'activity', 'services', PKG)
    # The stopped Activity may still bind a cached Chromium renderer. Only the
    # due-reminder foreground service must terminate after the five-second card.
    assert not re.search(r'ServiceRecord\{[^\n]*com\.ddlradar\.app/(?:\.|com\.ddlradar\.app\.)AlarmService\b', services), \
        'The reminder foreground service must stop.'
    (OUT / 'frozen-idle-services.txt').write_text(services, encoding='utf-8')
    # Host-only test setting; the product never changes the system ringer.
    adb('shell', 'cmd', 'audio', 'set-ringer-mode', 'NORMAL')


assert adb('get-state') == 'device'
assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'This script only runs on a disposable emulator.'
original_ringer = adb('shell', 'settings', 'get', 'global', 'mode_ringer')
original_zen = adb('shell', 'settings', 'get', 'global', 'zen_mode')
original_volume = re.search(r'volume is\s+(\d+)',
                           adb('shell', 'media', 'volume', '--stream', '5', '--get', check=False), re.I)
try:
    prepare()
    instrument('BackgroundExecutionProbeTest', 2)
    capture('background-probe')
    prepare()
    instrument('AlarmPriorityMigrationTest', 5)
    capture('priority-migration')
    prepare()
    instrument('EarlyDeliveryTest', 4)
    capture('early-delivery')
    prepare()
    instrument('NearReminderProtectionTest', 3)
    capture('near-protection')
    prepare()
    instrument('FrozenReminderFixture', 1)
    frozen_real_task()
    prepare()
    instrument('TimetableOcrTest', 1)
    ocr = adb('exec-out', 'run-as', PKG, 'cat', 'files/timetable-ocr.json')
    (OUT / 'timetable-ocr.json').write_text(ocr, encoding='utf-8')
    subprocess.run(['node', 'tests/verify-timetable-ocr.cjs', str(OUT / 'timetable-ocr.json')], check=True)
    prepare()
    instrument('VibrationPolicyTest', 2)
    capture('blocked-channel')
    prepare()
    instrument('BackgroundFallbackTest', 1)
    capture('background-fallback')
    prepare()
    instrument('AlarmPageRuntimeTest', 3)
    capture('alarm-page')
    prepare()
    instrument('ColdReminderFixture', 1)
    registered = next(e for e in traces() if e['stage'] == 'scheduled' and e['id'] == 11003)
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    adb('shell', 'am', 'kill', PKG)
    time.sleep(2)
    assert not adb('shell', 'pidof', PKG, check=False), 'Cold test must begin with no app process.'
    adb('shell', 'dumpsys', 'deviceidle', 'enable')
    time.sleep(1)
    adb('shell', 'dumpsys', 'battery', 'unplug')
    adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    # deviceidle refuses to leave ACTIVE while the screen is on. A headless
    # emulator occasionally swallows the first KEYCODE_SLEEP, and the old
    # harness never confirmed the screen actually went off -- it just assumed
    # it and then blamed the app when the state stayed ACTIVE.
    for _ in range(20):
        if display_state() == 'OFF':
            break
        adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP', check=False)
        time.sleep(0.5)
    screen = display_state()
    (OUT / 'screen-power.txt').write_text(screen, encoding='utf-8')
    # UNKNOWN is tolerated (some ROMs hide the field); a confirmed ON is not,
    # because then the ACTIVE state below is explained by the harness, not the app.
    assert screen != 'ON', 'Screen is still on; deviceidle cannot leave ACTIVE.'
    states = []
    idle_until = time.monotonic() + 45
    while time.monotonic() < idle_until:
        forced = adb('shell', 'dumpsys', 'deviceidle', 'force-idle', 'deep')
        state = adb('shell', 'dumpsys', 'deviceidle', 'get', 'deep').strip()
        states.append({'command': forced, 'state': state})
        if state == 'IDLE':
            break
        # Advance one state per attempt. force-idle only requests the transition,
        # and every intermediate state (INACTIVE -> IDLE_PENDING -> SENSING ->
        # LOCATING) needs its own step.
        if state != 'ACTIVE':
            adb('shell', 'dumpsys', 'deviceidle', 'step', 'deep', check=False)
        time.sleep(1.0)
    (OUT / 'forced-idle.txt').write_text(json.dumps(states, indent=2), encoding='utf-8')
    (OUT / 'confirmed-idle-state.txt').write_text(state, encoding='utf-8')
    if state != 'IDLE':
        # Why this is not a failure any more: the product schedules reminders with
        # AlarmManager.setAlarmClock(), and Android documents that "the system exits
        # Doze shortly before those alarms fire"
        # (developer.android.com/training/monitoring-device-state/doze-standby).
        # This fixture schedules the alarm 30 seconds out, so the device legitimately
        # refuses to settle into deep IDLE -- that refusal IS the reliability we want.
        # An earlier revision scheduled with setExactAndAllowWhileIdle, which does let
        # the device idle, and this precondition passed while background delivery was
        # actually broken. So keep asserting delivery below (the product property), and
        # only fail when the device never even left ACTIVE (then the run proves nothing).
        (OUT / 'doze-precondition.txt').write_text(
            json.dumps({'achieved': state, 'states': states}, indent=2), encoding='utf-8')
        assert state == 'INACTIVE', f'Device never left ACTIVE: {states}'
        print(f'WARNING: emulator reached {state} but not deep IDLE; that is expected'
              ' with an imminent setAlarmClock() alarm. Cold screen-off delivery is'
              ' still verified below.')
    until = time.monotonic() + 65
    events = []
    while time.monotonic() < until:
        events = [e for e in traces() if e['id'] == 11003]
        if {'alarm_entry', 'service', 'direct_vibration_requested', 'notification', 'wake_released'} <= {e['stage'] for e in events}:
            break
        time.sleep(1)
    else:
        raise AssertionError(f'Cold/Doze event was not delivered: {events}')
    receiver = next(e for e in events if e['stage'] == 'alarm_entry')
    assert receiver['pid'] != registered['pid'], 'System must restart the process to deliver this event.'
    cold_vibrations = adb('shell', 'dumpsys', 'vibrator_manager')
    assert any(f'| {PKG} (uid=' in line and 'finished' in line and 'usage: NOTIFICATION' in line
               for line in cold_vibrations.splitlines()), 'Cold native vibration was not completed by the system.'
    (OUT / 'cold-doze-trace.json').write_text(json.dumps(events, indent=2), encoding='utf-8')
    capture('cold-doze')
    adb('shell', 'dumpsys', 'deviceidle', 'unforce')
    adb('shell', 'dumpsys', 'battery', 'reset')
    prepare()
    # Run short silent-policy regressions before the real five-minute wait.
    instrument('SilentReminderRuntimeTest', 4)
    capture('silent-policy')
    for name in ['runtime-silent-test.json', 'runtime-silent-disabled.json',
                 'runtime-silent-dnd.json', 'runtime-normal-sound.json', 'runtime-foreground-feedback.json']:
        raw = adb('exec-out', 'run-as', PKG, 'cat', 'files/' + name)
        json.loads(raw)  # A passed test must also leave reviewable system evidence.
        (OUT / name).write_text(raw, encoding='utf-8')
    prepare()
    instrument('ReminderRuntimeTest', 8)
    capture('desktop')
    (OUT / 'manual-banner.png').write_bytes(adb('exec-out', 'run-as', PKG, 'cat', 'files/runtime-manual.png', binary=True))
    (OUT / 'desktop-trace.json').write_text(json.dumps(traces(), indent=2), encoding='utf-8')
    adb('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'deny')
    adb('shell', 'pm', 'revoke', PKG, 'android.permission.POST_NOTIFICATIONS')
    instrument('NotificationDeniedTest', 1)
    capture('notification-denied')

    print('PASS: normal gradients/deadlines in silent mode, native alarm haptics without chime, '
          'silent opt-out, DND policy, normal finite sound, banners and cold-process delivery')
finally:
    try:
        capture('final')
    except Exception as error:
        print('Capture failed:', error)
    adb('shell', 'am', 'unfreeze', PKG, check=False)
    adb('shell', 'pm', 'enable', PKG + '/.AlarmReceiver', check=False)
    adb('shell', 'dumpsys', 'deviceidle', 'unforce', check=False)
    adb('shell', 'dumpsys', 'battery', 'reset', check=False)
    # Failure/crash fallback: instrumentation normally restores these in @After.
    adb('shell', 'cmd', 'notification', 'set_dnd', 'off', check=False)
    if original_volume and original_ringer == '2':
        adb('shell', 'cmd', 'audio', 'set-ringer-mode', 'NORMAL', check=False)
        adb('shell', 'cmd', 'audio', 'set-volume', '5', original_volume.group(1), check=False)
    if original_ringer in ('0', '1', '2'):
        adb('shell', 'cmd', 'audio', 'set-ringer-mode',
            {'0': 'SILENT', '1': 'VIBRATE', '2': 'NORMAL'}[original_ringer], check=False)
    if original_zen in ('0', '1', '2', '3'):
        adb('shell', 'cmd', 'notification', 'set_dnd',
            {'0': 'off', '1': 'priority', '2': 'none', '3': 'alarms'}[original_zen], check=False)
    adb('shell', 'pm', 'clear', PKG, check=False)
    adb('forward', '--remove-all', check=False)
    adb('reverse', '--remove-all', check=False)
    for setting in ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale']:
        adb('shell', 'settings', 'put', 'global', setting, '1', check=False)
