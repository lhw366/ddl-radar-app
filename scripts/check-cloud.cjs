'use strict';
// Read-only health checks. Never registers a person or writes user data.
const config = require('../config/cloud.public.json');
async function main() {
  for (const [label, path] of [
    ['auth_settings', '/auth/v1/settings'],
    ['cloud_table_anonymous', '/rest/v1/ddlr_cloud_state?select=revision&limit=0']
  ]) {
    const response = await fetch(config.url + path, {
      headers: { apikey: config.publishableKey }, signal: AbortSignal.timeout(15000)
    });
    const data = await response.json();
    console.log(JSON.stringify({ check: label, status: response.status,
      ...(label === 'auth_settings' && response.ok ? {
        email: data.external && data.external.email, signupDisabled: data.disable_signup,
        emailAutoConfirm: data.mailer_autoconfirm
      } : { code: data.code || null, message: data.message || data.msg || null })
    }));
  }
  const response = await fetch(config.url + '/rest/v1/rpc/ddlr_save_state', {
    method: 'POST', headers: { apikey: config.publishableKey, 'Content-Type': 'application/json' },
    body: JSON.stringify({ expected_revision: 0, new_payload: { schema: 1, name: 'anonymous-check', tasks: [], timetable: null } }),
    signal: AbortSignal.timeout(15000)
  });
  const data = await response.json();
  console.log(JSON.stringify({ check: 'cloud_write_anonymous', status: response.status, code: data.code || null }));
  if (response.ok) throw new Error('Anonymous write must be rejected');
}
main().catch(error => { console.error('Project reachability failed:', error.name); process.exitCode = 1; });
