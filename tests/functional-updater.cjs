const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

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
