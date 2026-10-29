const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

function nativeHarness(exportJson) {
  return harness(['nativenotify.js', 'app.js'], {
    globals: { Capacitor: { Plugins: { DdlBackup: { exportJson } } } }
  });
}

test('only an explicit backup click opens native export and its JSON restores the profile, tasks, timetable and wallpaper', async () => {
  const calls = [];
  const h = nativeHarness(async payload => {
    calls.push(payload);
    return { saved: true, filename: '恢复包.json', bytes: 100 };
  });
  h.run(`S.profile.name = '中文昵称'; S.settings.bgImage = 'data:image/jpeg;base64,local-wallpaper';
    S.tasks = [{ id:'task-1', name:'用户任务', dueAt:Date.now()+3600000, custom:[], tiers:{5:true} }];
    S.timetable = { courses:[{id:'course-1',name:'高等数学',day:2,start:1,end:2,week:'all'}] };
    bindSettings()`);
  assert.equal(calls.length, 0, 'Binding and rendering settings does not export anything');
  await h.el('saveRecoveryBtn').click();
  assert.equal(calls.length, 1);
  assert.match(calls[0].filename, /^ddlradar-backup-\d{4}-\d{2}-\d{2}\.json$/);
  const snapshot = JSON.parse(calls[0].json);
  const restored = h.run(`normalizeState(${JSON.stringify(snapshot)})`);
  assert.equal(restored.profile.id, snapshot.profile.id);
  assert.equal(restored.profile.name, '中文昵称');
  assert.equal(restored.tasks[0].name, '用户任务');
  assert.equal(restored.timetable.courses[0].name, '高等数学');
  assert.equal(restored.settings.bgImage, 'data:image/jpeg;base64,local-wallpaper');
  assert.equal(h.el('exportBtn').disabled, false);
  assert.match(h.el('toastWrap').children.at(-1).children[0].textContent, /已保存/);
});

test('cancelling the system save dialog does not report success, mutate data or fall back to Blob download', async () => {
  const h = nativeHarness(async () => ({ saved: false, cancelled: true }));
  const before = h.run('JSON.stringify(S)');
  assert.equal(await h.run('exportBackup()'), false);
  assert.equal(h.run('JSON.stringify(S)'), before);
  assert.equal(h.el('saveRecoveryBtn').disabled, false);
  const title = h.el('toastWrap').children.at(-1).children[0].textContent;
  assert.match(title, /取消/);
  assert.doesNotMatch(title, /成功|已保存|下载/);
});

test('write failures and unconfirmed results release backup controls for retry without reporting saved', async () => {
  let count = 0;
  const h = nativeHarness(async () => {
    if (++count === 1) throw new Error('Provider refused write');
    if (count === 2) return {};
    return { saved: true, filename: 'retry.json' };
  });
  assert.equal(await h.run('exportBackup()'), false);
  assert.equal(h.el('exportBtn').disabled, false);
  assert.match(h.el('toastWrap').children.at(-1).children[0].textContent, /未保存/);
  assert.equal(await h.run('exportBackup()'), false);
  assert.equal(await h.run('exportBackup()'), true);
  assert.equal(count, 3);
});

test('repeated backup clicks cannot open competing system save dialogs', async () => {
  let complete, count = 0;
  const h = nativeHarness(() => {
    count++;
    return new Promise(resolve => { complete = resolve; });
  });
  const pending = h.run('exportBackup()');
  assert.equal(h.el('exportBtn').disabled, true);
  assert.equal(await h.run('exportBackup()'), false);
  assert.equal(count, 1);
  complete({ saved: false, cancelled: true });
  await pending;
  assert.equal(h.el('exportBtn').disabled, false);
});

test('the browser keeps a JSON download and describes it as started rather than a confirmed file save', async () => {
  let downloaded;
  const h = harness(['nativenotify.js', 'app.js'], {
    globals: { Blob, URL: { createObjectURL: blob => { downloaded = blob; return 'blob:local-backup'; }, revokeObjectURL() {} } }
  });
  assert.equal(await h.run('exportBackup()'), true);
  assert.equal(downloaded.type, 'application/json');
  assert.equal(JSON.parse(await downloaded.text()).profile.id, h.context.S.profile.id);
  assert.match(h.el('toastWrap').children.at(-1).children[0].textContent, /发起.*下载/);
});
