const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

test('foreground automatic check shows a persistent update choice and one system notice without installing', async () => {
  let installs=0, notices=0, requests=0;
  const h=harness(['app.js'], {fetch:async url=>{
    requests++;
    return {ok:true,json:async()=>String(url).includes('releases/latest') ? {} :
      {code:100,version:'2.3.1',apk:'https://example.com/100.apk'}};
  },globals:{Capacitor:{Plugins:{
    App:{getInfo:async()=>({build:'99'})},
    ApkInstaller:{addListener:async()=>({}),getUpdateState:async()=>({status:'idle'}), installApk:async()=>{installs++}},
    DdlNotify:{notifyUpdate:async()=>{notices++}}
  }}}});
  h.run('bindUpdater()');
  await new Promise(r=>setImmediate(r));
  assert.equal(h.el('updateBtn').dataset.code,'100');
  assert.ok(h.el('updateNotice'));
  assert.equal(installs,0);
  assert.equal(notices,1);
  await h.document.dispatch('visibilitychange');
  await new Promise(r=>setImmediate(r));
  assert.equal(notices,1);
  assert.equal(requests,6,'No foreground resume network polling within the interval');
  await h.el('updateLater').click();
  assert.equal(h.el('updateNotice'),null);
});

test('automatic update checks stay quiet in background or when installed version is unknown', async()=>{
  let requests=0;
  const h=harness(['app.js'],{fetch:async()=>{requests++;return {ok:false}},globals:{Capacitor:{Plugins:{
    ApkInstaller:{addListener:async()=>({}),getUpdateState:async()=>({status:'idle'})}
  }}}});
  h.document.hidden=true;
  h.run('bindUpdater()');
  await new Promise(r=>setImmediate(r));
  assert.equal(requests,0);
  h.document.hidden=false;
  await h.document.dispatch('visibilitychange');
  await new Promise(r=>setImmediate(r));
  assert.equal(requests,1,'Only the local version lookup is allowed without a known build');
  assert.equal(h.el('updateNotice'),null);
});

function installHarness(plugin) {
  const detachedTimeout = (fn, ms, ...args) => {
    const timer = setTimeout(fn, ms, ...args);
    if (timer && timer.unref) timer.unref();
    return timer;
  };
  return harness(['app.js'], {
    globals: {
      Capacitor: { Plugins: { ApkInstaller: plugin } },
      setTimeout: detachedTimeout,
      clearTimeout
    }
  });
}

test('rapid update clicks start exactly one native install request', async () => {
  let calls = 0;
  let finish;
  const plugin = {
    addListener: async () => ({ remove() {} }),
    getUpdateState: async () => ({ status: 'idle', busy: false, code: 0, urls: [] }),
    installApk: () => {
      calls++;
      return new Promise((resolve) => { finish = resolve; });
    }
  };
  const h = installHarness(plugin);
  const button = h.el('updateBtn');
  button.dataset.url = 'https://example.com/ddl-radar-48.apk';
  button.dataset.code = '48';
  h.run('bindUpdater()');

  const first = button.dispatch('click');
  const second = button.dispatch('click');
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(calls, 1);

  finish({ status: 'ready', busy: false, code: 48, urls: [button.dataset.url] });
  await Promise.all([first, second]);
  assert.equal(calls, 1);
});

test('a native download already in progress blocks another click', async () => {
  let calls = 0;
  const plugin = {
    addListener: async () => ({ remove() {} }),
    getUpdateState: async () => ({
      status: 'downloading',
      busy: true,
      code: 48,
      bytes: 10,
      total: 100,
      urls: ['https://example.com/ddl-radar-48.apk']
    }),
    installApk: async () => { calls++; }
  };
  const h = installHarness(plugin);
  const button = h.el('updateBtn');
  h.run('bindUpdater()');

  await button.dispatch('click');
  assert.equal(calls, 0);
  assert.equal(button.disabled, true);
  assert.match(button.textContent, /下载中/);
});

test('an opened installer keeps the downloaded target for reuse', async () => {
  const plugin = {
    addListener: async () => ({ remove() {} }),
    getUpdateState: async () => ({
      status: 'installerOpened',
      busy: false,
      code: 48,
      urls: ['https://example.com/ddl-radar-48.apk']
    }),
    installApk: async () => ({})
  };
  const h = installHarness(plugin);
  const button = h.el('updateBtn');
  h.run('bindUpdater()');

  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(button.dataset.code, '48');
  assert.equal(button.dataset.url, 'https://example.com/ddl-radar-48.apk');
  assert.match(button.textContent, /继续安装/);
});
