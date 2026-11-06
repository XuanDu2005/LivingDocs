#!/usr/bin/env node
/**
 * LivingDocs — workspace doc-type policy checks.
 *
 * Exercises the feature end-to-end against a running stack:
 *
 *   1. a workspace manager lists the effective doc-type policies and
 *      sees every canonical type, with the scope of each;
 *   2. the manager overrides one doc type and the runtime immediately
 *      honours it — the governance endpoint reports the workspace
 *      override, and the effective workflow changes;
 *   3. the override wins over the platform policy for the same type;
 *   4. deleting the override restores the inherited behaviour;
 *   5. a member without GOVERNANCE_MANAGE is refused;
 *   6. every change is written to the audit trail.
 *
 * Usage:
 *   node scripts/verify-workspace-doc-type-policies.js
 *
 * Environment overrides:
 *   API_BASE       default http://localhost:8080/api/v1
 *   JWT_SECRET     default matches docker-compose.yml
 *   WORKSPACE_ID   the workspace to operate in
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID;

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

const b64url = (buf) =>
  Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

function mint(userId, email) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(
    JSON.stringify({ sub: userId, email, iat: now, exp: now + 3600 }),
  );
  const signature = b64url(
    crypto.createHmac('sha256', JWT_SECRET).update(`${header}.${payload}`).digest(),
  );
  return `${header}.${payload}.${signature}`;
}

let passed = 0;
let failed = 0;
const failures = [];

function check(label, condition, detail) {
  if (condition) {
    passed++;
    console.log(`  PASS  ${label}`);
  } else {
    failed++;
    failures.push(`${label}${detail ? ` — ${detail}` : ''}`);
    console.log(`  FAIL  ${label}${detail ? ` — ${detail}` : ''}`);
  }
}

async function call(token, path, opts = {}) {
  const res = await fetch(`${API_BASE}${path}`, {
    method: opts.method || 'GET',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    body = text;
  }
  return { status: res.status, body };
}

// ---------------------------------------------------------------------------
// identities
// ---------------------------------------------------------------------------

const ADMIN = mint('9b35bb4d-844b-4fc8-af5d-aa20ce41858f', 'admin@livingdocs.com');
// A pure DEVELOPER: holds only the DEVELOPER platform role, so a refusal
// proves the capability boundary rather than a data accident.
const DEVELOPER_ID = '33333333-3333-3333-3333-333333333333';
const DEVELOPER = mint(DEVELOPER_ID, 'puredev@livingdocs.test');

const WS = WORKSPACE_ID;

// The doc type we toggle. README is AUTO_APPLY at the platform level
// (V15 seeds it that way), so flipping it to MANAGER_REVIEW is
// observable in both directions.
const DOC_TYPE = 'README';
const FLIPPED = 'MANAGER_REVIEW';
const ORIGINAL = 'AUTO_APPLY';

// ---------------------------------------------------------------------------
// checks
// ---------------------------------------------------------------------------

async function checkList() {
  console.log('\n== list ==');

  const res = await call(ADMIN, `/workspaces/${WS}/doc-type-policies`);
  check('the list is readable by a workspace manager', res.status === 200, `HTTP ${res.status} ${JSON.stringify(res.body)}`);
  if (res.status !== 200) return null;

  const rows = res.body;
  check('the list is an array', Array.isArray(rows));
  check('every canonical doc type is listed', Array.isArray(rows) && rows.length >= 10, `got ${rows?.length}`);

  const readme = rows.find((r) => r.docType === DOC_TYPE);
  check('the target doc type is present', !!readme);
  check(
    'each row carries a scope',
    Array.isArray(rows) && rows.every((r) => ['WORKSPACE', 'PLATFORM', 'DEFAULT'].includes(r.scope)),
    JSON.stringify(rows?.map((r) => r.scope)),
  );
  check(
    'each row carries an effective workflow',
    Array.isArray(rows) && rows.every((r) => r.workflow === 'AUTO_APPLY' || r.workflow === 'MANAGER_REVIEW'),
  );
  return readme;
}

async function checkOverride(readme) {
  console.log('\n== override ==');

  const before = await call(ADMIN, `/workspaces/${WS}/governance/doc-types/${DOC_TYPE}`);
  check('the effective policy is readable', before.status === 200, `HTTP ${before.status}`);
  console.log(`        effective before: ${before.body?.workflow} (scope ${before.body?.scope})`);

  const put = await call(ADMIN, `/workspaces/${WS}/doc-type-policies/${DOC_TYPE}`, {
    method: 'PUT',
    body: { workflow: FLIPPED },
  });
  check('the manager can override the doc type', put.status === 200, `HTTP ${put.status} ${JSON.stringify(put.body)}`);
  check('the override is reported as workspace-scoped', put.body?.scope === 'WORKSPACE', JSON.stringify(put.body));
  check('the override is the effective workflow', put.body?.workflow === FLIPPED, JSON.stringify(put.body));

  const after = await call(ADMIN, `/workspaces/${WS}/governance/doc-types/${DOC_TYPE}`);
  check('the runtime immediately honours the override', after.body?.workflow === FLIPPED, JSON.stringify(after.body));
  check('the runtime reports the workspace scope', after.body?.scope === 'WORKSPACE', JSON.stringify(after.body));
  check(
    'autoApply follows the override',
    after.body?.autoApply === (FLIPPED === 'AUTO_APPLY'),
    JSON.stringify(after.body),
  );
}

async function checkPermissions() {
  console.log('\n== permissions ==');

  const list = await call(DEVELOPER, `/workspaces/${WS}/doc-type-policies`);
  check('a DEVELOPER is refused the policy list', list.status === 403, `got ${list.status}`);

  const put = await call(DEVELOPER, `/workspaces/${WS}/doc-type-policies/${DOC_TYPE}`, {
    method: 'PUT',
    body: { workflow: 'AUTO_APPLY' },
  });
  check('a DEVELOPER cannot change a policy', put.status === 403, `got ${put.status}`);
}

async function checkInvalidDocType() {
  console.log('\n== validation ==');

  const res = await call(ADMIN, `/workspaces/${WS}/doc-type-policies/NOT_A_DOC_TYPE`, {
    method: 'PUT',
    body: { workflow: 'AUTO_APPLY' },
  });
  check('an unknown doc type is rejected', res.status === 400, `got ${res.status}`);

  const bad = await call(ADMIN, `/workspaces/${WS}/doc-type-policies/${DOC_TYPE}`, {
    method: 'PUT',
    body: { workflow: 'WHATEVER' },
  });
  check('an unknown workflow is rejected', bad.status === 400, `got ${bad.status}`);
}

async function checkAuditTrail() {
  console.log('\n== audit trail ==');

  const res = await call(ADMIN, `/workspaces/${WS}/audit-logs?size=50`);
  if (res.status !== 200) {
    check('the audit trail is readable', false, `HTTP ${res.status}`);
    return;
  }
  // The workspace-wide trail answers with a paged envelope; the rows are
  // the raw entities, so the payload is a JSON *string*.
  const rows = Array.isArray(res.body?.items) ? res.body.items : [];
  const entry = rows.find((r) => r.action === 'workspace.doc_type_policy.update');
  check('the change is written to the audit trail', !!entry, JSON.stringify(rows?.map((r) => r.action)));
  if (entry) {
    let payload = {};
    try {
      payload = JSON.parse(entry.payload);
    } catch {
      payload = {};
    }
    check('the audit entry names the doc type', payload.docType === DOC_TYPE, JSON.stringify(entry.payload));
    check('the audit entry records the new workflow', payload.workflow === FLIPPED, JSON.stringify(entry.payload));
  }
}

async function checkReset() {
  console.log('\n== reset ==');

  const del = await call(ADMIN, `/workspaces/${WS}/doc-type-policies/${DOC_TYPE}`, {
    method: 'DELETE',
  });
  check('the manager can drop the override', del.status === 204, `HTTP ${del.status} ${JSON.stringify(del.body)}`);

  const after = await call(ADMIN, `/workspaces/${WS}/governance/doc-types/${DOC_TYPE}`);
  check('the doc type inherits again', after.body?.scope !== 'WORKSPACE', JSON.stringify(after.body));
  check(
    'the inherited workflow is the platform one',
    after.body?.workflow === ORIGINAL,
    `expected ${ORIGINAL}, got ${after.body?.workflow}`,
  );
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

async function main() {
  if (!WS) {
    console.error('WORKSPACE_ID is required.');
    process.exit(1);
  }

  console.log(`API: ${API_BASE}`);
  console.log(`Workspace: ${WS}`);

  const readme = await checkList();
  if (!readme) {
    console.log('\nThe list endpoint is not reachable — stopping here.');
    process.exit(1);
  }

  await checkOverride(readme);
  await checkPermissions();
  await checkInvalidDocType();
  await checkAuditTrail();
  await checkReset();

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) {
    console.log('\nFailures:');
    failures.forEach((f) => console.log(`  - ${f}`));
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
