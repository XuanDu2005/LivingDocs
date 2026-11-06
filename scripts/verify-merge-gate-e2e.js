#!/usr/bin/env node
/**
 * LivingDocs — end-to-end merge gate check against a real repository
 * and pull request: a BLOCK policy with open drift alerts must produce
 * a blocked verdict.
 */
'use strict';
const crypto = require('crypto');
const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WS = process.env.WORKSPACE_ID;
const REPO = process.env.REPOSITORY_ID;
const PR = process.env.PULL_REQUEST_NUMBER;

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
  else { failed++; failures.push(`${label} — ${detail}`); console.log(`  FAIL  ${label} — ${detail}`); }
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
  const s = await call(ADMIN, `/workspaces/${WS}/settings`);
  const original = s.body;
  const payload = { ...original };
  delete payload.updatedAt; delete payload.workspaceId;

  const issued = await call(ADMIN, `/workspaces/${WS}/ci-token/rotate`, { method: 'POST' });
  const token = issued.body?.token;

  for (const policy of ['WARN', 'BLOCK']) {
    const upd = await call(ADMIN, `/workspaces/${WS}/settings`, {
      method: 'PUT', body: { ...payload, mergePolicyCritical: policy },
    });
    check(`the policy can be set to ${policy}`, upd.status === 200, `HTTP ${upd.status}`);

    const v = await call(null, `/cicd/workspaces/${WS}/repositories/${REPO}/merge-gate?pullRequestNumber=${PR}`, { ciToken: token });
    console.log(`        verdict(${policy}): HTTP ${v.status} ${JSON.stringify(v.body)}`);
    check(`the verdict is produced for ${policy}`, v.status === 200, `HTTP ${v.status}`);
    if (v.status !== 200) continue;
    check(`the verdict reports the policy ${policy}`, v.body.policy === policy, JSON.stringify(v.body));
    if (policy === 'BLOCK') {
      check('a BLOCK policy with open alerts blocks the merge', v.body.blocked === true, JSON.stringify(v.body));
      check('the blocking alerts are counted', v.body.blockingAlertCount > 0, JSON.stringify(v.body));
    } else {
      check('a WARN policy never blocks', v.body.blocked === false, JSON.stringify(v.body));
    }
  }

  await call(ADMIN, `/workspaces/${WS}/settings`, {
    method: 'PUT', body: { ...payload, mergePolicyCritical: original.mergePolicyCritical },
  });
  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed) { failures.forEach(f => console.log('  - ' + f)); process.exit(1); }
}
main().catch(e => { console.error(e); process.exit(1); });
