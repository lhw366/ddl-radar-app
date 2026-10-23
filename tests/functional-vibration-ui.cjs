const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

function nativeFixture(options = {}) {
  const calls = [], lifecycle = [], requests = [];
  let diagnosis = options.diag || { notify: true, channels: 8, pending: 1, headsUp: true,
    channelImportance: 4, overlayAllowed: false, channelVibration: false };
  const plugin = {
    notifyStatus: async () => ({ granted: true }),
    openNotificationSettings: async () => { calls.push('notification-settings'); },
    testVibration: async request => {
      calls.push('test');
      requests.push(request);
      if (options.error) throw new Error('native bridge failed');
      return options.result || { requested: true, hasVibrator: true };
    }
  };
  const h = harness(['app.js'], { globals: {
    NativeNotify: {
      available: () => true,
      refreshDelivered: async () => {},
      isSynced: () => true,
      sync: async state => { calls.push('sync:' + state.settings.vibrationMode); return true; },
      diag: async () => { calls.push('diag'); return typeof diagnosis === 'function' ? diagnosis(h) : diagnosis; }
    },
    Capacitor: { Plugins: { DdlNotify: plugin, App: {
      addListener: async (event, callback) => { if (event === 'appStateChange') lifecycle.push(callback); }
    } } }
  } });
  h.context.toast = (...args) => h.toasts.push(args);
  return Object.assign(h, { calls, lifecycle, requests, plugin, setDiagnosis: value => { diagnosis = value; } });
}

test('upgrading or importing settings preserves system notification vibration until explicitly selected', () => {
  const h = harness(['app.js']);
  for (const settings of [{}, { vibrate: true }, { vibrationMode: 'unknown' }]) {
    assert.equal(h.run(`normalizeState({tasks: [], settings: ${JSON.stringify(settings)}}).settings.vibrationMode`), 'notification');
  }
  assert.equal(h.run('defaultState().settings.vibrationMode'), 'notification');
  assert.equal(h.run("normalizeState({tasks: [], settings: {vibrationMode: 'native', vibrate: false}}).settings.vibrationMode"), 'native');
});

test('vibration source selection persists and reschedules while silent and vibration-off prevent changing it', async () => {
  const h = nativeFixture();
  h.run('bindSettings()');
  const select = h.el('setVibrationMode');
  assert.equal(select.value, 'notification');
  assert.equal(select.disabled, false);
  select.value = 'native';
  await select.dispatch('change');
  assert.equal(h.context.S.settings.vibrationMode, 'native');
  assert.equal(JSON.parse(h.storage.get('ddlr_v1')).settings.vibrationMode, 'native');
  assert.ok(h.calls.includes('sync:native'));

  h.el('setVibrate').checked = false;
  await h.el('setVibrate').dispatch('change');
  assert.equal(select.disabled, true);
  assert.equal(h.el('enableNativeVibrationBtn').disabled, true);
  assert.equal(h.el('vibrationNowBtn').disabled, false, 'explicit hardware self-test remains available');
  select.value = 'notification';
  await select.dispatch('change');
  assert.equal(h.context.S.settings.vibrationMode, 'native');
  h.el('setVibrate').checked = true;
  await h.el('setVibrate').dispatch('change');
  assert.equal(select.disabled, false);
  assert.equal(select.value, 'native');
  h.el('setSilent').checked = true;
  await h.el('setSilent').dispatch('change');
  assert.equal(select.disabled, true);
  assert.equal(h.el('enableNativeVibrationBtn').disabled, true);
  assert.equal(h.context.S.settings.vibrationMode, 'native');
});

test('compatibility action only switches after a click, saves normal reminders and then requests a self-test', async () => {
  const h = nativeFixture({ diag: fixture => ({ notify: true, channels: 8, pending: 1,
    vibrationMode: fixture.context.S.settings.vibrationMode, channelVibration: false }) });
  h.run('bindSettings()');
  assert.equal(h.context.S.settings.vibrationMode, 'notification');
  assert.equal(h.calls.includes('test'), false);
  await h.el('enableNativeVibrationBtn').click();
  assert.equal(h.context.S.settings.vibrationMode, 'native');
  assert.equal(h.el('setVibrationMode').value, 'native');
  assert.ok(h.calls.indexOf('sync:native') < h.calls.indexOf('test'));
  assert.equal(h.calls.filter(call => call === 'test').length, 1);
  assert.match(h.toasts.at(-1).join(' '), /已请求原生震动.*以手机实际触感为准/);
  assert.equal(h.context.lastVibrationSelfTest.requested, true);
  assert.equal(h.el('vibrationNowBtn').disabled, false);
});

