const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

function feedbackHarness(capacitor) {
  const web = { contexts: 0, oscillators: 0, resumed: 0, vibrations: [] };
  class BrowserAudio {
    constructor() { web.contexts++; this.state = 'suspended'; this.currentTime = 1; this.destination = {}; }
    resume() { web.resumed++; this.state = 'running'; }
    createOscillator() {
      web.oscillators++;
      return { frequency: {}, connect() {}, start() {}, stop() {} };
    }
    createGain() {
      return { gain: { setValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect() {} };
    }
  }
  const h = harness(['app.js'], { globals: { AudioContext: BrowserAudio,
    navigator: { vibrate: pattern => web.vibrations.push(Array.from(pattern)) },
    ...(capacitor ? { Capacitor: capacitor } : {}) } });
  return Object.assign(h, { web });
}

test('APK foreground sound and vibration always use native policy and preserve the silent preference', async () => {
  for (const detection of ['native-platform', 'plugin-only', 'plugin-on-nonnative']) {
    const calls = [];
    const capacitor = { Plugins: { DdlNotify: { foregroundFeedback: async args => { calls.push({ ...args }); } } } };
    if (detection !== 'plugin-only') capacitor.isNativePlatform = () => detection === 'native-platform';
    const h = feedbackHarness(capacitor);
    for (const preference of [true, false]) {
      h.context.S.settings.vibrateInSilent = preference;
      await h.run('beep()');
      await h.run('vibe()');
      assert.deepEqual(calls.slice(-2), [
        { sound: true, vibrate: false, vibrateInSilent: preference },
        { sound: false, vibrate: true, vibrateInSilent: preference }
      ]);
    }
    assert.equal(h.web.contexts, 0);
    assert.deepEqual(h.web.vibrations, []);
  }
});

test('APK global silence and independent output switches prevent native foreground feedback', async () => {
  const calls = [];
  const h = feedbackHarness({ isNativePlatform: () => true, Plugins: { DdlNotify: {
    foregroundFeedback: async args => { calls.push({ ...args }); }
  } } });
  h.context.S.settings.remindSilent = true;
  await h.run('beep()');
  await h.run('vibe()');
  assert.equal(calls.length, 0);
  h.context.S.settings.remindSilent = false;
  h.context.S.settings.sound = false;
  await h.run('beep()');
  await h.run('vibe()');
  assert.deepEqual(calls, [{ sound: false, vibrate: true, vibrateInSilent: true }]);
  h.context.S.settings.vibrate = false;
  h.context.S.settings.sound = true;
  await h.run('beep()');
  await h.run('vibe()');
  assert.deepEqual(calls.at(-1), { sound: true, vibrate: false, vibrateInSilent: true });
  assert.equal(calls.length, 2);
  assert.equal(h.web.contexts, 0);
  assert.deepEqual(h.web.vibrations, []);
});

test('missing, throwing or rejected APK feedback never falls back to browser sound or vibration', async () => {
  for (const failure of ['no-plugin', 'missing-method', 'throws', 'rejects']) {
    const plugin = failure === 'no-plugin' ? undefined : failure === 'missing-method' ? {} : {
      foregroundFeedback: failure === 'throws'
        ? () => { throw new Error('native bridge failed'); }
        : async () => { throw new Error('native bridge rejected'); }
    };
    const h = feedbackHarness({ isNativePlatform: () => true, Plugins: { DdlNotify: plugin } });
    await assert.doesNotReject(async () => { await h.run('beep()'); await h.run('vibe()'); });
    assert.equal(h.web.contexts, 0, failure);
    assert.deepEqual(h.web.vibrations, [], failure);
  }
});

test('theme selection, task completion and foreground catch-up share the native feedback route', async () => {
  const calls = [];
  const h = feedbackHarness({ isNativePlatform: () => true, Plugins: { DdlNotify: {
    foregroundFeedback: async args => { calls.push({ ...args }); }
  } } });
  h.context.S.settings.vibrateInSilent = false;
  h.run('renderAll = () => {}; applyTheme = () => {}; renderThemeGrids();');
  await h.document.querySelector('.theme-card').click();
  const card = h.run(`taskCard({ id:'done-event', name:'事项', dueAt:Date.now()+60000,
    quadrant:'q2', createdAt:Date.now(), done:false })`);
  await card.querySelector('.t-check').click();
  await h.run(`flushCatchUps([{ r:{ title:'截止', body:'任务已到点', kind:'urgent' }, late:true }])`);
  assert.deepEqual(calls, [
    { sound: true, vibrate: false, vibrateInSilent: false },
    { sound: true, vibrate: false, vibrateInSilent: false },
    { sound: true, vibrate: false, vibrateInSilent: false },
    { sound: false, vibrate: true, vibrateInSilent: false }
  ]);
  assert.equal(h.web.contexts, 0);
  assert.deepEqual(h.web.vibrations, []);
});

test('ordinary browser sound and vibration retain their existing behavior and output switches', async () => {
  const h = feedbackHarness({ isNativePlatform: () => false, Plugins: {} });
  await h.run('beep()');
  await h.run('vibe()');
  assert.equal(h.web.contexts, 1);
  assert.equal(h.web.resumed, 1);
  assert.equal(h.web.oscillators, 2);
  assert.deepEqual(h.web.vibrations, [[180, 90, 180]]);
  h.context.S.settings.sound = false;
  h.context.S.settings.vibrate = false;
  await h.run('beep()');
  await h.run('vibe()');
  h.context.S.settings.sound = true;
  h.context.S.settings.vibrate = true;
  h.context.S.settings.remindSilent = true;
  await h.run('beep()');
  await h.run('vibe()');
  assert.equal(h.web.oscillators, 2);
  assert.equal(h.web.vibrations.length, 1);
});
