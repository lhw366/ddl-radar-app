const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');
const reference = require('../www/data/timetable-reference.json');

test('one-click reference import adds all 13 verified placements once and preserves lesson times', async()=>{
  const h=setup([], {fetch:async()=>({ok:true,json:async()=>JSON.parse(JSON.stringify(reference))})});
  h.context.S.profile={role:'admin',name:'管理员',id:'usr-admin'};
  h.context.S.timetable.slots[0]='08:10-09:00';
  await h.bind();
  await h.el('ttReferenceBtn').click();
  assert.equal(h.context.S.timetable.courses.length,13);
  assert.equal(h.context.S.timetable.slots[0],'08:10-09:00');
  assert.equal(h.context.S.timetable.courses.find(c=>c.day===3 && c.start===7).end,9);
  assert.equal(h.context.S.timetable.courses.find(c=>c.day===5 && c.start===5).room,'计算中心二层7-10号机房');
  await h.el('ttReferenceBtn').click();
  assert.equal(h.context.S.timetable.courses.length,13);
});

test('reference timetable is unavailable to normal users and cannot overlap an existing timetable', async()=>{
  let fetches=0;
  const h=setup([course()], {fetch:async()=>{fetches++;return {ok:true,json:async()=>reference};}});
  h.context.S.profile={role:'user',name:'用户',id:'usr-user'};
  await h.bind();
  await h.el('ttReferenceBtn').click();
  assert.equal(fetches,0);
  assert.equal(h.context.S.timetable.courses.length,1);

  h.context.S.profile.role='admin';
  await h.el('ttReferenceBtn').click();
  assert.equal(fetches,0,'Administrator reference test also refuses to overwrite existing courses');
  assert.equal(h.context.S.timetable.courses.length,1);
});

test('fresh timetable remains empty after startup and applying either school preset', async () => {
  const h = setup([]);
  await h.bind();
  assert.equal(h.context.S.timetable.courses.length, 0);
  assert.equal(h.context.S.timetable.schoolPreset, 'generic');
  for (const preset of ['pku', 'generic']) {
    h.el('ttSchoolPreset').value = preset;
    await h.el('ttApplyPreset').click();
    assert.equal(h.context.S.timetable.courses.length, 0);
  }
});

test('overlap detection respects weekday, period range and odd/even weeks',()=>{
  const h=setup([]), overlap=h.context.Timetable._coursesOverlap;
  assert.equal(overlap({day:1,start:1,end:2,week:'all'},{day:1,start:2,end:3,week:'odd'}),true);
  assert.equal(overlap({day:1,start:1,end:2,week:'odd'},{day:1,start:1,end:2,week:'even'}),false);
  assert.equal(overlap({day:1,start:1,end:2,week:'odd'},{day:1,start:1,end:2,week:'odd'}),true);
  assert.equal(overlap({day:1,start:1,end:2,week:'all'},{day:2,start:1,end:2,week:'all'}),false);
});

test('school presets change lesson times only and custom schedules support four to twenty periods',async()=>{
  const h=setup([course()]);
  await h.bind();
  h.el('ttSchoolPreset').value='pku';
  await h.el('ttApplyPreset').click();
  assert.equal(h.context.S.timetable.slots[0],'08:00-08:50');
  assert.equal(h.context.S.timetable.courses.length,1);
  assert.equal(h.context.S.timetable.courses[0].name,'数学');

  h.el('ttSlotsText').value='08:10-08:55\n09:05-09:50\n10:00-10:45\n10:55-11:40';
  await h.el('ttSlotsSave').click();
  assert.equal(h.context.S.timetable.slots.length,4);
  assert.equal(h.context.S.timetable.schoolPreset,'custom');
  assert.equal(h.context.S.timetable.courses.length,1);
});

function course(overrides = {}) {
  return { id: 'math', name: '数学', room: '一教101', day: 4, start: 1, end: 2, week: 'all', remindMin: 15, ...overrides };
}
function setup(courses = [], opts = {}) {
  const h = harness(['timetable.js'], opts);
  const tt = h.context.Timetable.ensureState();
  tt.courses = courses;
  return h;
}

test('course reminder remains available at its deadline and throughout the catch-up window', () => {
  const h = setup([course()]);
  const at = new Date(2026, 9, 22, 7, 45).getTime();
  h.now(at);
  assert.equal(h.context.Timetable.planFor(at).length, 1);
  h.now(at + 2 * 60000);
  assert.equal(h.context.Timetable.planFor(at).length, 1);
  h.now(at + 15 * 60000 + 1);
  assert.equal(h.context.Timetable.planFor(at).length, 0);
});