test('self-test reports system refusals and missing hardware without claiming a vibration occurred', async () => {
  const cases = [
    [{ requested: false, hasVibrator: false }, /未提供可用震动器/],
    [{ requested: false, hasVibrator: true, suppressed: 'dnd' }, /被系统策略阻止/],
    [{ requested: false, hasVibrator: true, suppressed: 'silent_vibration_disabled' }, /按你的设置保持静默/],
    [{ requested: false, hasVibrator: true, error: 'denied' }, /调用失败/],
    [{ requested: false, hasVibrator: true }, /未接受/]
  ];
  for (const [result, expected] of cases) {
    const h = nativeFixture({ result });
    h.run('bindSettings()');
    await h.el('vibrationNowBtn').click();
    assert.match(h.toasts.at(-1).join(' '), expected);
    assert.doesNotMatch(h.toasts.at(-1).join(' '), /震动成功|手机已震动|后台验证通过/);
    assert.equal(h.context.S.settings.vibrationMode, 'notification', 'self-test alone does not change reminder mode');
  }
  const h = nativeFixture({ error: true });
  h.run('bindSettings()');
  await h.el('vibrationNowBtn').click();
  assert.match(h.toasts.at(-1).join(' '), /自检失败/);
  assert.equal(h.el('vibrationNowBtn').disabled, false);
});

test('system-silent haptics default on while an explicit opt-out survives save, import and self-check', async () => {
  const h = nativeFixture();
  assert.equal(h.run('defaultState().settings.vibrateInSilent'), true);
  assert.equal(h.run('normalizeState({tasks:[],settings:{}}).settings.vibrateInSilent'), true);
  assert.equal(h.run('normalizeState({tasks:[],settings:{vibrateInSilent:false}}).settings.vibrateInSilent'), false);
  h.run('bindSettings()');
  assert.equal(h.el('setVibrateInSilent').checked, true);
  const beforeSound = h.context.S.settings.sound;
  h.el('setVibrateInSilent').checked = false;
  await h.el('setVibrateInSilent').dispatch('change');
  assert.equal(h.context.S.settings.vibrateInSilent, false);
  assert.equal(JSON.parse(h.storage.get('ddlr_v1')).settings.vibrateInSilent, false);
  assert.equal(h.context.S.settings.sound, beforeSound, 'silent haptic choice never enables audio');
  assert.ok(h.calls.some(call => call.startsWith('sync:')));
  await h.el('vibrationNowBtn').click();
  assert.equal(h.requests.at(-1).vibrateInSilent, false);
  h.el('setSilent').checked = true;
  await h.el('setSilent').dispatch('change');
  assert.equal(h.el('setVibrateInSilent').disabled, true);
  assert.equal(h.context.S.settings.vibrateInSilent, false, 'global silent keeps the independent preference');
});

test('silent-ringer diagnostics distinguish the expected quiet channel from disabled notification vibration', async () => {
  const h = nativeFixture({ diag: { notify: true, channels: 14, pending: 1,
    vibrationMode: 'notification', channelId: 'ddlr_high_v6_quiet', channelVibration: false,
    vibrationStatus: 'native_requested', lastVibrationUsage: 'alarm',
    environment: { hasVibrator: true, ringerMode: 0, notificationVolume: 0, interruptionFilter: 1 } } });
  await h.run('renderDiag()');
  const html = h.el('diagList').innerHTML;
  assert.doesNotMatch(html, /当前系统通知通道的震动开关被关闭/);
  assert.match(html, /禁止响铃；默认允许震动/);
  assert.match(html, /仅震动，不使用闹钟音频/);
  assert.match(html, /14\/14/);
  assert.deepEqual(h.calls, ['diag']);
});

test('a silent-haptic opt-out is shown as a preference, without blaming system rejection', async () => {
  const h = nativeFixture({ diag: { notify: true, channels: 14, pending: 0,
    channelId: 'ddlr_high_v6_quiet', vibrationMode: 'notification', channelVibration: false,
    vibrationStatus: 'blocked_preference', environment: { ringerMode: 0, interruptionFilter: 1 } } });
  h.run('S.settings.vibrateInSilent=false');
  await h.run('renderDiag()');
  assert.match(h.el('diagList').innerHTML, /按你的设置关闭手机静音时的震动/);
  assert.match(h.el('diagList').innerHTML, /禁止响铃；已选择不震动/);
  assert.doesNotMatch(h.el('diagList').innerHTML, /被系统静音.*阻止/);
});

