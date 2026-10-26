const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

test('OPPO b101 late service arrival is shown before permission checks and startup access remains unknown', async () => {
  const diag = { device: 'OPPO PME110', pending: 40, notify: true, exactOk: true,
    lastDelayMs: 48550, lastDeliveryAppVisible: true, lastEntryAt: 1792993330527,
    lastEntrySinceForegroundMs: 43, lastEntrySinceBackgroundMs: 51867,
    backgroundAccess: { colorOS: true, state: 'unknown', startupPageAvailable: true } };
  const h = harness(['app.js'], { globals: { NativeNotify: { available: () => true, diag: async () => diag } } });
  await h.run('renderDiag()');
  const html = h.el('diagList').innerHTML;
  assert.match(html, /后台提醒失败.*48\.55/);
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
