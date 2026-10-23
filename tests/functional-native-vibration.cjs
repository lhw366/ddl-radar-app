const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

function nativeHarness({ mode, granted = true, vibration = true, timers = [], pendingFields = {} } = {}) {
  const applied = [], queries = [];
  const h = harness(['nativenotify.js'], { globals: {
    setTimeout: (fn, delay) => { timers.push({ fn, delay }); return timers.length; },
    planReminders: () => [
      { key: 'normal-tier', at: h.context.Date.now() + 120000, title: '五分钟梯度', body: '任务' },
      { key: 'normal-deadline', at: h.context.Date.now() + 420000, title: '截止提醒', body: '任务' }
    ],
    Timetable: { planFor: () => [{ key: 'normal-class', at: h.context.Date.now() + 180000,
      title: '课程提醒', body: '教室', repeatDays: 7 }] },
    Capacitor: { isNativePlatform: () => true, Plugins: {
      DdlAlarm: {
        apply: async payload => { applied.push(payload); },
        pending: async query => {
          queries.push(query);
          return { items: applied.at(-1)?.alarms || [], channelVibration: vibration, ...pendingFields };
        }
      },
      DdlNotify: {
        notifyStatus: async () => ({ granted }),
        listChannelIds: async () => ({ ids: [] })
      }
    } }
  } });
  h.context.S.tasks = [{ id: 'normal-task' }];
  h.context.S.settings = { sound: false, vibrate: true, banner: true };
  if (mode !== undefined) h.context.S.settings.vibrationMode = mode;
  return { h, applied, queries, timers };
}

test('existing settings keep notification vibration even when that system channel is disabled', async () => {
  const { h, applied, queries } = nativeHarness({ vibration: false });
  await h.context.NativeNotify.sync(h.context.S);
  assert.equal(applied[0].alarms.length, 3);
  assert.ok(applied[0].alarms.every(item => item.channelId === 'ddlr_high_v4_vib_silent'));
  const diagnostic = await h.context.NativeNotify.diag();
  assert.equal(diagnostic.vibrationMode, 'notification');
  assert.equal(diagnostic.channelVibration, false);
  assert.equal(queries.at(-1).channelId, 'ddlr_high_v4_vib_silent');
  assert.equal(applied.length, 1, 'reading diagnostics must not replace user channel choices');
});

test('explicit compatibility mode creates four additional non-vibrating channels without deleting existing ones', async () => {
  const created = [];
  const h = harness(['nativenotify.js'], { globals: { Capacitor: { Plugins: { DdlNotify: {
    ensureChannel: async definition => created.push(definition)
  } } } } });
  await h.context.NativeNotify.ensureChannels();
  assert.equal(created.length, 12);
  assert.equal(new Set(created.map(item => item.id)).size, 12);
  assert.equal(created.filter(item => item.id.includes('_v4_')).length, 8);
  for (const banner of [false, true]) for (const sound of [false, true]) {
    const id = h.context.NativeNotify.channelIdFor({ banner, sound, vibrate: true, vibrationMode: 'native' });
    assert.equal(id, `ddlr_${banner ? 'high' : 'gentle'}_v5_native_${sound ? 'sound' : 'silent'}`);
    const channel = created.find(item => item.id === id);
    assert.ok(channel);
    assert.equal(channel.vibration, false, 'the finite native service must be the only vibration producer');
    assert.equal(channel.sound, '', 'avoid system sound-to-vibration fallback in vibrate mode');
    assert.equal(channel.importance, banner ? 4 : 3);
    assert.match(channel.name, /原生兼容震动/);
    assert.equal(h.context.NativeNotify.channelIdFor({ banner, sound, vibrate: false, vibrationMode: 'native' }),
      `ddlr_${banner ? 'high' : 'gentle'}_v5_native_${sound ? 'sound' : 'silent'}`);
  }
});

