const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

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
});

test('render refreshes the term control when imported state replaces the timetable', async () => {
  const h = setup([]);
  await h.bind();
  h.context.S.timetable.termStart = '2026-09-01';
  h.context.Timetable.render();
  assert.equal(h.el('ttTermStart').value, '2026-09-01');
  assert.match(h.el('ttTermHint').textContent, /本周第/);
});
