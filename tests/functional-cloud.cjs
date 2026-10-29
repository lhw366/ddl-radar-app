'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');
const { snapshot, fingerprint } = require('../www/js/cloud-model.js');

const task = (id, name = id) => ({ id, name, dueAt: Date.now() + 3600000,
  detail: '', quadrant: 'q2', custom: [], tiers: {}, daily: false });
async function setup(options = {}) {
  let user = options.user === undefined ? { id: 'alice', email: 'alice@example.com' } : options.user;
  let callback, sdkOptions;
  const rows = new Map(Object.entries(options.rows || {})), calls = [];
  const auth = {
    onAuthStateChange: fn => { callback = fn; },
    getSession: async () => ({ data: { session: user ? { user } : null } }),
    startAutoRefresh: async () => { calls.push('refresh:start'); },
    stopAutoRefresh: async () => { calls.push('refresh:stop'); },
    signInWithPassword: async value => { calls.push(['login', value.email]); return { data: { session: { user } } }; },
    signUp: async value => { calls.push(['signup', value]); return { data: { session: null } }; },
    resetPasswordForEmail: async (email, value) => { calls.push(['reset', email, value]); return {}; },
    resend: async value => { calls.push(['resend', value]); return {}; },
    signOut: async () => { calls.push('signout'); return {}; },
    updateUser: async value => { calls.push(['password', value]); return {}; },
    exchangeCodeForSession: async (code, opts) => { calls.push(['exchange', code, opts]); return { data: { session: { user }, redirectType: options.recovery ? 'recovery' : null } }; }
  };
  const client = {
    auth,
    from: table => ({ select: columns => ({ eq: (field, id) => ({ maybeSingle: async () => {
      calls.push(['read', id]);
      if (options.onRead) await options.onRead(h);
      if (options.offline) return { error: new Error('network offline') };
      return { data: rows.get(id) || null };
    } }) }) }),
    rpc: async (name, args) => {
      calls.push(['write', user.id, structuredClone(args)]);
      if (options.onWrite) await options.onWrite(h);
      const old = rows.get(user.id);
      if (options.conflict || (old ? old.revision : 0) !== args.expected_revision)
        return { error: { message: 'DDLR_CONFLICT' } };
      const row = { revision: args.expected_revision + 1, payload: structuredClone(args.new_payload) };
      rows.set(user.id, row); return { data: row };
    }
  };
  const h = harness(['nativenotify.js', 'timetable.js', 'cloud-model.js', 'cloud.js', 'app.js'], {
    storage: options.storage || {}, globals: {
      URL, location: { origin: 'https://localhost', pathname: '/', href: 'https://localhost/' },
      DDL_CLOUD_CONFIG: options.unconfigured ? {} : { url: 'https://testproject.supabase.co', publishableKey: 'sb_publishable_publictest' },
      DdlSupabase: { createClient: (url, key, config) => { sdkOptions = config; return client; } },
      ...(options.globals || {})
    }
  });
  h.el('heroBar').firstElementChild = h.el('heroBar').children[0];
  if (!options.storage) h.run(`S.onboarded=true; S.tasks=[${JSON.stringify(task('guest-task'))}]; S.settings.bgImage='data:image/jpeg;base64,private-photo'; Timetable.ensureState();`);
  await h.run('CloudAccount.init()');
  return { ...h, rows, calls, auth, sdkOptions, setUser: next => { user = next; }, callback: () => callback };
}
const meta = h => h.storage.has('ddlr_cloud_v1') ? JSON.parse(h.storage.get('ddlr_cloud_v1')) : undefined;

