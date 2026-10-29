'use strict';
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { PGlite } = require('@electric-sql/pglite');
let db;
const alice = '11111111-1111-4111-8111-111111111111';
const bob = '22222222-2222-4222-8222-222222222222';
const payload = name => ({ schema: 1, name, tasks: [], timetable: null });
async function as(user, role = 'authenticated') {
  await db.exec('reset role');
  await db.query("select set_config('request.jwt.claim.sub', $1, false)", [user || '']);
  await db.exec('set role ' + role);
}
async function save(revision, data) {
  return (await db.query('select public.ddlr_save_state($1, $2::jsonb) as result', [revision, JSON.stringify(data)])).rows[0].result;
}
before(async () => {
  db = new PGlite();
  await db.exec(`create role anon; create role authenticated;
    create schema auth; grant usage on schema auth to anon, authenticated;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    insert into auth.users values ('${alice}'),('${bob}');`);
  await db.exec(fs.readFileSync(path.join(__dirname, '../supabase/migrations/202610290001_accounts.sql'), 'utf8'));
});
after(async () => { await db.close(); });

test('unverified/anonymous requests cannot read or write user data', async () => {
  await as(null, 'anon');
  await assert.rejects(db.query('select * from public.ddlr_cloud_state'), /permission denied/);
  await assert.rejects(save(0, payload('anonymous')), /permission denied/);
  await as(null);
  await assert.rejects(save(0, payload('no JWT')), /DDLR_UNAUTHENTICATED/);
});
test('authenticated users can read only their own state and cannot bypass the write protocol', async () => {
  await as(alice);
  const first = await save(0, payload('Alice'));
  assert.equal(first.revision, 1);
  await assert.rejects(db.query('update public.ddlr_cloud_state set revision=99'), /permission denied/);
  await assert.rejects(db.query('insert into public.ddlr_cloud_state(user_id) values($1)', [bob]), /permission denied/);
  await assert.rejects(db.query('delete from public.ddlr_cloud_state'), /permission denied/);
  await as(bob);
  assert.equal((await db.query('select * from public.ddlr_cloud_state')).rows.length, 0);
  await save(0, payload('Bob'));
  const rows = (await db.query('select * from public.ddlr_cloud_state')).rows;
  assert.equal(rows.length, 1); assert.equal(rows[0].user_id, bob);
});
test('two devices using the same expected revision cannot silently overwrite one another', async () => {
  await as(alice);
  const results = await Promise.allSettled([save(1, payload('phone')), save(1, payload('tablet'))]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.match(results.find(r => r.status === 'rejected').reason.message, /DDLR_CONFLICT/);
  assert.equal((await db.query('select revision from public.ddlr_cloud_state')).rows[0].revision, 2);
});
test('schema validation rejects privileged fields, unknown versions, missing fields and oversized data', async () => {
  await as(alice);
  for (const data of [null, [], { ...payload('x'), schema: 2 }, { ...payload('x'), role: 'admin' },
    { ...payload('x'), user_id: bob }, { ...payload('x'), tasks: null }, { ...payload('x'), name: 'x'.repeat(25) },
    { schema: 1, name: 'x', tasks: [] }, { ...payload('x'), tasks: [{ detail: 'x'.repeat(1048576) }] }]) {
    await assert.rejects(save(2, data), /DDLR_INVALID_PAYLOAD/);
  }
  assert.equal((await db.query('select revision from public.ddlr_cloud_state')).rows[0].revision, 2);
});
test('five prior revisions remain recoverable by the owner and invisible to other users', async () => {
  await as(alice);
  for (let revision = 2; revision < 10; revision++) await save(revision, payload('v' + revision));
  const history = (await db.query('select revision from public.ddlr_cloud_history order by revision')).rows;
  assert.deepEqual(history.map(row => row.revision), [5, 6, 7, 8, 9]);
  await as(bob);
  assert.equal((await db.query('select * from public.ddlr_cloud_history')).rows.length, 0);
});
test('account removal cascades state/history without affecting another account', async () => {
  await db.exec('reset role');
  await db.query('delete from auth.users where id=$1', [alice]);
  assert.equal((await db.query('select * from public.ddlr_cloud_history')).rows.length, 0);
  const rows = (await db.query('select * from public.ddlr_cloud_state')).rows;
  assert.equal(rows.length, 1); assert.equal(rows[0].user_id, bob);
});