test('odd and even course weeks follow the local term date and do not run before term begins', () => {
  const h = setup([course({ id: 'odd', week: 'odd' }), course({ id: 'even', week: 'even' })]);
  h.context.S.timetable.termStart = '2026-10-22';
  const week1 = new Date(2026, 9, 22, 7).getTime();
  h.now(week1);
  assert.match(h.context.Timetable.planFor(week1)[0].key, /tt:odd:/);
  const week2 = new Date(2026, 9, 29, 7).getTime();
  assert.match(h.context.Timetable.planFor(week2)[0].key, /tt:even:/);
  assert.equal(h.context.Timetable.planFor(new Date(2026, 9, 15, 7).getTime()).length, 0);
});

test('zero-minute course reminder is honored and malformed courses cannot break the rest', () => {
  const at = new Date(2026, 9, 22, 8).getTime();
  const h = setup([null, course({ id: 'bad', start: 1.5 }), course({ remindMin: 0 })], { now: at });
  const reminders = h.context.Timetable.planFor(at);
  assert.equal(reminders.length, 1);
  assert.equal(reminders[0].at, at);
  assert.equal(reminders[0].title, '📚 上课时间到');
});

test('spanning course cells keep explicit weekday and lesson coordinates', () => {
  const h = setup([course({ day: 1 }), course({ id: 'physics', day: 2, start: 3, end: 4 })]);
  h.context.Timetable.render();
  const html = h.el('ttGrid').innerHTML;
  assert.match(html, /grid-row:2 \/ span 2;grid-column:2/);
  assert.match(html, /grid-row:4 \/ span 2;grid-column:3/);
  assert.match(html, /class="tt-rowhead" style="grid-row:13;grid-column:1"/);
  assert.doesNotMatch(html, /grid-row:span/);
});

test('course edit rounds lesson numbers and preserves its existing reminder offset', async () => {
  const h = setup([course({ remindMin: 0 })]);
  await h.bind();
  await h.el('ttGrid').querySelector('.tt-course-item').click();
  h.el('ttEdName').value = '数学修改';
  h.el('ttEdStart').value = '3.5';
  h.el('ttEdEnd').value = '4.5';
  await h.el('ttEdSave').click();
  assert.equal(h.context.S.timetable.courses[0].start, 3);
  assert.equal(h.context.S.timetable.courses[0].end, 4);
  assert.equal(h.saved.at(-1).timetable.courses[0].name, '数学修改');
  assert.equal(h.saved.at(-1).timetable.courses[0].remindMin, 0);
  await h.el('ttGrid').querySelector('.tt-course-item').click();
  await h.el('ttEdDel').click();
  assert.equal(h.saved.at(-1).timetable.courses.length, 0);
});

function ocrSetup(recognize) {
  const h = setup([], {
    globals: { Capacitor: { Plugins: { DdlOcr: { recognize } } } }
  });
  h.context.navigator.clipboard = {
    read: async () => [{ types: ['image/png'], getType: async () => ({}) }]
  };
  h.context.FileReader = class { readAsDataURL() { this.result = 'data:image/png;base64,a'; this.onload(); } };
  return h;
}
const ocrBlocks = () => ({ blocks: [
  { text: '高等数学', lines: [{ text: '高等数学' }] },
  { text: '英语课', lines: [{ text: '英语课' }] },
  ...Array.from({ length: 8 }, (_, i) => ({
    text: `第${i + 1}节 ${i + 9}:30-${i + 10}:20`,
    lines: [{ text: `第${i + 1}节 ${i + 9}:30-${i + 10}:20` }]
  }))
] });

test('course reminders preserve their real date when the advance notice falls before midnight', () => {
  const h = setup([course({ day: 5 })]);
  h.context.S.timetable.slots[0] = '00:05-00:55';
  const reminderAt = new Date(2026, 9, 22, 23, 50).getTime();
  h.now(reminderAt + 60000);
  const reminders = h.context.Timetable.planFor(new Date(2026, 9, 23, 0).getTime());
  assert.equal(reminders.length, 1);
  assert.equal(reminders[0].at, reminderAt);
});

