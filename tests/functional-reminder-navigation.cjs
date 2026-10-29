const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

function withTasks(options = {}) {
  const h = harness(['nativenotify.js', 'timetable.js', 'app.js'], options);
  h.el('heroBar').firstElementChild = h.el('heroBar').children[0];
  h.run(`S.tasks = [
    {id:'task-a',name:'对应事项 A',quadrant:'q1',dueAt:Date.now()+3600000,custom:[{v:2,u:'h'}],tiers:{},daily:true},
    {id:'task-b',name:'对应事项 B',quadrant:'q2',dueAt:Date.now()+7200000,custom:[],tiers:{},daily:false}
  ];S.onboarded=true`);
  return h;
}

test('deadline, daily, all normal gradients and custom reminder hashes open their exact existing task', () => {
  const h = withTasks();
  const before = h.run('JSON.stringify(S)');
  for (const key of ['due', 'daily', 't120', 't60', 't30', 't5', 'c2h']) {
    assert.equal(h.run(`AppBridge.openReminderUrl('ddlr://reminder/'+NativeNotify.idOf('task-a|${key}'))`), true);
    assert.equal(h.el('fName').value, '对应事项 A');
    assert.equal(h.el('editorWrap').classList.contains('hidden'), false);
  }
  h.run(`AppBridge.openReminderUrl('ddlr://reminder/'+NativeNotify.idOf('task-b|due'))`);
  assert.equal(h.el('fName').value, '对应事项 B');
  assert.equal(h.run('JSON.stringify(S)'), before, 'Opening a reminder does not save or mark the task done');
});

test('course reminder hashes open the corresponding existing course on the timetable', async () => {
  const h = withTasks();
  await h.bind();
  h.run(`S.timetable.courses=[
    {id:'math',name:'高等数学',room:'理教408',day:2,start:1,end:2,week:'all'},
    {id:'english',name:'英语',room:'二教313',day:3,start:3,end:4,week:'all'}
  ];AppBridge.openReminderUrl('ddlr://reminder/'+NativeNotify.idOf('tt:english:recurring'))`);
  assert.equal(h.run('curView'), 'timetable');
  assert.equal(h.el('ttEditor').classList.contains('hidden'), false);
  assert.equal(h.el('ttEdName').value, '英语');
  assert.equal(h.el('ttEdRoom').value, '二教313');
});

test('unknown or test reminder hashes safely open home while malformed URLs are ignored', () => {
  const h = withTasks();
  const before = h.run('JSON.stringify(S)');
  h.run('openEditor("task-a");openReminderUrl("ddlr://reminder/990000001")');
  assert.equal(h.run('curView'), 'home');
  assert.equal(h.el('editorWrap').classList.contains('hidden'), true);
  h.run('switchView("matrix")');
  for (const url of ['javascript:alert(1)', 'https://example.com/reminder/3', 'ddlr://reminder/3?task=task-a',
    'ddlr://reminder/-1', 'ddlr://reminder/2147483648', 'ddlr://reminder/0']) {
    h.context.__testUrl = url;
    assert.equal(h.run('openReminderUrl(__testUrl)'), false);
  }
  assert.equal(h.run('curView'), 'matrix');
  assert.equal(h.run('JSON.stringify(S)'), before);
});

test('cold launch URL and warm appUrlOpen events both dispatch once through the registered App bridge', async () => {
  let warm, registrations = 0;
  const h = withTasks({ globals: { Capacitor: { Plugins: { App: {
    addListener: async (name, listener) => { assert.equal(name, 'appUrlOpen'); warm = listener; registrations++; },
    getLaunchUrl: async () => ({ url: 'ddlr://reminder/' + h.context.NativeNotify.idOf('task-a|due') })
  } } } } });
  await h.run('bindNativeReminderNavigation()');
  assert.equal(h.el('fName').value, '对应事项 A');
  warm({ url: 'ddlr://reminder/' + h.context.NativeNotify.idOf('task-b|t5') });
  assert.equal(h.el('fName').value, '对应事项 B');
  await h.run('bindNativeReminderNavigation()');
  assert.equal(registrations, 1);
});
