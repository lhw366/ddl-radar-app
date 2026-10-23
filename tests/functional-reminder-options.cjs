const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

test('old reminder presets migrate to independent sound, vibration, and banner fields', () => {
  const h = harness(['nativenotify.js', 'app.js']);
  const migrated = h.run(`normalizeState({
    tasks: [],
    fired: [],
    settings: { remindMode: 'vib' }
  }).settings`);
  assert.equal(migrated.sound, false);
  assert.equal(migrated.vibrate, true);
  assert.equal(migrated.banner, true);
  assert.equal(migrated.remindSilent, false);

  const silent = h.run(`normalizeState({
    tasks: [],
    fired: [],
    settings: { remindMode: 'silent' }
  }).settings`);
  assert.equal(silent.remindSilent, true);
});

test('reminder controls are checked independently and silent disables the three outputs', async () => {
  const h = harness(['nativenotify.js', 'app.js']);
  h.run('bindSettings()');
  assert.equal(h.el('setSound').checked, true);
  assert.equal(h.el('setVibrate').checked, true);
  assert.equal(h.el('setBanner').checked, true);

  h.el('setSound').checked = false;
  await h.el('setSound').dispatch('change');
  h.el('setBanner').checked = false;
  await h.el('setBanner').dispatch('change');
  assert.equal(h.context.S.settings.sound, false);
  assert.equal(h.context.S.settings.vibrate, true);
  assert.equal(h.context.S.settings.banner, false);

  h.el('setSilent').checked = true;
  await h.el('setSilent').dispatch('change');
  assert.equal(h.context.S.settings.remindSilent, true);
  assert.equal(h.el('setSound').disabled, true);
  assert.equal(h.el('setVibrate').disabled, true);
  assert.equal(h.el('setBanner').disabled, true);
  assert.equal(h.run('reminderOutputsEnabled()'), false);
});

test('native channel combines only banner and sound; vibration remains a separate flag', () => {
  const h = harness(['nativenotify.js']);
  const channel = h.run(`NativeNotify.channelIdFor({
    sound: true,
    vibrate: false,
    banner: false,
    remindSilent: false
  })`);
  assert.equal(channel, 'ddlr_gentle_v3_sound');
  assert.equal(h.run('NativeNotify.outputsEnabled({sound:false,vibrate:true,banner:false,remindSilent:false})'), true);
  assert.equal(h.run('NativeNotify.outputsEnabled({sound:true,vibrate:true,banner:true,remindSilent:true})'), false);
});

test('five-second reminder uses the same reconciliation as real events', async () => {
  let scheduled = null;
  const ddl = {
    scheduleOne: async () => { throw new Error('test-only scheduling is forbidden'); },
    apply: async (payload) => { scheduled = payload.alarms.find(item => item.id === 990000001); },
    pending: async () => ({ items: [{ id: 990000001 }] })
  };
  const h = harness(['nativenotify.js'], {
    globals: {
      Capacitor: {
        isNativePlatform: () => true,
        Plugins: {
          DdlAlarm: ddl,
          DdlNotify: { notifyStatus: async () => ({ granted: true }) }
        }
      }
    }
  });
  h.context.S.settings = { sound: false, vibrate: true, banner: false, remindSilent: false };
  const ok = await h.context.NativeNotify.test();
  assert.equal(ok, true);
  assert.equal(scheduled.at - h.context.Date.now(), 5000);
  assert.equal(scheduled.channelId, 'ddlr_gentle_v3_silent');
  assert.equal(scheduled.vibrate, true);
});

test('sixty-second background verification uses the normal event scheduling path', async () => {
  let scheduled;
  const h = harness(['nativenotify.js'], { globals: {
    Capacitor: { isNativePlatform: () => true, Plugins: { DdlAlarm: {
      apply: async payload => { scheduled = payload.alarms.find(item => item.id === 990000001); },
      pending: async () => ({ items: [{ id: 990000001 }] })
    } } }
  } });
  assert.equal(await h.context.NativeNotify.test(60), true);
  assert.equal(scheduled.at - h.context.Date.now(), 60000);
});

test('silent clears native alarms and vibration-only schedules a silent channel flag', async () => {
  const applied = [];
  const ddl = {
    apply: async (payload) => { applied.push(payload); return payload; }
  };
  const h = harness(['nativenotify.js'], {
    globals: {
      Capacitor: {
        isNativePlatform: () => true,
        Plugins: { DdlAlarm: ddl }
      }
    }
  });
  h.context.S.tasks = [];
  h.context.S.settings = { sound: true, vibrate: true, banner: true, remindSilent: true };
  await h.context.NativeNotify.sync(h.context.S);
  assert.equal(Array.isArray(applied[0].alarms), true);
  assert.equal(applied[0].alarms.length, 0);

  const at = h.context.Date.now() + 5000;
  h.context.localStorage.setItem('ddlr_testAt', String(at));
  h.context.S.settings = { sound: false, vibrate: true, banner: false, remindSilent: false };
  h.context.NativeNotify._sig = null;
  await h.context.NativeNotify.sync(h.context.S);
  assert.equal(applied[1].alarms[0].channelId, 'ddlr_gentle_v3_silent');
  assert.equal(applied[1].alarms[0].vibrate, true);
});

test('reconciliation retains imminent and just-due test reminders', async () => {
  for (const offset of [2999, 0, -1001]) {
    let alarms;
    const h = harness(['nativenotify.js'], { globals: {
      Capacitor: { isNativePlatform: () => true, Plugins: {
        DdlAlarm: { apply: async (payload) => { alarms = payload.alarms; } }
      } }
    } });
    const at = h.context.Date.now() + offset;
    h.context.localStorage.setItem('ddlr_testAt', String(at));
    await h.context.NativeNotify.sync(h.context.S);
    assert.equal(alarms.length, 1, 'must retain test at offset ' + offset);
    assert.equal(alarms[0].at, at);
  }
});

test('reconciliation retains an event crossing its deadline while JS is busy', async () => {
  let alarms;
  const h = harness(['nativenotify.js'], { globals: {
    Capacitor: { isNativePlatform: () => true, Plugins: {
      DdlAlarm: { apply: async (payload) => { alarms = payload.alarms; } }
    } },
    planReminders: () => [{ key: 'deadline', at: 1000, title: '到点', body: '' }]
  } });
  h.now(2500);
  h.context.S.tasks = [{ id: 'event' }];
  await h.context.NativeNotify.sync(h.context.S);
  assert.equal(alarms.length, 1);
  assert.equal(alarms[0].at, 1000);
});

test('banner-only reminders use the migrated high importance silent channel', async () => {
  const created = [];
  const h = harness(['nativenotify.js'], { globals: {
    Capacitor: { Plugins: { DdlNotify: { ensureChannel: async (definition) => created.push(definition) } } }
  } });
  await h.context.NativeNotify.ensureChannels();
  const channelId = h.context.NativeNotify.channelIdFor({ sound: false, vibrate: false, banner: true });
  const definition = created.find((item) => item.id === channelId);
  assert.equal(definition.importance, 4);
  assert.equal(definition.vibration, false);
  assert.equal(definition.sound, 'ddlr_silence.wav');
});
