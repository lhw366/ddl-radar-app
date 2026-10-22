const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

function nativeHarness(apply, extra = {}, alarm = {}) {
  const h = harness(['nativenotify.js', 'app.js'], { globals: {
    Capacitor: { isNativePlatform: () => true, Plugins: {
      DdlAlarm: { apply, ...alarm },
      DdlNotify: { configureDisplay: async () => ({ overlayAllowed: true }), ...extra }
    } }
  } });
  // app.js declares its own toast(), which shadows the fixture spy installed on
  // the sandbox globals. Re-install the spy after load so toast assertions are real.
  h.run('window.__toasts = []; toast = function () { window.__toasts.push(Array.prototype.slice.call(arguments)); };');
  h.toasts = h.context.__toasts;
  return h;
}

test('a real saved event reaches native scheduling before its deadline without opening it again', async () => {
  const plans = [];
  const h = nativeHarness(async payload => { plans.push(payload.alarms); });
  h.run(`S.tasks.push({ id: 'normal', name: '正常事件', dueAt: Date.now() + 120000,
    tiers: {}, custom: [], daily: false, done: false, quadrant: 'q1' }); S.settings.tiered = false;`);
  assert.equal(await h.run('save()'), true);
  assert.equal(plans.length, 1);
  assert.equal(plans[0].length, 1);
  assert.equal(plans[0][0].at, h.context.Date.now() + 120000);
  assert.equal(plans[0][0].vibrate, true);
  assert.match(plans[0][0].body, /正常事件/);
});

test('deadline and custom advance reminder survive beyond the former seven-day window', async () => {
  let alarms;
  const h = nativeHarness(async payload => { alarms = payload.alarms; });
  h.run(`S.settings.tiered = false;
    S.tasks.push({ id: 'far', name: '两周后', dueAt: Date.now() + 14 * 86400000,
      custom: [{v: 1, u: 'h'}], daily: false, quadrant: 'q2' });`);
  await h.run('save()');
  assert.equal(alarms.length, 2);
  assert.equal(alarms.at(-1).at, h.context.Date.now() + 14 * 86400000);
});

test('editing a real event reschedules even when an old import omitted updatedAt', async () => {
  const plans = [];
  const h = nativeHarness(async payload => { plans.push(payload.alarms); });
  h.run(`S.settings.tiered = false; S.tasks.push({ id: 'edit', name: '修改前',
    dueAt: Date.now() + 60000, daily: false, quadrant: 'q2' });`);
  await h.run('save()');
  h.run(`S.tasks[0].dueAt += 60000; S.tasks[0].name = '修改后'`);
  await h.run('save()');
  assert.equal(plans.length, 2);
  assert.equal(plans[1][0].at - plans[0][0].at, 60000);
  assert.match(plans[1][0].body, /修改后/);
});

test('slow native reconciliation finishes before a newer event edit is applied', async () => {
  const plans = [];
  let finish;
  const h = nativeHarness(payload => {
    plans.push(payload.alarms);
    return plans.length === 1 ? new Promise(resolve => { finish = resolve; }) : Promise.resolve({});
  });
  h.run(`S.settings.tiered = false; S.tasks.push({ id: 'race', name: '原计划',
    dueAt: Date.now() + 60000, daily: false, quadrant: 'q2' });`);
  const first = h.run('save()');
  await new Promise(resolve => setImmediate(resolve));
  h.run(`S.tasks[0].dueAt += 60000`);
  const second = h.run('save()');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(plans.length, 1);
  finish({});
  await Promise.all([first, second]);
  assert.equal(plans.length, 2);
  assert.equal(plans[1][0].at - plans[0][0].at, 60000);
});

test('a failed native save is reported and retried with the same event', async () => {
  let count = 0;
  const h = nativeHarness(async () => { if (++count === 1) throw new Error('system unavailable'); });
  h.run(`S.tasks.push({ id: 'retry', name: '重试', dueAt: Date.now() + 60000, daily: false, quadrant: 'q2' })`);
  assert.equal(await h.run('save()'), false);
  assert.equal(h.context.NativeNotify.isSynced(), false);
  assert.equal(await h.run('save()'), true);
  assert.equal(count, 2);
});