test('cloud snapshots exclude photographs, roles, diagnostics, credentials and device reminder settings', async () => {
  const h = await setup();
  const data = snapshot(h.context.S);
  const serialized = JSON.stringify(data);
  assert.doesNotMatch(serialized, /private-photo|bgImage|profile|role|fired|sound|vibrationMode|feedback/);
  assert.equal(data.tasks[0].id, 'guest-task');
  assert.equal(h.calls.some(c => Array.isArray(c) && c[0] === 'write'), false, 'Login alone never uploads local data');
  assert.equal(h.sdkOptions.auth.flowType, 'pkce');
  assert.equal(h.sdkOptions.auth.autoRefreshToken, false);
});
test('enabling sync preserves the guest workspace and signing out restores it', async () => {
  const h = await setup();
  await h.el('cloudUseLocal').click();
  assert.equal(meta(h).active, 'alice');
  assert.equal(h.rows.get('alice').payload.tasks[0].id, 'guest-task');
  h.run(`S.tasks=[${JSON.stringify(task('alice-only'))}]; save()`);
  await h.run('CloudAccount.sync()');
  await h.el('cloudSignOut').click();
  assert.equal(h.context.S.tasks[0].id, 'guest-task');
  assert.equal(meta(h).active, 'guest');
  assert.match(h.storage.get('ddlr_workspace_v1:alice'), /alice-only/);
  assert.match(h.context.S.settings.bgImage, /private-photo/, 'Wallpaper is kept locally through workspace changes');
});
test('a changed cloud revision and local edit pause instead of overwriting either side', async () => {
  const h = await setup(); await h.el('cloudUseLocal').click();
  h.run(`S.tasks.push(${JSON.stringify(task('local-change'))}); save()`);
  const remote = structuredClone(h.rows.get('alice').payload); remote.tasks = [task('remote-change')];
  h.rows.set('alice', { revision: 2, payload: remote });
  await h.run('CloudAccount.sync()');
  assert.equal(h.el('cloudConflict').hidden, false);
  assert.equal(h.context.S.tasks.length, 2);
  assert.equal(h.rows.get('alice').payload.tasks[0].id, 'remote-change');
  await h.el('cloudKeepRemote').click();
  assert.equal(h.context.S.tasks[0].id, 'remote-change');
  assert.match(h.storage.get('ddlr_workspace_v1:before-cloud-alice'), /local-change/);
  assert.equal(h.el('cloudConflict').hidden, true);
});
test('unchanged local data accepts a newer cloud version through native scheduling', async () => {
  let applies = 0;
  const h = await setup(); await h.el('cloudUseLocal').click();
  h.context.NativeNotify.available = () => true;
  h.context.NativeNotify.sync = async state => { applies++; assert.equal(state.tasks[0].id, 'remote'); return true; };
  const remote = structuredClone(h.rows.get('alice').payload); remote.tasks = [task('remote')];
  h.rows.set('alice', { revision: 2, payload: remote });
  await h.run('CloudAccount.sync()');
  assert.equal(h.context.S.tasks[0].id, 'remote'); assert.equal(applies, 1);
});
test('an offline cloud cannot block a local save or its exact native reminder handoff', async () => {
  const h = await setup({ offline: true });
  let scheduled = 0;
  h.context.NativeNotify.available = () => true;
  h.context.NativeNotify.sync = async () => { scheduled++; return true; };
  h.run(`S.tasks.push(${JSON.stringify(task('offline'))})`);
  assert.equal(await h.run('save()'), true);
  assert.equal(scheduled, 1); assert.match(h.storage.get('ddlr_v1'), /offline/);
  await h.el('cloudUseLocal').click();
  assert.equal(h.context.S.tasks.length, 2); assert.equal(meta(h), undefined);
});
test('switching to another account cannot automatically upload the previous account data', async () => {
  const h = await setup(); await h.el('cloudUseLocal').click();
  h.run(`S.tasks=[${JSON.stringify(task('alice-private'))}]; save()`);
  await h.run('CloudAccount.sync()'); await h.el('cloudSignOut').click();
  h.setUser({ id: 'bob', email: 'bob@example.com' });
  h.el('cloudEmail').value = 'bob@example.com'; h.el('cloudPassword').value = 'long-password';
  await h.el('cloudAuthForm').dispatch('submit', { preventDefault() {} });
  await h.el('cloudUseLocal').click();
  assert.equal(h.rows.get('bob').payload.tasks[0].id, 'guest-task');
  assert.doesNotMatch(JSON.stringify(h.rows.get('bob')), /alice-private/);
});
test('background cloud work stops; normal local changes do not create background network requests', async () => {
  const h = await setup(); await h.el('cloudUseLocal').click();
  h.document.hidden = true;
  await h.document.dispatch('visibilitychange');
  const before = h.calls.length;
  h.run(`S.tasks.push(${JSON.stringify(task('background'))}); save()`);
  await h.run('CloudAccount.sync()');
  assert.equal(h.calls.length, before);
  assert.ok(h.calls.includes('refresh:stop'));
});
test('a user edit while cloud activation waits is retained and requires retry', async () => {
  let edit = true;
  const h = await setup({ onRead: async h => {
    if (edit) { edit = false; h.run(`S.tasks.push(${JSON.stringify(task('during-read'))}); save()`); }
  } });
  await h.el('cloudUseLocal').click();
  assert.equal(h.context.S.tasks.length, 2);
  assert.equal(h.rows.size, 0);
  assert.match(h.el('cloudStatus').textContent, /发生变化/);
});
test('email signup, resend and recovery use same-device PKCE redirects and clear passwords', async () => {
  const h = await setup({ user: null });
  await h.document.querySelectorAll('[data-cloud-mode]')[1].click();
  h.el('cloudEmail').value = 'student@example.com'; h.el('cloudPassword').value = 'strong-password';
  await h.el('cloudAuthForm').dispatch('submit', { preventDefault() {} });
  assert.equal(h.calls.find(c => Array.isArray(c) && c[0] === 'signup')[1].options.emailRedirectTo, 'https://localhost/');
  assert.equal(h.el('cloudPassword').value, '');
  await h.el('cloudResend').click();
  assert.ok(h.calls.some(c => Array.isArray(c) && c[0] === 'resend'));
  await h.document.querySelectorAll('[data-cloud-mode]')[2].click();
  await h.el('cloudAuthForm').dispatch('submit', { preventDefault() {} });
  assert.ok(h.calls.some(c => Array.isArray(c) && c[0] === 'reset'));
});
test('recovery callback accepts only the configured path and exposes password reset after exchange', async () => {
  const h = await setup({ recovery: true });
  assert.equal(await h.run("CloudAccount.handleUrl('https://evil.example/?code=stolen')"), false);
  assert.equal(await h.run("CloudAccount.handleUrl('ddlradar://other/callback?code=wrong')"), false);
  assert.equal(await h.run("CloudAccount.handleUrl('ddlradar://auth/callback?code=pkce-code')"), true);
  assert.equal(h.el('cloudRecovery').hidden, false);
  h.el('cloudNewPassword').value = 'new-strong-password';
  await h.el('cloudUpdatePassword').click();
  assert.equal(h.el('cloudNewPassword').value, '');
  assert.equal(h.el('cloudRecovery').hidden, true);
});
test('Android tokens use the encrypted native adapter and never fall back to plaintext', async () => {
  const secure = new Map();
  const h = await setup({ globals: { Capacitor: { isNativePlatform: () => true, Plugins: {
    DdlSecureStore: { get: async ({ key }) => ({ value: secure.get(key) || null }),
      set: async ({ key, value }) => { secure.set(key, value); }, remove: async ({ key }) => { secure.delete(key); } }
  } } } });
  const adapter = h.sdkOptions.auth.storage;
  await adapter.setItem('ddlr-auth', 'private-token');
  assert.equal(await adapter.getItem('ddlr-auth'), 'private-token');
  assert.equal(h.storage.has('ddlr-auth'), false);
  await adapter.removeItem('ddlr-auth'); assert.equal(await adapter.getItem('ddlr-auth'), null);
});
test('unconfigured builds show their real connection state and keep guest data editable', async () => {
  const h = await setup({ unconfigured: true });
  assert.match(h.el('cloudStatus').textContent, /尚未接入/);
  assert.equal(h.el('cloudSubmit').disabled, true);
  h.run(`S.tasks.push(${JSON.stringify(task('guest-offline'))})`); await h.run('save()');
  assert.equal(h.context.S.tasks.length, 2); assert.equal(h.rows.size, 0);
  assert.equal(fingerprint({ b: 2, a: 1 }), fingerprint({ a: 1, b: 2 }));
});

