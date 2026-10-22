const test = require('node:test');
const assert = require('node:assert/strict');
const { harness } = require('./functional-dom.cjs');

test('calendar shares concurrent year requests and caches a valid result', async () => {
  let calls = 0, finish;
  const h = harness(['calendar.js'], { fetch: () => {
    calls++;
    return new Promise((resolve) => { finish = resolve; });
  } });
  const first = h.context.Hol.ensure(2030);
  const second = h.context.Hol.ensure(2030);
  assert.equal(calls, 1);
  finish({ ok: true, json: async () => ({ days: [{ date: '2030-01-01', name: '元旦', isOffDay: true }] }) });
  await Promise.all([first, second]);
  assert.equal(h.context.Hol.day('2030-01-01').name, '元旦');
  await h.context.Hol.ensure(2030);
  assert.equal(calls, 1);
});

test('offline year lookup backs off, reports unavailable, and retries later', async () => {
  let calls = 0;
  const now = Date.now();
  const h = harness(['calendar.js'], { now, fetch: async () => { calls++; throw new Error('offline'); } });
  await h.context.Hol.ensure(2030);
  assert.equal(calls, 2);
  assert.equal(h.context.Hol.yearStatus(2030), 'unavailable');
  await h.context.Hol.ensure(2030);
  assert.equal(calls, 2);
  h.now(now + 5 * 60000 + 1);
  await h.context.Hol.ensure(2030);
  assert.equal(calls, 4);
});

test('malformed holiday cache is ignored and cannot crash a day lookup', async () => {
  const h = harness(['calendar.js'], { storage: { ddlr_holiday_2030: '{"days":{}}' } });
  assert.equal(h.context.Hol.day('2030-01-01'), null);
  assert.equal(h.context.Hol.getLocal(2030), null);
});

test('calendar preserves selection on visible adjacent-month dates and escapes remote labels', async () => {
  const h = harness(['calendar.js']);
  h.context.Hol.mem[2026] = { days: [{ date: '2026-09-28', name: '<img src=x>', isOffDay: true }] };
  h.context.Cal.y = 2026;
  h.context.Cal.m = 9;
  h.context.Cal._sel = '2026-09-28';
  await h.context.Cal.render();
  const first = h.el('calGrid').children[0];
  assert.equal(first.classList.contains('sel'), true);
  assert.match(first.innerHTML, /&lt;img src=x&gt;/);
  assert.equal(h.el('calGrid').children.length, 42);
});
