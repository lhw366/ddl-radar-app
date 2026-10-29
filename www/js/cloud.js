/* Supabase account + foreground-only, revision-checked cloud snapshots.
   This module never owns alarm scheduling and never waits inside local save(). */
(function () {
  'use strict';
  const META = 'ddlr_cloud_v1', VAULT = 'ddlr_workspace_v1:';
  const $ = id => document.getElementById(id);
  const copy = obj => JSON.parse(JSON.stringify(obj));
  let client, user = null, meta, timer, busy = false, syncing = false, applying = false;
  let conflict = null, recovery = false, started = false, epoch = 0, mode = 'login';
  let status = '游客模式 · 数据保存在本机', refreshActive = false, localGeneration = 0;
  let pendingUrl = null;
  const consumedCodes = new Set();
  let appActive = true;
  const foreground = () => !document.hidden && appActive;
  function readMeta() {
    const raw = localStorage.getItem(META);
    if (!raw) return { active: 'guest', accounts: {} };
    const value = JSON.parse(raw);
    if (!value || typeof value.active !== 'string' || !value.accounts || typeof value.accounts !== 'object')
      throw new Error('本机账户记录无法读取，请先导出恢复包');
    return value;
  }
  function persist() { localStorage.setItem(META, JSON.stringify(meta)); }
  function archive(owner, state) {
    // Wallpaper already has its own local storage key and is never uploaded.
    localStorage.setItem(VAULT + owner, JSON.stringify(state, (key, value) => key === 'bgImage' ? undefined : value));
  }
  function stored(owner) {
    const raw = localStorage.getItem(VAULT + owner);
    return raw ? window.normalizeState(JSON.parse(raw)) : null;
  }
  function entry() { return user && meta.accounts[user.id]; }
  function ownsWorkspace() { return !!(user && meta.active === user.id); }
  function enabled() { return !!(ownsWorkspace() && entry() && entry().enabled); }
  function message(text) { status = text; render(); }
  function errorText(error) {
    const code = error && error.code;
    if (code === 'invalid_credentials') return '邮箱或密码不正确';
    if (code === 'email_not_confirmed') return '请先打开验证邮件，完成邮箱验证';
    if (code === 'over_email_send_rate_limit' || code === 'over_request_rate_limit' || error && error.status === 429)
      return '操作过于频繁，请稍后再试；管理员需检查邮件服务额度';
    if (code === 'weak_password') return '密码强度不足，请使用至少 10 位字母、数字和符号';
    if (code === 'otp_expired' || code === 'flow_state_expired' || code === 'flow_state_not_found')
      return '验证链接已失效，请在这台设备重新申请';
    if (code === 'PGRST202' || code === '42P01') return '云端数据库尚未完成初始化，请联系管理员';
    if (error && /DDLR_INVALID|DDLR_TOO/.test(error.message)) return '云端未接受数据，数据过大或格式不兼容；本机内容已保留';
    return '暂时无法连接云端，请检查网络后重试；本机数据和提醒仍可使用';
  }
  function render() {
    if (!$('cloudStatus')) return;
    $('cloudStatus').textContent = status;
    $('cloudIdentity').textContent = user ? user.email || '已登录账户' : '登录后，在不同设备继续安排';
    $('cloudAuthForm').hidden = !!user && !recovery;
    $('cloudAccountActions').hidden = !user || recovery;
    $('cloudRecovery').hidden = !recovery;
    $('cloudChoice').hidden = !user || enabled() || recovery || !!conflict;
    $('cloudConflict').hidden = !conflict;
    $('cloudSyncBtn').hidden = !enabled();
    $('cloudPauseBtn').hidden = !enabled();
    $('cloudResume').hidden = !user || !localStorage.getItem(VAULT + user.id);
    $('cloudUseLocal').textContent = meta && meta.active !== 'guest' && !ownsWorkspace()
      ? '使用游客数据开启同步' : '用本机数据开启同步';
    $('cloudSubmit').textContent = mode === 'signup' ? '注册并发送验证邮件' : mode === 'reset' ? '发送找回密码邮件' : '登录';
    $('cloudPasswordRow').hidden = mode === 'reset';
    $('cloudPassword').autocomplete = mode === 'signup' ? 'new-password' : 'current-password';
    document.querySelectorAll('[data-cloud-mode]').forEach(el => {
      el.classList.toggle('active', el.dataset.cloudMode === mode);
      el.setAttribute('aria-pressed', String(el.dataset.cloudMode === mode));
    });
    document.querySelectorAll('[data-cloud-action]').forEach(el => { el.disabled = busy || !client || syncing; });
  }
  async function action(fn) {
    if (busy || !client) return;
    busy = true; render();
    try { await fn(); } catch (error) { message(errorText(error)); }
    finally {
      busy = false; render();
      if (pendingUrl) { const url = pendingUrl; pendingUrl = null; handleUrl(url).catch(() => {}); }
    }
  }
  function redirect() {
    if (window.Capacitor && Capacitor.isNativePlatform()) return 'ddlradar://auth/callback';
    return window.location.origin + window.location.pathname;
  }
  function credentials(passwordRequired) {
    const email = $('cloudEmail').value.trim();
    const password = $('cloudPassword').value;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { message('请输入有效邮箱'); return null; }
    if (passwordRequired && !password) { message('请输入密码'); return null; }
    if (passwordRequired && mode === 'signup' && password.length < 10) { message('请设置至少 10 位密码'); return null; }
    return { email, password };
  }
  async function submit() {
    const values = credentials(mode !== 'reset');
    if (!values) return;
    const selectedMode = mode;
    try {
      let result;
      if (selectedMode === 'signup') result = await client.auth.signUp({ ...values,
        options: { emailRedirectTo: redirect() } });
      else if (selectedMode === 'reset') result = await client.auth.resetPasswordForEmail(values.email, { redirectTo: redirect() });
      else result = await client.auth.signInWithPassword(values);
      if (result.error) throw result.error;
      if (result.data && result.data.session) acceptSession(result.data.session, 'SIGNED_IN');
      message(selectedMode === 'signup' ? '若该邮箱可注册，将收到验证邮件；请用这台设备打开，验证后登录'
        : selectedMode === 'reset' ? '若该邮箱已注册，将收到找回密码邮件；请用这台设备打开' : '登录成功，请选择如何开启同步');
    } finally { $('cloudPassword').value = ''; }
  }
  function acceptSession(session, event) {
    const next = session && session.user;
    if ((user && user.id) !== (next && next.id)) { epoch++; conflict = null; }
    user = next || null;
    if (event === 'PASSWORD_RECOVERY') { recovery = true; message('验证成功，请设置新密码'); }
    else if (!user) { recovery = false; message('当前未登录；本机数据已保留'); }
    else if (!recovery) message(enabled() ? '已登录 · 前台同步已开启' : '已登录 · 请选择同步方式');
    updateRefresh();
  }
  function onLocalSave() {
    if (!meta || applying) return;
    localGeneration++;
    try {
      archive(meta.active, window.S);
      if (!enabled() || !foreground()) return;
      if (CloudModel.fingerprint(CloudModel.snapshot(window.S)) === entry().base) return;
      clearTimeout(timer);
      timer = setTimeout(() => { sync().catch(() => {}); }, 1800);
    } catch (error) { message('本机账户缓存空间不足，请先导出恢复包；本机事项仍可编辑'); }
  }
  function validRow(row) {
    if (!row) return { revision: 0, payload: null };
    if (!Number.isSafeInteger(row.revision) || row.revision < 0) throw new Error('Unsupported revision');
    if (row.payload) CloudModel.validate(row.payload);
    return row;
  }
  async function readCloud() {
    const result = await client.from('ddlr_cloud_state').select('revision,payload,updated_at').eq('user_id', user.id).maybeSingle();
    if (result.error) throw result.error;
    return validRow(result.data);
  }
  async function writeCloud(payload, revision) {
    const result = await client.rpc('ddlr_save_state', { expected_revision: revision, new_payload: payload });
    if (result.error) throw result.error;
    return validRow(result.data);
  }
  async function applyPayload(payload, owner = meta.active) {
    const next = CloudModel.apply(payload, window.S, window.normalizeState);
    next.cloudWorkspace = owner;
    applying = true;
    try { await window.applyCloudState(next); } finally { applying = false; }
  }
  function record(row, payload) {
    const e = entry();
    e.revision = row.revision;
    e.base = CloudModel.fingerprint(payload);
    e.syncedAt = Date.now();
    persist();
    archive(meta.active, window.S);
  }
  function showConflict(row) {
    conflict = row;
    const cloudCount = row.payload ? row.payload.tasks.length : 0;
    $('cloudConflictSummary').textContent = `本机 ${window.S.tasks.length} 条事项，云端 ${cloudCount} 条事项。请选择要保留的版本；替换前会保存本机副本。`;
    message('另一台设备也有修改，已暂停自动同步，等待你选择');
  }
  async function sync() {
    if (!enabled() || !foreground() || syncing || recovery || conflict) return;
    const currentEpoch = epoch, owner = user.id;
    syncing = true; render();
    try {
      let row = await readCloud();
      if (currentEpoch !== epoch || !user || owner !== user.id || !enabled()) return;
      const local = CloudModel.snapshot(window.S), fp = CloudModel.fingerprint(local), e = entry();
      if (row.revision !== e.revision) {
        const remoteFp = row.payload && CloudModel.fingerprint(row.payload);
        if (fp !== e.base && fp !== remoteFp) { showConflict(row); return; }
        if (row.payload && fp !== remoteFp) {
          // Network waits may race a user edit; recheck before replacing anything.
          if (CloudModel.fingerprint(CloudModel.snapshot(window.S)) !== fp) { showConflict(row); return; }
          archive('before-cloud-' + owner, window.S);
          await applyPayload(row.payload);
        }
        record(row, row.payload || local);
      } else if (fp !== e.base || !row.payload) {
        row = await writeCloud(local, e.revision);
        if (currentEpoch !== epoch || !user || owner !== user.id) return;
        record(row, local);
      }
      message('已同步 · ' + new Date(entry().syncedAt || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
    } catch (error) {
      if (currentEpoch !== epoch) return;
      if (error && error.message === 'DDLR_CONFLICT') {
        try { showConflict(await readCloud()); } catch (readError) { message(errorText(readError)); }
      } else message(errorText(error));
    } finally {
      syncing = false; render();
      // If an edit occurred during a successful write, enqueue that new version.
      if (enabled() && !conflict && foreground() && entry().base !== CloudModel.fingerprint(CloudModel.snapshot(window.S)) && status.startsWith('已同步'))
        onLocalSave();
    }
  }
  async function enable(source) {
    if (!user || syncing || recovery) return;
    const owner = user.id, currentEpoch = epoch, generation = localGeneration;
    const local = meta.active === 'guest' || ownsWorkspace() ? copy(window.S) : stored('guest');
    if (source === 'local' && !local) { message('没有游客数据，请选择使用云端数据'); return; }
    const row = await readCloud();
    if (currentEpoch !== epoch || !user || user.id !== owner) return;
    if (generation !== localGeneration) { message('准备同步期间本机内容发生变化，请重新选择同步方式'); return; }
    const previous = entry();
    const cached = stored(owner);
    if (source === 'cached') {
      if (!cached || !previous) { message('没有可恢复的账户工作区'); return; }
      archive(meta.active, window.S);
      await applyPayload(CloudModel.snapshot(cached), owner);
      meta.active = owner; previous.enabled = true; persist(); archive(owner, window.S);
      message('本机工作区已恢复，正在检查云端版本');
      await sync(); return;
    }
    if (source === 'cloud' && previous && cached && previous.base !== CloudModel.fingerprint(CloudModel.snapshot(cached)) &&
        !confirm('该账户在本机有尚未同步的修改。使用云端版本会替换当前工作区，原内容会保存为本机副本。继续吗？')) return;
    if (source === 'local' && row.payload &&
        !confirm(`云端已有 ${row.payload.tasks.length} 条事项。用本机数据替换它们？云端会保留最近 5 次历史，本机原内容也会备份。`)) return;
    archive(meta.active, window.S);
    if (meta.active === 'guest') archive('guest', window.S);
    archive('before-cloud-' + owner, cached || window.S);
    // Restore a paused account's own unsynced workspace, never somebody else's.
    let chosen = source === 'cloud' ? row.payload : CloudModel.snapshot(local);
    if (!chosen) chosen = CloudModel.snapshot({ ...window.S, tasks: [], timetable: null,
      profile: { name: user.user_metadata && user.user_metadata.display_name || '云端用户' } });
    // Validate before changing ownership or uploading a new snapshot.
    CloudModel.apply(chosen, window.S, window.normalizeState);
    let savedRow = row;
    if (source === 'local') savedRow = await writeCloud(chosen, row.revision);
    if (currentEpoch !== epoch || !user || owner !== user.id) return;
    if (generation !== localGeneration) { message('本机内容有新修改，已保留；请重新选择同步方式'); return; }
    await applyPayload(chosen, owner);
    meta.active = owner;
    meta.accounts[owner] = { enabled: true, revision: savedRow.revision, base: CloudModel.fingerprint(chosen), syncedAt: Date.now() };
    persist(); archive(owner, window.S);
    message('云端同步已开启 · 只同步事项、课表和昵称');
    if (!savedRow.payload) await sync();
  }
  async function resolveConflict(source) {
    if (!conflict || !ownsWorkspace()) return;
    const owner = user.id, currentEpoch = epoch, generation = localGeneration;
    const row = await readCloud();
    if (currentEpoch !== epoch || !user || user.id !== owner) return;
    if (row.revision !== conflict.revision) { showConflict(row); return; }
    if (generation !== localGeneration) { showConflict(row); return; }
    archive('before-cloud-' + owner, window.S);
    let payload = source === 'local' ? CloudModel.snapshot(window.S) : row.payload;
    if (!payload) { message('云端没有可恢复的数据，请选择保留本机'); return; }
    CloudModel.apply(payload, window.S, window.normalizeState);
    const saved = source === 'local' ? await writeCloud(payload, row.revision) : row;
    if (currentEpoch !== epoch) return;
    if (source === 'cloud' && generation !== localGeneration) { showConflict(row); return; }
    if (source === 'cloud') await applyPayload(payload);
    record(saved, payload); conflict = null; message('已同步 · 冲突已处理');
    onLocalSave();
  }
  function pause() {
    if (!enabled()) return;
    entry().enabled = false; epoch++; conflict = null; clearTimeout(timer); persist();
    message('同步已暂停 · 本机编辑继续保留');
  }
  async function signOut() {
    if (syncing) { message('请等待当前同步结束后退出'); return; }
    if (!confirm('退出后恢复游客工作区。当前账户的数据保留在本机及云端；尚未同步的修改也会保留。继续吗？')) return;
    archive(meta.active, window.S);
    const guest = stored('guest');
    const result = await client.auth.signOut({ scope: 'local' });
    if (result.error) throw result.error;
    // Preserve edits made while the sign-out request was in flight, too.
    archive(meta.active, window.S);
    epoch++; user = null; conflict = null; recovery = false;
    if (meta.active !== 'guest') {
      const empty = window.defaultState(); empty.onboarded = true;
      await applyPayload(CloudModel.snapshot(guest || empty), 'guest');
      meta.active = 'guest'; persist(); archive('guest', window.S);
    }
    message('已退出 · 已恢复游客工作区');
  }
  async function restoreLocalCopy() {
    const backup = stored('before-cloud-' + (user ? user.id : meta.active));
    if (!backup) { message('当前账户没有替换前的本机副本'); return; }
    if (!confirm('恢复替换前的本机副本，并暂停云端同步？当前内容会保存在账户工作区。')) return;
    archive(meta.active, window.S); pause();
    await applyPayload(CloudModel.snapshot(backup)); archive(meta.active, window.S);
    message('已恢复本机副本 · 云端同步已暂停');
  }
  async function handleUrl(value) {
    if (!client || typeof value !== 'string') return false;
    let url;
    try { url = new URL(value); } catch (_) { return false; }
    const native = url.protocol === 'ddlradar:' && url.host === 'auth' && url.pathname === '/callback';
    const browser = window.location && url.origin === window.location.origin && url.pathname === window.location.pathname;
    if (!native && !browser) return false;
    const code = url.searchParams.get('code');
    if (!code) return false;
    if (consumedCodes.has(code)) return true;
    if (busy) { pendingUrl = value; return true; }
    await action(async () => {
      const flowId = url.searchParams.get('sb_flow_id');
      const result = await client.auth.exchangeCodeForSession(code, flowId ? { flowId } : undefined);
      if (result.error) throw result.error;
      consumedCodes.add(code);
      if (consumedCodes.size > 10) consumedCodes.delete(consumedCodes.values().next().value);
      acceptSession(result.data.session, result.data.redirectType === 'recovery' ? 'PASSWORD_RECOVERY' : 'SIGNED_IN');
      if (window.switchView) window.switchView('settings');
      if (browser && window.history) history.replaceState({}, '', url.pathname);
    });
    return true;
  }
  function storageAdapter() {
    const native = window.Capacitor && Capacitor.isNativePlatform();
    const secure = native && Capacitor.Plugins.DdlSecureStore;
    if (native && !secure) throw new Error('Secure store unavailable');
    return native ? {
      getItem: async key => (await secure.get({ key })).value,
      setItem: async (key, value) => { await secure.set({ key, value }); },
      removeItem: async key => { await secure.remove({ key }); }
    } : localStorage;
  }
  function updateRefresh() {
    if (!client) return;
    const next = foreground() && !!user;
    if (next === refreshActive) return;
    refreshActive = next;
    const result = next ? client.auth.startAutoRefresh() : client.auth.stopAutoRefresh();
    if (result && result.catch) result.catch(() => {});
  }
  function foregroundChanged(active) {
    if (!active) { clearTimeout(timer); refreshActive = false; client && client.auth.stopAutoRefresh(); return; }
    updateRefresh(); sync().catch(() => {});
  }
  async function init() {
    if (started) return;
    started = true;
    try { meta = readMeta(); } catch (error) { message('本机账户记录异常，请先导出恢复包'); return; }
    // The data and its owner share one local write. Reconcile an interrupted
    // metadata write without ever uploading a different user's workspace.
    const actualOwner = window.S.cloudWorkspace || 'guest';
    if (meta.active !== actualOwner) {
      meta.active = actualOwner;
      if (meta.accounts[actualOwner]) meta.accounts[actualOwner].enabled = false;
      persist();
    }
    document.querySelectorAll('[data-cloud-mode]').forEach(el => el.addEventListener('click', () => {
      mode = el.dataset.cloudMode; $('cloudPassword').value = ''; render();
    }));
    const bind = (id, fn) => $(id).addEventListener('click', () => action(fn));
    $('cloudAuthForm').addEventListener('submit', event => { event.preventDefault(); return action(submit); });
    bind('cloudResend', async () => {
      const values = credentials(false); if (!values) return;
      const result = await client.auth.resend({ type: 'signup', email: values.email, options: { emailRedirectTo: redirect() } });
      if (result.error) throw result.error;
      message('若该邮箱需要验证，将收到新的验证邮件');
    });
    bind('cloudUseLocal', () => enable('local')); bind('cloudUseRemote', () => enable('cloud'));
    bind('cloudResume', () => enable('cached'));
    bind('cloudKeepLocal', () => resolveConflict('local')); bind('cloudKeepRemote', () => resolveConflict('cloud'));
    bind('cloudSyncBtn', sync); bind('cloudPauseBtn', pause); bind('cloudSignOut', signOut);
    bind('cloudRestoreLocal', restoreLocalCopy);
    bind('cloudUpdatePassword', async () => {
      const password = $('cloudNewPassword').value;
      if (!recovery || password.length < 10) { message('请使用有效的找回密码链接，并设置至少 10 位新密码'); return; }
      try {
        const result = await client.auth.updateUser({ password }); if (result.error) throw result.error;
        recovery = false; message('密码已更新 · 可以继续使用账户');
      } finally { $('cloudNewPassword').value = ''; }
    });
    const config = window.DDL_CLOUD_CONFIG || {};
    if (!/^https:\/\/[a-z0-9-]+\.supabase\.co$/.test(config.url || '') ||
        !/^sb_publishable_[A-Za-z0-9_-]+$/.test(config.publishableKey || '')) {
      message('云端账户尚未接入 · 游客模式和本机提醒可正常使用'); return;
    }
    try {
      client = DdlSupabase.createClient(config.url, config.publishableKey, {
        auth: { flowType: 'pkce', detectSessionInUrl: false, autoRefreshToken: false,
          persistSession: true, storageKey: 'ddlr-auth', storage: storageAdapter() },
        global: { fetch: async (input, init = {}) => {
          const controller = new AbortController();
          const abort = () => controller.abort();
          if (init.signal) { if (init.signal.aborted) abort(); else init.signal.addEventListener('abort', abort, { once: true }); }
          const timeout = setTimeout(abort, 15000);
          try { return await fetch(input, { ...init, signal: controller.signal }); }
          finally { clearTimeout(timeout); if (init.signal) init.signal.removeEventListener('abort', abort); }
        } }
      });
      client.auth.onAuthStateChange((event, session) => {
        // Supabase calls subscribers under its auth lock. Never await SDK methods here.
        setTimeout(() => { acceptSession(session, event); if (event === 'SIGNED_IN') sync().catch(() => {}); }, 0);
      });
      const result = await client.auth.getSession();
      if (result.error) throw result.error;
      acceptSession(result.data.session, 'INITIAL_SESSION');
      document.addEventListener('visibilitychange', () => foregroundChanged(foreground()));
      window.addEventListener('online', () => { if (foreground()) sync().catch(() => {}); });
      const app = window.Capacitor && Capacitor.Plugins.App;
      if (app && app.addListener) {
        await app.addListener('appStateChange', event => { appActive = event.isActive; foregroundChanged(foreground()); });
        await app.addListener('appUrlOpen', event => { handleUrl(event.url).catch(() => {}); });
        if (app.getLaunchUrl) { const launch = await app.getLaunchUrl(); if (launch) await handleUrl(launch.url); }
      } else if (window.location) await handleUrl(window.location.href);
      if (!recovery) await sync();
    } catch (error) { message(errorText(error)); }
    render();
  }
  window.CloudAccount = { init, onLocalSave, sync, handleUrl };
})();