test('paused unsynced account edits survive logout and can resume without uploading guest data', async () => {
  const h = await setup(); await h.el('cloudUseLocal').click();
  await h.el('cloudPauseBtn').click();
  h.run(`S.tasks=[${JSON.stringify(task('unsynced-alice'))}]; save()`);
  await h.el('cloudSignOut').click();
  assert.equal(h.context.S.tasks[0].id, 'guest-task');
  h.el('cloudEmail').value = 'alice@example.com'; h.el('cloudPassword').value = 'long-password';
  await h.el('cloudAuthForm').dispatch('submit', { preventDefault() {} });
  await h.el('cloudResume').click();
  assert.equal(h.context.S.tasks[0].id, 'unsynced-alice');
  assert.equal(h.rows.get('alice').payload.tasks[0].id, 'unsynced-alice');
});
test('an interrupted workspace metadata write cannot enable uploads under a different owner', async () => {
  const state = { onboarded: true, tasks: [task('bob-private')], cloudWorkspace: 'bob',
    profile: { id: 'local', name: 'Bob', role: 'user' }, settings: {} };
  const h = await setup({ storage: { ddlr_v1: JSON.stringify(state), ddlr_cloud_v1: JSON.stringify({ active: 'alice',
    accounts: { alice: { enabled: true, revision: 0, base: '' }, bob: { enabled: true, revision: 0, base: '' } } }) } });
  assert.equal(meta(h).active, 'bob'); assert.equal(meta(h).accounts.bob.enabled, false);
  assert.equal(h.rows.size, 0);
  assert.equal(h.calls.some(c => Array.isArray(c) && c[0] === 'write'), false);
});
test('invalid remote tasks cannot replace local reminders or erase local data', async () => {
  const h = await setup(); await h.el('cloudUseLocal').click();
  const remote = structuredClone(h.rows.get('alice').payload); remote.tasks = [{ id: 'bad', name: 'invalid', dueAt: -1 }];
  h.rows.set('alice', { revision: 2, payload: remote });
  await h.run('CloudAccount.sync()');
  assert.equal(h.context.S.tasks[0].id, 'guest-task');
  assert.match(h.el('cloudStatus').textContent, /本机数据/);
});
test('duplicate callback delivery exchanges a PKCE code once and preserves its flow ID', async () => {
  const h = await setup();
  const value = 'ddlradar://auth/callback?code=unique-code&sb_flow_id=0123456789abcdef';
  await h.run(`CloudAccount.handleUrl(${JSON.stringify(value)})`);
  await h.run(`CloudAccount.handleUrl(${JSON.stringify(value)})`);
  assert.equal(h.calls.filter(c => Array.isArray(c) && c[0] === 'exchange').length, 1);
  assert.equal(h.calls.find(c => Array.isArray(c) && c[0] === 'exchange')[2].flowId, '0123456789abcdef');
});
test('Android appState background event prevents sync even before document.hidden changes', async () => {
  const listeners = {};
  const h = await setup({ globals: { Capacitor: { isNativePlatform: () => false,
    Plugins: { App: { addListener: async (name, fn) => { listeners[name] = fn; } } } } } });
  await h.el('cloudUseLocal').click();
  listeners.appStateChange({ isActive: false });
  assert.equal(h.document.hidden, false);
  const before = h.calls.length;
  h.run(`S.tasks.push(${JSON.stringify(task('native-background'))}); save()`);
  await h.run('CloudAccount.sync()');
  assert.equal(h.calls.length, before);
});

