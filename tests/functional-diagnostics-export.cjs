const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

function diagnostics() {
  const trace = [];
  let at = 1792808000000;
  for (let occurrence = 0; occurrence < 4; occurrence++) {
    trace.push({ id: 990000001, at: at++, pid: 18307, stage: 'scheduled', detail: `at=${at + 5000};mode=clock` });
    trace.push({ id: 990000001, at: at++, pid: 18307, stage: 'receiver', detail: 'ddlr://alarm/990000001/0' });
    trace.push({ id: 990000001, at: at++, pid: 18307, stage: 'delivery', detail: 'lateMs=8115;appVisible=false' });
    trace.push({ id: 990000001, at: at++, pid: 18307, stage: 'notification', detail: 'allowed=true;channel=ddlr_high_v6_quiet;vibration=false' });
    trace.push({ id: 990000001, at: at++, pid: 18307, stage: 'overlay_hidden', detail: '0x0' });
    trace.push({ id: 990000001, at: at++, pid: 18307, stage: 'service', detail: 'foreground=true;on_demand=true' });
    trace.push({ id: 990000001, at: at++, pid: 18307, stage: 'wake_released', detail: 'handoff complete' });
  }
  return { appVersion: '2.3.3', appBuild: 86, device: 'OPPO PME110', androidRelease: '16', sdk: 36,
    capturedAt: '2026-10-24T02:19:19.213Z', notify: true, overlayAllowed: true, canFullScreen: false, exactOk: true,
    channelId: 'ddlr_high_v6_quiet', foregroundChannelId: 'ddlr_gentle_v6_quiet', channelImportance: 4,
    channelVibration: false, headsUp: true, lastNotificationAllowed: true, pending: 23,
    lastExpectedAt: 1792808332926, lastReceiverAt: 1792808341018, lastDelayMs: 8115,
    lastDisplayChannel: 'ddlr_high_v6_quiet', lastDeliveryAppVisible: false,
    displayState: { appVisible: true, interactive: true, serviceRunning: false, wakeHeld: false, overlayCards: 0 },
    vibrationMode: 'native', lastVibrationStatus: 'native_requested', lastVibrationUsage: 'alarm', vibrateInSilent: true,
    environment: { ringerMode: 0, notificationVolume: 0, interruptionFilter: 1, hasVibrator: true,
      backgroundRestricted: false, batteryExempt: false, powerSaveMode: false, vibrateAppOp: 0 }, trace };
}

test('compact clipboard diagnostics keep valid JSON, actual channels and the latest three full event stage sequences', () => {
  const h = harness(['app.js']);
  const source = diagnostics();
  h.context.exportFixture = source;
  const text = h.run('compactReminderDiagnostics(exportFixture)');
  const output = JSON.parse(text);
  assert.ok(text.length <= 4500);
  assert.equal(output.version, '2.3.3');
  assert.equal(output.build, 86);
  assert.equal(output.permissions.overlayAllowed, true);
  assert.equal(output.notification.channelId, 'ddlr_high_v6_quiet');
  assert.equal(output.notification.foregroundChannelId, 'ddlr_gentle_v6_quiet');
  assert.equal(output.environment.ringerMode, 0);
  assert.equal(output.display.appVisible, true);
  assert.equal(output.display.lastDeliveryAppVisible, false);
  assert.equal(output.display.lastOverlay.stage, 'overlay_hidden');
  assert.equal(output.alarm.lastDelayMs, 8115);
  assert.equal(output.trace.events.length, 3);
  assert.ok(output.trace.events[0].at > output.trace.events[1].at);
  assert.ok(output.trace.events[1].at > output.trace.events[2].at);
  for (const event of output.trace.events) {
    assert.deepEqual(event.steps.map(step => step[1]), [
      'receiver', 'delivery', 'notification', 'overlay_hidden', 'service', 'wake_released'
    ]);
    assert.equal(event.steps[1][2].appVisible, 'false');
    assert.equal(event.steps[1][2].lateMs, '8115');
  }
  assert.equal(source.trace.length, 28, 'clipboard formatting must not alter the full UI diagnostics');
});