test('normal task tiers, deadlines, courses and both background tests share the explicitly selected route', async () => {
  for (const mode of ['notification', 'native']) for (const seconds of [5, 60]) {
    const { h, applied, queries } = nativeHarness({ mode });
    assert.equal(await h.context.NativeNotify.test(seconds), true);
    const alarms = applied.at(-1).alarms;
    assert.equal(alarms.length, 4);
    const channelId = mode === 'native' ? 'ddlr_high_v5_native_silent' : 'ddlr_high_v4_vib_silent';
    assert.ok(alarms.every(item => item.channelId === channelId && item.vibrate === true));
    const trial = alarms.find(item => item.id === 990000001);
    assert.equal(trial.at - h.context.Date.now(), seconds * 1000);
    assert.equal(alarms.find(item => item.title === '课程提醒').repeatDays, 7);
    assert.equal(queries.at(-1).channelId, channelId);
    assert.equal(queries.at(-1).vibrationMode, mode);
  }
});

test('choosing compatibility mode resynchronizes every future normal alarm without changing output preferences', async () => {
  const { h, applied } = nativeHarness();
  await h.context.NativeNotify.sync(h.context.S);
  h.context.S.settings.vibrationMode = 'native';
  await h.context.NativeNotify.sync(h.context.S);
  assert.equal(applied.length, 2, 'mode changes must invalidate the reconciliation signature');
  assert.ok(applied[0].alarms.every(item => item.channelId === 'ddlr_high_v4_vib_silent'));
  assert.ok(applied[1].alarms.every(item => item.channelId === 'ddlr_high_v5_native_silent'));
  assert.equal(h.context.S.settings.vibrate, true);
  assert.equal(h.context.S.settings.sound, false);
  assert.equal(h.context.S.settings.banner, true);
});

test('diagnostics query the current channel and preserve actual native vibration status', async () => {
  const { h, queries } = nativeHarness({ mode: 'native', vibration: false, pendingFields: {
    vibrationMode: 'native', vibrationStatus: 'blocked_policy', lastVibrationStatus: 'blocked_policy',
    lastVibrationChannel: 'ddlr_high_v5_native_silent'
  } });
  const diagnostic = await h.context.NativeNotify.diag();
  assert.equal(queries.at(-1).channelId, 'ddlr_high_v5_native_silent');
  assert.equal(diagnostic.channelVibration, false, 'native channels are intentionally silent for vibration');
  assert.equal(diagnostic.vibrationMode, 'native');
  assert.equal(diagnostic.vibrationStatus, 'blocked_policy');
  assert.equal(diagnostic.lastVibrationStatus, 'blocked_policy');
  assert.equal(diagnostic.lastVibrationChannel, 'ddlr_high_v5_native_silent');
});

test('a scheduled test warns about disabled system vibration without claiming hardware success', async () => {
  const { h } = nativeHarness({ mode: 'notification', vibration: false });
  assert.equal(await h.context.NativeNotify.test(5), true);
  assert.match(h.toasts.at(-1)[1], /系统提醒通道的震动已关闭/);
  assert.doesNotMatch(h.toasts.at(-1).join(' '), /应已响铃|应响铃|应震动/);
});

test('denied notifications warn about the disabled notification route without silently changing the mode', async () => {
  const { h, applied } = nativeHarness({ granted: false });
  assert.equal(await h.context.NativeNotify.test(60), true);
  assert.match(h.toasts.at(-1)[1], /系统通知与通知通道震动不可用/);
  assert.ok(applied.at(-1).alarms.every(item => item.channelId === 'ddlr_high_v4_vib_silent'));
});

test('test readback distinguishes receiver delivery from hardware vibration in either mode', async () => {
  for (const mode of ['notification', 'native']) {
    const timers = [];
    const { h } = nativeHarness({ mode, vibration: false, timers });
    assert.equal(await h.context.NativeNotify.test(5), true);
    const at = h.context.Date.now() + 5000;
    h.context.Capacitor.Plugins.DdlAlarm.pending = async () => ({ items: [], lastFireId: 990000001,
      lastFireAt: at, lastFireVia: 'alarmClock', channelVibration: false, lastVibrationStatus: 'native_requested' });
    timers.find(timer => timer.delay === 8000).fn();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(h.toasts.at(-1)[0], /测试闹钟已送达/);
    assert.match(h.toasts.at(-1)[1], mode === 'native' ? /调用记录不能证明硬件已震动/ : /通道的震动/);
    assert.doesNotMatch(h.toasts.at(-1).join(' '), /应已响铃|应响铃|应震动/);
  }
});
