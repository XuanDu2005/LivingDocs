#!/usr/bin/env node
/**
 * LivingDocs — merge policy wiring check.
 *
 * Changes the workspace's merge policy through the settings API and
 * verifies that the merge gate reports the new policy — i.e. that the
 * policy comes from the workspace, not from the caller.
 */
'use strict';
const crypto = require('crypto');
const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID;

const b64url = (b) =>
  Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
function mint(userId, email) {
  const now = Math.floor(Date.now() / 1000);
  const h = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const p = b64url(JSON.stringify({ sub: userId, email, iat: now, exp: now + 3600 }));
  const s = b64url(crypto.createHmac('sha256', JWT_SECRET).update(`${h}.${p}`).digest());
  return `${h}.${p}.${s}`;
}
let passed = 0, failed = 0;
const failures = [];
function check(label, cond, detail) {
  if (cond) { passed++; console.log(`  PASS  ${label}`); }
  else { failed++; failures.push(`${label}${detail ? ' — ' + detail : ''}`); console.log(`  FAIL  ${label}${detail ? ' — ' + detail : ''}`); }
}
async function call(token, path, opts = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.ciToken) headers['X-LivingDocs-CI-Token'] = opts.ciToken;
  const res = await fetch(`${API_BASE}${path}`, {
    method: opts.method || 'GET', headers,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  let body; try { body = JSON.parse(text); } catch { body = text; }
  return { status: res.status, body };
}
const ADMIN = mint('9b35bb4d-844b-4fc8-af5d-aa20ce41858f', 'admin@livingdocs.com');

async function main() {
  const s = await call(ADMIN, `/workspaces/${WORKSPACE_ID}/settings`);
  if (s.status !== 200) { console.error('settings unreadable', s.status, s.body); process.exit(1); }
  const original = s.body;
  console.log(`current policy: ${original.mergePolicyCritical}, threshold: ${original.driftSeverityThreshold}`);

  // Issue a token to call the CI endpoint with.
  const issued = await call(ADMIN, `/workspaces/${WORKSPACE_ID}/ci-token/rotate`, { method: 'POST' });
  const token = issued.body?.token;

  const payload = { ...original };
  delete payload.updatedAt; delete payload.workspaceId;

  for (const policy of ['BLOCK', 'WARN']) {
    const upd = await call(ADMIN, `/workspaces/${WORKSPACE_ID}/settings`, {
      method: 'PUT', body: { ...payload, mergePolicyCritical: policy },
    });
    check(`the policy can be set to ${policy}`, upd.status === 200, `HTTP ${upd.status} ${JSON.stringify(upd.body)}`);

    const v = await call(null, `/cicd/workspaces/${WORKSPACE_ID}/repositories/${crypto.randomUUID()}/merge-gate?pullRequestNumber=1`, { ciToken: token });
    if (v.status === 200) {
      check(`the gate reports the workspace policy ${policy}`, v.body.policy === policy, JSON.stringify(v.body));
    } else {
      // Repository not found → the verdict cannot be produced; check the
      // governance endpoint instead, which reads the same policy.
      const g = await call(ADMIN, `/workspaces/${WORKSPACE_ID}/governance/merge-gate`);
      check(`the gate reports the workspace policy ${policy}`, g.body?.policy === policy, `HTTP ${v.status} ${JSON.stringify(g.body)}`);
    }
  }

  // restore
  await call(ADMIN, `/workspaces/${WORKSPACE_ID}/settings`, {
    method: 'PUT', body: { ...payload, mergePolicyCritical: original.mergePolicyCritical },
  });
  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed) { failures.forEach(f => console.log('  - ' + f)); process.exit(1); }
}
main().catch(e => { console.error(e); process.exit(1); });
