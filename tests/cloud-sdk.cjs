'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createClient } = require('@supabase/supabase-js');

test('official SDK persists PKCE instead of passwords and exchanges recovery using the original verifier', async () => {
  const memory = new Map(), requests = [], events = [];
  const identity = { id: '11111111-1111-4111-8111-111111111111', email: 'student@example.com',
    aud: 'authenticated', role: 'authenticated', app_metadata: {}, user_metadata: {}, created_at: new Date().toISOString() };
  const sdk = createClient('https://not-a-real-project.supabase.co', 'sb_publishable_test', {
    auth: { autoRefreshToken: false, detectSessionInUrl: false, persistSession: true, flowType: 'pkce',
      storageKey: 'ddlr-auth', storage: { getItem: key => memory.get(key) || null,
        setItem: (key, value) => memory.set(key, value), removeItem: key => memory.delete(key) } },
    global: { fetch: async (input, init) => {
      const url = new URL(input), body = JSON.parse(init.body || '{}');
      requests.push({ path: url.pathname, query: url.searchParams, body });
      const result = url.pathname.endsWith('/signup') ? { ...identity, identities: [] }
        : url.pathname.endsWith('/token') ? { access_token: 'test-access-token', refresh_token: 'test-refresh-token',
          token_type: 'bearer', expires_in: 3600, user: identity } : {};
      return new Response(JSON.stringify(result), { status: 200, headers: { 'Content-Type': 'application/json' } });
    } }
  });
  sdk.auth.onAuthStateChange(event => { events.push(event); });
  const registered = await sdk.auth.signUp({ email: identity.email, password: 'not-stored-password',
    options: { emailRedirectTo: 'ddlradar://auth/callback' } });
  assert.equal(registered.error, null); assert.equal(registered.data.session, null);
  assert.equal(requests[0].query.get('redirect_to'), 'ddlradar://auth/callback');
  assert.equal(requests[0].body.code_challenge_method, 's256');
  assert.ok(memory.get('ddlr-auth-code-verifier'));
  assert.doesNotMatch(JSON.stringify([...memory]), /not-stored-password/);
  const reset = await sdk.auth.resetPasswordForEmail(identity.email, { redirectTo: 'ddlradar://auth/callback' });
  assert.equal(reset.error, null);
  const verifier = JSON.parse(memory.get('ddlr-auth-code-verifier')).split('/')[0];
  const exchanged = await sdk.auth.exchangeCodeForSession('test-recovery-code');
  assert.equal(exchanged.error, null); assert.equal(exchanged.data.redirectType, 'recovery');
  assert.ok(events.includes('PASSWORD_RECOVERY'));
  assert.equal(requests.at(-1).body.code_verifier, verifier);
  assert.equal(requests.at(-1).body.auth_code, 'test-recovery-code');
  assert.equal(memory.has('ddlr-auth-code-verifier'), false);
  await sdk.auth.signOut({ scope: 'local' });
  assert.equal(memory.has('ddlr-auth'), false);
  await sdk.auth.stopAutoRefresh();
});