test('clipboard budget drops old details explicitly while preserving every selected stage and process transition', () => {
  const h = harness(['app.js']);
  const source = diagnostics();
  source.trace = [];
  for (let occurrence = 1; occurrence <= 3; occurrence++) {
    for (let index = 0; index < 20; index++) {
      source.trace.push({ id: occurrence, at: 100000 + occurrence * 1000 + index, pid: index < 10 ? 100 : 200,
        stage: index === 0 ? 'receiver' : 'direct_vibration_requested',
        detail: 'status=' + 'x'.repeat(1000) + ';reason=' + 'y'.repeat(1000) + ';appVisible=false;foregroundService=true' });
    }
  }
  h.context.exportFixture = source;
  const text = h.run('compactReminderDiagnostics(exportFixture)');
  const output = JSON.parse(text);
  assert.ok(text.length <= 4500, text.length + ' exceeds the clipboard budget');
  assert.equal(output.trace.events.length, 3);
  assert.ok(output.trace.events.some(event => event.detailsOmitted > 0));
  for (const event of output.trace.events) {
    assert.equal(event.steps.length, 20, 'detail reduction must not discard stage evidence');
    assert.equal(event.pid, 100);
    assert.equal(event.steps.at(-1)[3], 200);
  }
});

test('diagnostic export omits task titles, bodies and arbitrary exception messages', () => {
  const h = harness(['app.js']);
  const source = diagnostics();
  const secret = 'private-course-title-and-room';
  source.items = [{ id: 1, title: secret, body: secret }];
  source.tasks = [{ name: secret }];
  source.environment.secret = secret;
  source.trace.push({ id: 990000001, at: 1792809000000, pid: 18307, stage: 'overlay_error',
    detail: 'android.view.WindowManager$BadTokenException: ' + secret });
  h.context.exportFixture = source;
  const text = h.run('compactReminderDiagnostics(exportFixture)');
  assert.doesNotMatch(text, new RegExp(secret));
  assert.match(text, /BadTokenException/);
  assert.equal(JSON.parse(text).alarm.pending, 1, 'fresh native items determine the queue size');
});

test('diagnostics retain background-to-foreground delay evidence and device-wide alarm ownership', () => {
  const h = harness(['app.js']);
  const source = diagnostics();
  Object.assign(source, { scheduleVersion: 15, sysNextOwner: 'com.android.deskclock',
    lastReceiverUrgent: true, lastReceiverSinceForegroundMs: 12, lastReceiverSinceBackgroundMs: 13277,
    lastForegroundAt: 1792809000000, lastBackgroundAt: 1792808986723 });
  source.trace.at(-6).detail = 'urgent=true;appVisible=true;sinceForegroundMs=12;sinceBackgroundMs=13277';
  h.context.exportFixture = source;
  const text = h.run('compactReminderDiagnostics(exportFixture)');
  const result = JSON.parse(text);
  assert.ok(text.length <= 4500);
  assert.equal(result.alarm.lastReceiverUrgent, true);
  assert.equal(result.alarm.lastReceiverSinceForegroundMs, 12);
  assert.equal(result.alarm.sysNextOwner, 'com.android.deskclock');
  assert.equal(result.display.lastForegroundAt, 1792809000000);
  assert.equal(result.trace.events[0].steps[0][2].urgent, 'true');
});

test('copy action refreshes native display evidence but preserves the full UI snapshot', async () => {
  let copied, query;
  const source = diagnostics();
  const h = harness(['app.js'], { globals: { Capacitor: { Plugins: {
    DdlAlarm: { pending: async options => { query = options; return { appBuild: 87, items: [],
      displayState: { appVisible: true, interactive: true, serviceRunning: false, overlayCards: 0 } }; } },
    DdlNotify: { copyDiagnostics: async payload => { copied = payload.text; } }
  } }, NativeNotify: { channelIdFor: () => 'ddlr_high_v5_native_silent' } } });
  h.context.exportFixture = source;
  h.run('renderDiag = async () => { window.lastReminderDiagnostics = exportFixture; }; S.settings.vibrateInSilent = false;');
  assert.equal(await h.run('copyReminderDiagnostics()'), true);
  assert.equal(query.channelId, 'ddlr_high_v5_native_silent');
  assert.equal(query.vibrateInSilent, false);
  const output = JSON.parse(copied);
  assert.equal(output.build, 87);
  assert.equal(output.alarm.pending, 0);
  assert.equal(output.display.appVisible, true);
  assert.equal(h.context.lastReminderDiagnostics, source);
  assert.equal(source.trace.length, 28);
  assert.ok(copied.length <= 4500);
});