test('OCR preview and cancel leave saved courses and lesson times unchanged', async () => {
  const h = ocrSetup(async () => ocrBlocks());
  await h.bind();
  const before = JSON.stringify(h.context.S.timetable);
  await h.el('ttPasteBtn').click();
  assert.equal(h.el('ttReviewList').querySelectorAll('.tt-rv-row').length, 2);
  assert.equal(JSON.stringify(h.context.S.timetable), before);
  assert.equal(h.saved.length, 0);
  await h.el('ttReviewCancel').click();
  assert.equal(JSON.stringify(h.context.S.timetable), before);
});

test('OCR deleting another row preserves edits and confirmation commits its times once', async () => {
  const h = ocrSetup(async () => ocrBlocks());
  await h.bind();
  await h.el('ttPasteBtn').click();
  const rows = h.el('ttReviewList').querySelectorAll('.tt-rv-row');
  rows[1].querySelector('.tt-rv-name').value = '已修正的英语课';
  rows[1].querySelector('.tt-rv-day').value = '5';
  await rows[0].querySelector('[data-del]').click();
  assert.equal(h.el('ttReviewList').querySelector('.tt-rv-name').value, '已修正的英语课');
  await h.el('ttConfirmBtn').click();
  assert.equal(h.saved.at(-1).timetable.courses.length, 1);
  assert.equal(h.saved.at(-1).timetable.courses[0].name, '已修正的英语课');
  assert.equal(h.saved.at(-1).timetable.courses[0].day, 5);
  assert.equal(h.saved.at(-1).timetable.slots[0], '09:30-10:20');
  await h.el('ttConfirmBtn').click();
  assert.equal(h.context.S.timetable.courses.length, 1);
});

test('cancelled OCR request cannot reopen the preview when recognition finishes', async () => {
  let finish;
  const h = ocrSetup(() => new Promise((resolve) => { finish = resolve; }));
  await h.bind();
  const work = h.el('ttPasteBtn').click();
  for (let i = 0; i < 8 && !finish; i++) await Promise.resolve();
  assert.equal(typeof finish, 'function');
  await h.el('ttOcrCancel').click();
  finish(ocrBlocks());
  await work;
  assert.equal(h.el('ttReview').classList.contains('hidden'), true);
  assert.equal(h.el('ttOcrStatus').textContent, '');
  assert.equal(h.saved.length, 0);
});

test('OCR recognizes plain blocks without line arrays and importing the same result is idempotent', async () => {
  const h = ocrSetup(async () => ({ blocks: [{ text: '高等数学' }] }));
  await h.bind();
  for (let i = 0; i < 2; i++) {
    await h.el('ttPasteBtn').click();
    await h.el('ttConfirmBtn').click();
  }
  assert.equal(h.context.S.timetable.courses.length, 1);
  assert.match(h.toasts.at(-1)[1], /重复 1 条/);
});

test('import into an occupied timetable skips overlapping courses and preserves its lesson times', async () => {
  const h = ocrSetup(async () => ocrBlocks());
  h.context.S.timetable.courses = [course({ day: 1 })];
  h.context.S.timetable.slots[0] = '08:10-09:00';
  await h.bind();
  await h.el('ttPasteBtn').click();
  await h.el('ttConfirmBtn').click();
  assert.equal(h.context.S.timetable.courses.length, 1);
  assert.equal(h.context.S.timetable.courses[0].id, 'math');
  assert.equal(h.context.S.timetable.slots[0], '08:10-09:00');
  assert.match(h.toasts.at(-1)[1], /冲突 2 条已跳过/);
});

test('import accepts alternating-week courses once and rejects an every-week collision in the same batch', async () => {
  const h = ocrSetup(async () => ({ blocks: [
    { text: '高等数学 单周', lines: [{ text: '高等数学' }, { text: '单周' }] },
    { text: '大学英语 双周', lines: [{ text: '大学英语' }, { text: '双周' }] },
    { text: '线性代数 每周', lines: [{ text: '线性代数' }, { text: '每周' }] }
  ] }));
  await h.bind();
  for (let i = 0; i < 2; i++) {
    await h.el('ttPasteBtn').click();
    await h.el('ttConfirmBtn').click();
  }
  assert.equal(h.context.S.timetable.courses.length, 2);
  assert.deepEqual(Array.from(h.context.S.timetable.courses, c => c.week), ['odd', 'even']);
  assert.match(h.toasts.at(-1)[1], /重复 2 条/);
  assert.match(h.toasts.at(-1)[1], /冲突 1 条已跳过/);
});

