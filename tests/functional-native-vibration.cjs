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
  assert.equal(created.length, 14);
  assert.equal(new Set(created.map(item => item.id)).size, 14);
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

test('silent-phone vibration preference reaches normal tiers, deadlines, courses and both tests without changing sound', async () => {
  for (const mode of ['notification', 'native']) for (const seconds of [5, 60]) {
    for (const preference of [undefined, true, false]) for (const sound of [false, true]) {
      const { h, applied, queries } = nativeHarness({ mode });
      h.context.S.settings.sound = sound;
      if (preference !== undefined) h.context.S.settings.vibrateInSilent = preference;
      assert.equal(await h.context.NativeNotify.test(seconds), true);
      const alarms = applied.at(-1).alarms;
      assert.equal(alarms.length, 4);
      assert.ok(alarms.every(item => item.vibrateInSilent === (preference !== false)));
      assert.ok(alarms.every(item => item.channelId.endsWith(sound ? '_sound' : '_silent')));
      assert.equal(h.context.S.settings.sound, sound, 'silent-phone vibration must never enable ringing');
      assert.equal(alarms.find(item => item.id === 990000001).at - h.context.Date.now(), seconds * 1000);
      assert.equal(queries.at(-1).vibrateInSilent, preference !== false);
    }
  }
});

test('changing only silent-phone vibration preference updates all future alarms', async () => {
  const { h, applied } = nativeHarness({ mode: 'native' });
  await h.context.NativeNotify.sync(h.context.S);
  h.context.S.settings.vibrateInSilent = false;
  await h.context.NativeNotify.sync(h.context.S);
  assert.equal(applied.length, 2);
  assert.ok(applied[0].alarms.every(item => item.vibrateInSilent === true));
  assert.ok(applied[1].alarms.every(item => item.vibrateInSilent === false));
  assert.deepEqual(applied[0].alarms.map(item => item.id), applied[1].alarms.map(item => item.id));
  assert.deepEqual(applied[0].alarms.map(item => item.channelId), applied[1].alarms.map(item => item.channelId));
});

test('silent-phone preference never overrides app silent mode or disabled vibration', async () => {
  for (const preference of [true, false]) {
    for (const disabled of ['remindSilent', 'allOutputs']) {
      const { h, applied } = nativeHarness({ mode: 'native' });
      h.context.S.settings.vibrateInSilent = preference;
      if (disabled === 'remindSilent') h.context.S.settings.remindSilent = true;
      else Object.assign(h.context.S.settings, { sound: false, vibrate: false, banner: false });
      h.context.localStorage.setItem('ddlr_testAt', h.context.Date.now() + 5000);
      await h.context.NativeNotify.sync(h.context.S);
      assert.equal(applied.at(-1).alarms.length, 0);
      await h.context.NativeNotify.test(5);
      assert.equal(applied.length, 1, 'disabled outputs must not queue a new test alarm');
    }
    const { h, applied } = nativeHarness({ mode: 'native' });
    Object.assign(h.context.S.settings, { vibrateInSilent: preference, vibrate: false, sound: true, banner: true });
    await h.context.NativeNotify.sync(h.context.S);
    assert.ok(applied.at(-1).alarms.every(item => item.vibrate === false));
    assert.ok(applied.at(-1).alarms.every(item => item.vibrateInSilent === preference));
  }
});

test('system-silent visual channels keep high and gentle notification choices separate from haptics', async () => {
  const created = [];
  const h = harness(['nativenotify.js'], { globals: { Capacitor: { Plugins: { DdlNotify: {
    ensureChannel: async definition => created.push(definition)
  } } } } });
  await h.context.NativeNotify.ensureChannels();
  const quiet = created.filter(channel => channel.id.endsWith('_v6_quiet'));
  assert.equal(quiet.length, 2);
  const high = quiet.find(channel => channel.id === 'ddlr_high_v6_quiet');
  const gentle = quiet.find(channel => channel.id === 'ddlr_gentle_v6_quiet');
  assert.equal(high.importance, 4);
  assert.equal(high.sound, 'android.resource://com.ddlradar.app/raw/ddlr_silence');
  assert.equal(high.vibration, false);
  assert.equal(gentle.importance, 3);
  assert.equal(gentle.sound, '');
  assert.equal(gentle.vibration, false);
});

test('diagnostics retain silent-phone preference, actual usage and explicit preference-blocked status', async () => {
  const { h, queries } = nativeHarness({ pendingFields: {
    channelId: 'ddlr_high_v6_quiet', vibrateInSilent: false,
    vibrationStatus: 'blocked_preference', lastVibrationStatus: 'blocked_preference', lastVibrationUsage: 'none'
  } });
  h.context.S.settings.vibrateInSilent = false;
  const diagnostic = await h.context.NativeNotify.diag();
  assert.equal(queries.at(-1).vibrateInSilent, false);
  assert.equal(diagnostic.vibrateInSilent, false);
  assert.equal(diagnostic.channelId, 'ddlr_high_v6_quiet');
  assert.equal(diagnostic.lastVibrationUsage, 'none');
  assert.equal(diagnostic.lastVibrationStatus, 'blocked_preference');

  const legacyReadback = nativeHarness();
  legacyReadback.h.context.S.settings.vibrateInSilent = false;
  assert.equal((await legacyReadback.h.context.NativeNotify.diag()).vibrateInSilent, false,
    'an older native readback must not overwrite the explicit preference');
});

test('quiet-channel test readback never mislabels intentionally disabled channel vibration as a fault', async () => {
  for (const preference of [true, false]) {
    const timers = [];
    const { h } = nativeHarness({ vibration: false, timers, pendingFields: {
      channelId: 'ddlr_high_v6_quiet'
    } });
    h.context.S.settings.vibrateInSilent = preference;
    assert.equal(await h.context.NativeNotify.test(5), true);
    assert.doesNotMatch(h.toasts.at(-1)[1], /系统提醒通道的震动已关闭/);
    h.context.Capacitor.Plugins.DdlAlarm.pending = async () => ({ items: [], lastFireId: 990000001,
      lastFireAt: h.context.Date.now() + 5000, channelId: 'ddlr_high_v6_quiet', channelVibration: false,
      lastVibrationUsage: preference ? 'alarm' : 'none',
      lastVibrationStatus: preference ? 'native_requested' : 'blocked_preference' });
    timers.find(timer => timer.delay === 8000).fn();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(h.toasts.at(-1)[1], preference ? /纯触觉路径/ : /已关闭.*系统静音时震动/);
    assert.doesNotMatch(h.toasts.at(-1)[1], /系统已关闭当前提醒通道的震动/);
  }
});