test('manual and five-second banner choices persist and configure the native display', async () => {
  const settings = [];
  const h = nativeHarness(async () => ({}), {
    configureDisplay: async payload => settings.push(payload.bannerDuration)
  });
  h.run('bindSettings()');
  assert.equal(h.el('setBannerDuration').value, 'manual');
  h.el('setBannerDuration').value = '5s';
  await h.el('setBannerDuration').dispatch('change');
  await h.context.NativeNotify._queue;
  assert.equal(h.context.S.settings.bannerDuration, '5s');
  assert.equal(settings.at(-1), '5s');
  h.el('setBannerDuration').value = 'manual';
  await h.el('setBannerDuration').dispatch('change');
  await h.context.NativeNotify._queue;
  assert.equal(settings.at(-1), 'manual');
});

test('the event editor waits for native acceptance before confirming a saved event', async () => {
  let finish, scheduled;
  const h = nativeHarness(payload => {
    scheduled = payload.alarms;
    return new Promise(resolve => { finish = resolve; });
  });
  h.context.renderAll = () => {};
  h.el('fName').value = '后台事件';
  h.el('fDetail').value = '';
  h.el('fDue').value = h.run('dtLocalValue(Date.now() + 120000)');
  const saving = h.run('saveEditor()');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.el('fSave').disabled, true);
  assert.ok(scheduled.some(alarm => alarm.title === '🚨 时间到！'));
  finish({ added: scheduled.length });
  await saving;
  assert.equal(h.el('fSave').disabled, false);
  assert.equal(h.el('editorWrap').classList.contains('hidden'), true);
});

test('opening the APK does not replay a due event the native side already delivered', async () => {
  // De-duplication must key off the native delivery log, not off a 5s time guess:
  // the old heuristic either duplicated every delivered reminder or (when it was
  // bypassed with a blanket return) silenced the only fallback entirely.
  const log = [];
  const h = nativeHarness(async () => ({}), {}, { pending: async () => ({ deliveredLog: log }) });
  const dueAt = h.context.Date.now() - 6000;
  h.run(`S.settings.tiered = false; S.tasks.push({ id: 'native-owned', name: '已由原生处理',
    dueAt: ${dueAt}, daily: false, quadrant: 'q2' });`);
  log.push([h.run(`NativeNotify.idOf('native-owned|due')`), dueAt]);
  h.run('tickReminders()');
  await h.run('tickReminders._flush');
  assert.equal(h.toasts.length, 0, 'a delivered occurrence must not be replayed');
  assert.equal(h.context.S.fired.length, 1, 'the reminder is still consumed exactly once');
});

test('opening the APK replays a due event the native side never delivered', async () => {
  const h = nativeHarness(async () => ({}), {}, { pending: async () => ({ deliveredLog: [] }) });
  h.run(`S.settings.tiered = false; S.tasks.push({ id: 'missed', name: '原生漏送',
    dueAt: Date.now() - 6000, daily: false, quadrant: 'q2' }); tickReminders();`);
  await h.run('tickReminders._flush');
  assert.equal(h.toasts.length, 1, 'a missed occurrence must surface in the app');
  assert.match(h.toasts[0][0], /补弹/);
});

test('a just-due event is not consumed while native delivery may still be in flight', () => {
  // Regression: the old loop marked the reminder as fired *before* deciding
  // whether native still had to deliver it, so a reminder the native side never
  // delivered was swallowed permanently.
  const h = nativeHarness(async () => ({}));
  h.run(`NativeNotify._synced = true; NativeNotify._syncedAt = Date.now();
    S.settings.tiered = false; S.tasks.push({ id: 'in-flight', name: '正在派发',
      dueAt: Date.now() - 1000, daily: false, quadrant: 'q2' }); tickReminders();`);
  assert.equal(h.context.S.fired.length, 0,
    'a reminder inside the native delivery window stays pending for the next tick');
});
