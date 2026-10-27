const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

test('synthetic late service arrival is shown before permission checks and startup access remains unknown', async () => {
  const diag = { device: 'OPPO test-device', pending: 40, notify: true, exactOk: true,
    lastDelayMs: 48000, lastDeliveryAppVisible: true, lastEntryAt: 50000,
    lastEntrySinceForegroundMs: 40, lastEntrySinceBackgroundMs: 51000,
    backgroundAccess: { colorOS: true, state: 'unknown', startupPageAvailable: true } };
  const h = harness(['app.js'], { globals: { NativeNotify: { available: () => true, diag: async () => diag } } });
  await h.run('renderDiag()');
  const html = h.el('diagList').innerHTML;
  assert.match(html, /后台提醒失败.*48\.00/);
  assert.ok(html.indexOf('后台提醒失败') < html.indexOf('应用通知权限'));
  assert.match(html, /ColorOS 自启动权限：状态未知/);
  assert.equal(h.el('diagList').querySelectorAll('[data-fix="startup"]').length, 2);
});

test('probe separates a blocked main queue, late system entry, suspension, sleep and a foreground test', () => {
  const h = harness(['app.js']);
  const base = { expectedAt: 10000, expectedElapsed: 10000, expectedUptime: 10000,
    workerAt: 10010, workerElapsed: 10010, workerUptime: 10010, workerAppVisible: false,
    mainAt: 10020, mainQueueDelayMs: 10 };
  const summary = (probe, extra = {}) => h.run(`backgroundProbeSummary(${JSON.stringify({backgroundProbe: probe, ...extra})})`);
  assert.match(summary({ ...base, mainAt: 15000, mainQueueDelayMs: 4990 }), /主线程阻塞/);
  assert.match(summary(base, { lastExpectedAt: 10000, lastDelayMs: 48550 }), /系统到点投递/);
  assert.match(summary({ ...base, workerAt: 58000, workerElapsed: 58000, workerUptime: 58000 }), /可能存在进程暂停/);
  assert.match(summary({ ...base, workerAt: 58000, workerElapsed: 58000, workerUptime: 10010 }), /不能据此认定进程冻结/);
  assert.match(summary({ ...base, workerAppVisible: true }), /不能据此判定后台正常/);
});

test('startup button opens only on explicit click and does not turn unknown access into granted', async () => {
  let opened = 0;
  const h = harness(['app.js'], { globals: { Capacitor: { Plugins: { DdlNotify: {
    openStartupSettings: async () => { opened++; return { opened: true, page: 'startup', state: 'unknown' }; }
  } } } } });
  h.context.toast = (...args) => h.toasts.push(args);
  assert.equal(opened, 0);
  await h.run('openStartupPermissionSettings()');
  assert.equal(opened, 1);
  assert.match(h.toasts.at(-1).join(' '), /只确认.*允许自启动/);
  assert.doesNotMatch(h.toasts.at(-1).join(' '), /已修复|授权成功|关闭电池优化/);
});

test('an explicit native test arms one probe while normal saves arm none and a probe error cannot cancel the alarm', async () => {
  let armed = 0, applied = [];
  const h = harness(['nativenotify.js'], { globals: { Capacitor: { isNativePlatform: () => true, Plugins: {
    DdlNotify: { notifyStatus: async () => ({ granted: true }) }, DdlAlarm: {
      apply: async payload => { applied = payload.alarms; }, pending: async () => ({ items: applied }),
      armBackgroundProbe: async () => { armed++; throw new Error('probe unavailable'); }
    }
  } }, planReminders: () => [] } });
  await h.context.NativeNotify.sync(h.context.S);
  assert.equal(armed, 0);
  assert.equal(await h.context.NativeNotify.test(60), true);
  assert.equal(armed, 1);
  assert.ok(applied.some(alarm => alarm.id === 990000001));
});

test('copied diagnostics preserve probe clocks and bounded main stack without claiming OEM startup permission', () => {
  const h = harness(['app.js']);
  h.context.observation = { backgroundAccess: { colorOS: true, state: 'unknown' },
    lastBackgroundCompletedAt: 9000, backgroundProbe: { expectedAt: 10000, workerAt: 58000,
      workerElapsed: 58000, workerUptime: 58000, mainQueueDelayMs: 10, mainStack: Array(20).fill('frame'.repeat(50)) } };
  const result = JSON.parse(h.run('compactReminderDiagnostics(observation)'));
  assert.equal(result.backgroundAccess.state, 'unknown');
  assert.equal(result.backgroundProbe.workerAt, 58000);
  assert.equal(result.backgroundProbe.mainStack.length, 6);
  assert.ok(result.backgroundProbe.mainStack.every(frame => frame.length <= 140));
  assert.equal(result.display.lastBackgroundCompletedAt, 9000);
});

test('the actual native diagnostics bridge carries probe evidence and the completed HOME timestamp', async () => {
  const h = harness(['nativenotify.js'], { globals: { Capacitor: { isNativePlatform: () => true, Plugins: {
    DdlAlarm: { pending: async () => ({ items: [], lastBackgroundCompletedAt: 9000,
      backgroundAccess: { colorOS: true, state: 'unknown' },
      backgroundProbe: { expectedAt: 10000, workerAt: 58000, mainAt: 58020 } }) }
  } } } });
  const d = await h.context.NativeNotify.diag();
  assert.equal(d.lastBackgroundCompletedAt, 9000);
  assert.equal(d.backgroundAccess.state, 'unknown');
  assert.equal(d.backgroundProbe.workerAt, 58000);
});