test('native mode treats a silent notification channel as intended and shows requests as unconfirmed', async () => {
  const h = nativeFixture({ diag: { notify: true, channels: 8, pending: 1, overlayAllowed: false,
    vibrationMode: 'native', vibrationStatus: 'native_requested', channelVibration: false } });
  await h.run('renderDiag()');
  const html = h.el('diagList').innerHTML;
  assert.doesNotMatch(html, /当前系统通知通道的震动开关被关闭/);
  assert.match(html, /关闭震动属于此模式的正常设置/);
  assert.match(html, /尚非手机实际震动确认/);
  assert.equal(h.context.S.settings.vibrationMode, 'notification', 'read-only diagnostics cannot change a preference');
});

test('blocked notification vibration offers an explicit compatibility repair while disabled reminders do not warn', async () => {
  const h = nativeFixture({ diag: fixture => ({ notify: true, channels: 8, pending: 1,
    vibrationMode: fixture.context.S.settings.vibrationMode, channelVibration: false }) });
  h.run('bindSettings()');
  await h.run('renderDiag()');
  assert.match(h.el('diagList').innerHTML, /当前系统通知通道的震动开关被关闭/);
  const repair = h.el('diagList').querySelector('[data-fix="nativevibration"]');
  assert.ok(repair);
  assert.equal(h.context.S.settings.vibrationMode, 'notification');
  await repair.click();
  assert.equal(h.context.S.settings.vibrationMode, 'native');
  assert.equal(h.calls.filter(call => call === 'test').length, 1);

  h.run("S.settings.vibrationMode = 'notification'; S.settings.vibrate = false");
  await h.run('renderDiag()');
  assert.doesNotMatch(h.el('diagList').innerHTML, /当前系统通知通道的震动开关被关闭/);
  assert.equal(h.el('diagList').querySelector('[data-fix="nativevibration"]'), null);
});

test('diagnostics show read-only device policy and distinguish API level from Android version and banner eligibility', async () => {
  const h = nativeFixture({ diag: { notify: true, channels: 8, pending: 1, overlayAllowed: false,
    headsUp: true, channelImportance: 4, channelVibration: true, device: 'OPPO PME110', sdk: 36, androidRelease: '16',
    environment: { hasVibrator: true, interruptionFilter: 3, ringerMode: 0,
      notificationVolume: 0, notificationVibrationIntensity: -1, vibrateAppOp: 1 } } });
  await h.run('renderDiag()');
  const html = h.el('diagList').innerHTML;
  assert.match(html, /系统铃声模式：静音/);
  assert.match(html, /系统勿扰：全部阻止/);
  assert.match(html, /系统通知音量：0/);
  assert.match(html, /系统震动访问状态：1/);
  assert.doesNotMatch(html, /系统通知震动强度/);
  assert.match(html, /OPPO PME110 · Android 16/);
  assert.match(html, /Android API 级别 36/);
  assert.doesNotMatch(html, /Android 36/);
  assert.match(html, /具备系统横幅条件；是否弹出仍取决于/);
  assert.match(html, /系统横幅需另行开启/);
  assert.deepEqual(h.calls, ['diag'], 'diagnostics only read state');
});

test('returning from system settings refreshes visible settings diagnostics without background reads', async () => {
  const h = nativeFixture();
  h.run("curView = 'settings'; bindSettings()");
  h.document.hidden = true;
  await h.document.dispatch('visibilitychange');
  assert.equal(h.calls.filter(call => call === 'diag').length, 0);
  h.document.hidden = false;
  await h.document.dispatch('visibilitychange');
  assert.equal(h.calls.filter(call => call === 'diag').length, 1);
  assert.match(h.el('diagList').innerHTML, /当前系统通知通道的震动开关被关闭/);
  h.setDiagnosis({ notify: true, channels: 8, pending: 1, channelVibration: true });
  for (const callback of h.lifecycle) await callback({ isActive: true });
  assert.equal(h.calls.filter(call => call === 'diag').length, 2);
  assert.doesNotMatch(h.el('diagList').innerHTML, /当前系统通知通道的震动开关被关闭/);
  h.run("curView = 'home'");
  await h.document.dispatch('visibilitychange');
  for (const callback of h.lifecycle) await callback({ isActive: true });
  assert.equal(h.calls.filter(call => call === 'diag').length, 2);
  const syncCount = h.calls.filter(call => call.startsWith('sync:')).length;
  h.run("curView = 'settings'");
  await h.run('refreshSettingsDiagnostics()');
  assert.equal(h.calls.filter(call => call.startsWith('sync:')).length, syncCount, 'diagnostic refresh itself only reads state');
});

test('system notification settings has its own button without requesting overlay permission', async () => {
  const h = nativeFixture();
  h.run('bindSettings()');
  await h.el('notificationSettingsBtn').click();
  assert.deepEqual(h.calls, ['notification-settings']);
  assert.match(h.el('notificationSettingsBtn').textContent, /系统通知与横幅设置/);
});