test('replacement requires its explicit import option and replaces existing courses and lesson times', async () => {
  const h = ocrSetup(async () => ocrBlocks());
  h.context.S.timetable.courses = [course()];
  await h.bind();
  await h.el('ttPasteBtn').click();
  for (const input of h.el('ttImportMode').querySelectorAll('input')) input.checked = input.value === 'replace';
  await h.el('ttConfirmBtn').click();
  assert.equal(h.context.S.timetable.courses.length, 1);
  assert.equal(h.context.S.timetable.courses[0].name, '高等数学');
  assert.equal(h.context.S.timetable.slots[0], '09:30-10:20');
  assert.match(h.toasts.at(-1)[1], /已替换当前课表/);
});

test('manual course entry cannot create an overlapping class but permits alternate weeks', async () => {
  const h = setup([course({ day: 1, week: 'odd' })]);
  await h.bind();
  await h.el('ttAddBtn').click();
  h.el('ttEdName').value = '大学英语';
  h.el('ttEdDay').value = '1';
  h.el('ttEdWeek').value = 'all';
  await h.el('ttEdSave').click();
  assert.equal(h.context.S.timetable.courses.length, 1);
  assert.equal(h.saved.length, 0);
  assert.equal(h.el('ttEditor').classList.contains('hidden'), false);
  h.el('ttEdWeek').value = 'even';
  await h.el('ttEdSave').click();
  assert.equal(h.context.S.timetable.courses.length, 2);
});

