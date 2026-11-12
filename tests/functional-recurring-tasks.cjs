const assert = require('node:assert/strict');
const test = require('node:test');
const { harness } = require('./functional-dom.cjs');

const time = value => new Date(value).getTime();
const MONDAY = '2026-11-09';
const TUESDAY = '2026-11-10';
const THURSDAY = '2026-11-12';
const FRIDAY = '2026-11-13';

function setup() {
  const plans = [];
  const h = harness(['calendar.js', 'nativenotify.js', 'app.js'], {
    now: time(MONDAY + 'T08:00'),
    globals: {
      Capacitor: {
        isNativePlatform: () => true,
        Plugins: { DdlAlarm: { apply: async ({ alarms }) => { plans.push(alarms); return {}; } } }
      }
    }
  });
  h.el('heroBar').firstElementChild = h.el('heroBar').children[0];
  h.run('S.onboarded = true; init()');
  return {
    ...h,
    plan: () => plans.at(-1),
    mode: value => h.el('fRepeat').querySelector(`button[data-r="${value}"]`).click(),
    weekday: value => h.el('fRepeatWeek').querySelector(`button[data-weekday="${value}"]`).click()
  };
}

test('the weekly form sends one exact recurring alarm per selected day and includes the end date', async () => {
  const h = setup();
  h.run('openEditor(null)');
  h.el('fName').value = '周一和周四晨读';
  await h.mode('weekly');
  assert.equal(h.el('fSingleSchedule').classList.contains('hidden'), true);
  assert.equal(h.el('fRepeatWeekRow').classList.contains('hidden'), false);
  await h.weekday(1);
  await h.run('saveEditor()');
  assert.equal(h.context.S.tasks.length, 0, 'weekly requires at least one selected day');
  await h.weekday(1);
  await h.weekday(4);
  h.el('fRepeatTime').value = '09:15';
  h.el('fRepeatEnd').value = THURSDAY;
  await h.run('saveEditor()');

  const alarms = h.plan();
  assert.equal(alarms.length, 2);
  assert.deepEqual(Array.from(alarms, a => a.at), [time(MONDAY + 'T09:15'), time(THURSDAY + 'T09:15')]);
  assert.ok(alarms.every(a => a.repeatDays === 7 && a.repeatUntil === time(THURSDAY + 'T23:59:59.999')));
  assert.equal(new Set(Array.from(alarms, a => a.id)).size, 2);
  assert.equal(h.run(`tasksForDay('${TUESDAY}').length`), 0);
  assert.equal(h.run(`tasksForDay('${THURSDAY}')[0].at`), time(THURSDAY + 'T09:15'));
  assert.equal(h.run("tasksForDay('2026-11-16').length"), 0);
  assert.equal(h.context.AppBridge.openReminderUrl('ddlr://reminder/' + alarms[1].id), true);
  assert.equal(h.el('fName').value, '周一和周四晨读');
  assert.equal(h.el('fRepeatEnd').value, THURSDAY);

  h.now(time(MONDAY + 'T09:17'));
  await h.run('NativeNotify.sync(S)');
  assert.equal(h.plan()[0].at, alarms[0].at, 'resync leaves a delayed native alarm eligible');
  h.now(time(FRIDAY + 'T08:00'));
  await h.run('NativeNotify.sync(S)');
  assert.equal(h.plan().length, 0);
  assert.equal(h.context.S.tasks.length, 1, 'the series remains editable after its reminders end');
});

test('daily tasks continue without a checkmark and a same-day completion suppresses only that occurrence', async () => {
  const h = setup();
  h.run('openEditor(null)');
  h.el('fName').value = '每日练琴';
  await h.mode('daily');
  h.el('fRepeatTime').value = '09:15';
  await h.run('saveEditor()');
  const first = h.plan()[0];
  assert.equal(first.repeatDays, 1);
  assert.equal(first.repeatUntil, 0);
  assert.equal(first.at, time(MONDAY + 'T09:15'));

  h.now(time(TUESDAY + 'T08:00'));
  await h.run('NativeNotify.sync(S);');
  assert.equal(h.plan()[0].at, time(TUESDAY + 'T09:15'));
  assert.equal(h.plan()[0].id, first.id);
  assert.equal(h.context.S.tasks.length, 1);
  h.run('renderHome()');
  await h.el('repeatList').children[0].querySelector('.t-check').click();
  await h.run('NativeNotify.sync(S)');
  assert.equal(h.plan()[0].at, time('2026-11-11T09:15'));
  assert.equal(h.context.S.tasks.length, 2);
  assert.equal(h.el('doneList').children.length, 1);
  assert.equal(h.run(`tasksForDay('${TUESDAY}').length`), 0);
  assert.equal(h.el('repeatList').children[0].querySelector('.t-check').disabled, true);

  await h.el('doneList').children[0].querySelector('.t-check').click();
  await h.run('NativeNotify.sync(S)');
  assert.equal(h.plan()[0].at, time(TUESDAY + 'T09:15'));
  assert.equal(h.context.S.tasks.length, 1);
});

test('completed records are preserved, hidden at 72 hours and can be expanded', async () => {
  const h = setup();
  h.run(`S.tasks = [
    { id:'recent', name:'刚完成', quadrant:'q1', done:true, doneAt:Date.now()-3*86400000+1, dueAt:Date.now() },
    { id:'older', name:'三天前', quadrant:'q1', done:true, doneAt:Date.now()-3*86400000, dueAt:Date.now() }
  ]; renderHome()`);
  assert.equal(h.el('doneList').children.length, 1);
  assert.equal(h.el('doneHistoryList').classList.contains('hidden'), true);
  assert.match(h.el('doneHistoryBtn').textContent, /1/);
  await h.el('doneHistoryBtn').click();
  assert.equal(h.el('doneHistoryBtn').getAttribute('aria-expanded'), 'true');
  assert.equal(h.el('doneHistoryList').children[0].querySelector('.t-name').textContent, '三天前');
  await h.el('doneHistoryBtn').click();
  h.now(time(MONDAY + 'T08:01'));
  h.run('renderHome()');
  assert.equal(h.el('doneList').children.length, 0);
  assert.equal(h.el('doneHistoryList').classList.contains('hidden'), true);
  assert.equal(h.context.S.tasks.length, 2);
});

test('old interval-based records stay editable and retain the existing completion flow', async () => {
  const h = setup();
  h.run(`S.tasks = normalizeState({tasks: [{
    id:'legacy', name:'旧版打卡', dueAt:new Date('${MONDAY}T09:00').getTime(), repeat:{type:'ndays', n:3}
  }]}).tasks; openEditor('legacy')`);
  assert.equal(h.el('fRepeatLegacy').getAttribute('aria-pressed'), 'true');
  h.el('fName').value = '旧版打卡新名字';
  await h.run('saveEditor()');
  assert.equal(h.context.S.tasks[0].repeat.n, 3);
  await h.el('todayList').children[0].querySelector('.t-check').click();
  assert.equal(h.context.S.tasks[1].dueAt, time(THURSDAY + 'T09:00'));
});