test('a previous explicit-test probe is not used to explain a different normal occurrence', () => {
  const h = harness(['app.js']);
  const result = h.run(`backgroundProbeSummary({lastExpectedAt: 500000, lastDelayMs: 150000,
    backgroundProbe: {expectedAt: 10000, workerAt: 130000, workerElapsed: 130000,
    expectedElapsed: 10000, expectedUptime: 10000, workerUptime: 130000}})`);
  assert.match(result, /之前的测试.*不用于判断本次普通提醒/);
  assert.doesNotMatch(result, /可能存在进程暂停/);
});

test('early recovery is not labelled successful until that same occurrence actually delivers', async () => {
  const diag = {lastEarlyAt: 1000, lastEarlyId: 44, lastEarlyExpectedAt: 100000,
    lastEarlyStatus: 'rearmed', lastEarlyMode: 'exact_elapsed_recovery',
    lastEntryId: 44, lastEntryExpectedAt: 100000, lastEntryDelayMs: -140000};
  const h = harness(['app.js'], { globals: { NativeNotify: { available: () => true, diag: async () => diag } } });
  await h.run('renderDiag()');
  assert.match(h.el('diagList').innerHTML, /等待真正到点；尚不能判定送达成功/);
  Object.assign(diag, {lastFireId: 45, lastExpectedAt: 100000});
  await h.run('renderDiag()');
  assert.match(h.el('diagList').innerHTML, /尚不能判定送达成功/);
  Object.assign(diag, {lastFireId: 44, lastDelayMs: 15, lastDeliveryAppVisible: false});
  await h.run('renderDiag()');
  assert.match(h.el('diagList').innerHTML, /已在到点后执行/);
  Object.assign(diag, {lastDelayMs: 35000, lastDeliveryAppVisible: true});
  await h.run('renderDiag()');
  assert.match(h.el('diagList').innerHTML, /迟到 35\.00 秒；后台准时性未通过/);
  assert.doesNotMatch(h.el('diagList').innerHTML, /已在到点后执行/);
});

test('copied diagnostics retain the future entry and its recovery independently of the last delivered tier', async () => {
  const native = {items: [], lastFireId: 45, lastExpectedAt: 100000, lastDelayMs: 150000,
    lastEntryId: 44, lastEntryExpectedAt: 400000, lastEntryDelayMs: -150000,
    lastEarlyId: 44, lastEarlyAt: 250000, lastEarlyExpectedAt: 400000, lastEarlyStatus: 'rearmed',
    lastEarlyMode: 'exact_elapsed_recovery', trace: [{id: 44, at: 250000, pid: 5,
      stage: 'alarm_entry', detail: 'expectedAt=400000;lateMs=-150000;appVisible=false'},
      {id: 44, at: 250010, pid: 5, stage: 'early_rearm_rearmed',
        detail: 'expectedAt=400000;remainingMs=149990;mode=exact_elapsed_recovery'}]};
  const h = harness(['nativenotify.js', 'app.js'], { globals: { Capacitor: { isNativePlatform: () => true, Plugins: {
    DdlAlarm: { pending: async () => native }
  } } } });
  const bridged = await h.context.NativeNotify.diag();
  h.context.recoveryFixture = bridged;
  const out = JSON.parse(h.run('compactReminderDiagnostics(recoveryFixture)'));
  assert.equal(out.alarm.lastFireId, 45);
  assert.equal(out.alarm.lastEntryId, 44);
  assert.equal(out.alarm.lastEntryDelayMs, -150000);
  assert.equal(out.alarm.lastEarlyStatus, 'rearmed');
  assert.equal(out.trace.events[0].expectedAt, 400000, 'arrival itself supplies expectedAt even after scheduled trace rolls out');
  assert.equal(out.trace.events[0].steps[1][2].remainingMs, '149990');
});

test('finite protection crosses the actual bridge and is shown as waiting rather than proven background delivery', async () => {
  const h = harness(['nativenotify.js', 'app.js'], { globals: { Capacitor: { isNativePlatform: () => true, Plugins: {
    DdlAlarm: { pending: async () => ({items: [{id: 44, at: 400000}], guard: true, wakeHeld: false,
      protection: {waiting: true, expectedAt: 400000, expiresElapsed: 500000, maxWaitMs: 180000}}) }
  } } } });
  await h.run('renderDiag()');
  assert.match(h.el('diagList').innerHTML, /临近提醒保护已准备.*等待时不保持 CPU 唤醒/);
  assert.doesNotMatch(h.el('diagList').innerHTML, /后台准时性已通过/);
  h.context.protectionFixture = await h.context.NativeNotify.diag();
  const out = JSON.parse(h.run('compactReminderDiagnostics(protectionFixture)'));
  assert.equal(out.protection.waiting, true);
  assert.equal(out.protection.expectedAt, 400000);
  assert.equal(out.protection.maxWaitMs, 180000);
  assert.equal(out.display.wakeHeld, false);
});

test('a different early entry cannot supply lifecycle ages for the last successfully delivered event', async () => {
  const h = harness(['app.js'], { globals: { NativeNotify: { available: () => true, diag: async () => ({
    lastDelayMs: 35000, lastDeliveryAppVisible: true, lastFireId: 45,
    lastEntryAt: 200000, lastEntryId: 44, lastEntrySinceForegroundMs: 10, lastEntrySinceBackgroundMs: 60000
  }) } } });
  await h.run('renderDiag()');
  assert.doesNotMatch(h.el('diagList').innerHTML, /返回 App 后才执行|返回 App 后才收到/);
  assert.match(h.el('diagList').innerHTML, /不能据此判定后台提醒正常/);
});