test('alternating-week courses with different start rows render in distinct lanes', () => {
  const h = setup([
    course({ id: 'odd', day: 1, start: 1, end: 2, week: 'odd' }),
    course({ id: 'even', day: 1, start: 2, end: 3, week: 'even' })
  ]);
  h.context.Timetable.render();
  const cells = h.el('ttGrid').querySelectorAll('.has-course');
  assert.equal(cells.length, 1, 'One grid container owns the complete shared range');
  assert.match(cells[0].getAttribute('style'), /grid-row:2 \/ span 3/);
  assert.match(cells[0].getAttribute('style'), /grid-template-columns:repeat\(2,/);
  const items = cells[0].querySelectorAll('.tt-course-item');
  assert.match(items[0].getAttribute('style'), /grid-column:1;grid-row:1 \/ span 2/);
  assert.match(items[1].getAttribute('style'), /grid-column:2;grid-row:2 \/ span 2/);
});

test('OCR review uses the imported twenty-period schedule before committing it', async () => {
  const h = ocrSetup(async () => ({ blocks: [
    { text: '高等数学' },
    ...Array.from({ length: 20 }, (_, i) => ({ text: `${i + 1} ${String(i).padStart(2, '0')}:00-${String(i).padStart(2, '0')}:30` }))
  ] }));
  await h.bind();
  await h.el('ttPasteBtn').click();
  const row = h.el('ttReviewList').querySelector('.tt-rv-row');
  assert.equal(row.querySelector('.tt-rv-start').max, '20');
  row.querySelector('.tt-rv-start').value = '18';
  row.querySelector('.tt-rv-end').value = '20';
  await h.el('ttConfirmBtn').click();
  assert.equal(h.context.S.timetable.slots.length, 20);
  assert.equal(h.context.S.timetable.courses[0].start, 18);
  assert.equal(h.context.S.timetable.courses[0].end, 20);
});

test('render refreshes the term control when imported state replaces the timetable', async () => {
  const h = setup([]);
  await h.bind();
  h.context.S.timetable.termStart = '2026-09-01';
  h.context.Timetable.render();
  assert.equal(h.el('ttTermStart').value, '2026-09-01');
  assert.match(h.el('ttTermHint').textContent, /本周第/);
});

function pkuBlock(text, cx, top, bottom) {
  const line = { text, cx, cy: (top + bottom) / 2, top, bottom };
  return { text, cx, cy: line.cy, top, bottom, lines: [line] };
}

test('missing screenshot lesson times stay unknown and cannot schedule a template-time reminder', () => {
  const h = setup([course()]);
  h.context.S.timetable.slots[0] = null;
  const at = new Date(2026, 9, 22, 7, 45).getTime();
  h.now(at);
  assert.equal(h.context.Timetable.ensureState().slots[0], null);
  assert.equal(h.context.Timetable.planFor(at).length, 0, 'No invented 08:00 alarm for an unknown start time');
  const parsed = h.context.Timetable._parseBlocks([
    { text: '高等数学' }, { text: '1 08:00-08:45' }, { text: '2 09:00-09:45' },
    { text: '4 11:00-11:45' }, { text: '5 14:00-14:45' }
  ]);
  assert.equal(parsed.slots.length, 5);
  assert.equal(parsed.slots[2], null, 'A missing middle period keeps its index and does not inherit another school time');
});

test('PKU screenshot layout infers periods, keeps second periods, and prefers the listed classroom', () => {
  const h = setup([]);
  const headers = [324, 914, 1506, 2097, 2610].map((x, i) =>
    pkuBlock(`星期${['一', '二', '三', '四', '五'][i]}`, x, 13, 37));
  const blocks = headers.concat([
    pkuBlock('线性代数A（I）', 360, 382, 404),
    pkuBlock('（理教408）', 360, 411, 433),
    pkuBlock('（备注：上课时间：单周周一10-11节，上课教室：三教508、理教419）每周', 360, 439, 491),
    pkuBlock('线性代数A（I）', 360, 532, 554),
    pkuBlock('（理教408）', 360, 561, 583),
    pkuBlock('（备注：上课时间：单周周一10-11节，上课教室：三教508、理教419）每周', 360, 589, 641),

    pkuBlock('高等数学A（一）', 944, 82, 105),
    pkuBlock('（二教309）', 944, 112, 134),
    pkuBlock('（备注：习题课每周）每周', 944, 140, 192),
    pkuBlock('高等数学A（一）', 944, 232, 255),
    pkuBlock('（二教309）', 944, 262, 284),
    pkuBlock('（备注：习题课每周）每周', 944, 290, 342),

    pkuBlock('线性代数A（I）', 1534, 83, 105),
    pkuBlock('（理教408）', 1534, 112, 134),
    pkuBlock('（备注：上课时间：单周周一10-11节）每周', 1534, 140, 192),
    pkuBlock('线性代数A（I）', 1534, 233, 255),
    pkuBlock('（理教408）', 1534, 262, 284),
    pkuBlock('（备注：上课时间：单周周一10-11节）每周', 1534, 290, 342),

    pkuBlock('太极拳', 2126, 98, 119),
    pkuBlock('（暂无上课教室数据）', 2126, 126, 149),
    pkuBlock('（备注：男生班）每周', 2126, 155, 178),
    pkuBlock('太极拳', 2126, 247, 268),
    pkuBlock('（暂无上课教室数据）', 2126, 275, 298),
    pkuBlock('（备注：男生班）每周', 2126, 304, 327),

    pkuBlock('计算概论A', 2640, 381, 403),
    pkuBlock('（理教403）', 2640, 411, 433),
    pkuBlock('（备注：同时选计算概论A上机实习课）每周', 2640, 439, 491),
    pkuBlock('计算概论A', 2640, 531, 553),
    pkuBlock('（理教403）', 2640, 561, 583),
    pkuBlock('（备注：同时选计算概论A上机实习课）每周', 2640, 589, 641),

    pkuBlock('计算概论A上机实习课', 2640, 600, 622),
    pkuBlock('（暂无上课教室数据）', 2640, 629, 652),
    pkuBlock('（备注：计算中心二层7-10号机房）每周', 2640, 658, 709),
    pkuBlock('计算概论A上机实习课', 2640, 732, 754),
    pkuBlock('（暂无上课教室数据）', 2640, 761, 784),
    pkuBlock('（备注：计算中心二层7-10号机房）每周', 2640, 790, 841)
  ]);

  const parsed = h.context.Timetable._parseBlocks(blocks, { width: 3000, height: 1676 });
  assert.equal(parsed.degraded, false);
  assert.equal(parsed.rowsInferred, true);
  assert.equal(parsed.courses.length, 6);

  const highMath = parsed.courses.find((c) => c.name.startsWith('高等数学'));
  assert.deepEqual([highMath.day, highMath.start, highMath.end], [2, 1, 2]);
  assert.equal(highMath.room, '二教309');
  assert.equal(highMath.week, 'all');

  const linearAlgebra = parsed.courses.find((c) => c.day === 1);
  assert.deepEqual([linearAlgebra.start, linearAlgebra.end], [3, 4]);
  assert.equal(linearAlgebra.room, '理教408');
  assert.equal(linearAlgebra.week, 'all');

  const lab = parsed.courses.find((c) => c.name.includes('上机'));
  assert.deepEqual([lab.day, lab.start, lab.end], [5, 5, 6]);
  assert.equal(lab.room, '计算中心二层7-10号机房');
});