test('an expired-session account switch cannot expose the old account in the new account recovery copy', async () => {
  const aliceState = { onboarded: true, tasks: [task('alice-private')], cloudWorkspace: 'alice',
    profile: { id: 'local', name: 'Alice', role: 'user' }, settings: {} };
  const guestState = { ...aliceState, cloudWorkspace: 'guest', tasks: [task('guest-public')], profile: { ...aliceState.profile, name: 'Guest' } };
  const h = await setup({ user: { id: 'bob', email: 'bob@example.com' }, storage: {
    ddlr_v1: JSON.stringify(aliceState),
    ddlr_cloud_v1: JSON.stringify({ active: 'alice', accounts: { alice: { enabled: false, revision: 0, base: '' } } }),
    'ddlr_workspace_v1:guest': JSON.stringify(guestState)
  } });
  await h.el('cloudUseLocal').click();
  assert.equal(h.context.S.tasks[0].id, 'guest-public');
  assert.doesNotMatch(h.storage.get('ddlr_workspace_v1:before-cloud-bob'), /alice-private/);
  assert.match(h.storage.get('ddlr_workspace_v1:alice'), /alice-private/);
  await h.el('cloudRestoreLocal').click();
  assert.equal(h.context.S.tasks[0].id, 'guest-public');
});
