#!/usr/bin/env node
/**
 * LivingDocs integration checks.
 *
 * Exercises the endpoints that unit tests cannot reach: the two-axis
 * authorization model, the review queues, the governance policy and the
 * asynchronous generation pipeline — against a running stack.
 *
 * Usage:
 *   node scripts/integration-check.js
 *
 * Environment overrides:
 *   API_BASE       default http://localhost:8080/api/v1
 *   JWT_SECRET     default matches .env.example
 *   WORKSPACE_ID   the workspace to operate in
 *   SKIP_GENERATE  set to 1 to skip the generation checks
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';
const SKIP_GENERATE = process.env.SKIP_GENERATE === '1';

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

const b64url = (buf) =>
  Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

/** Mint a JWT for a user with the given platform roles. */
function mint(userId, email, roles) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(
    JSON.stringify({ sub: userId, email, iat: now, exp: now + 3600, roles }),
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// identities
// ---------------------------------------------------------------------------

const ADMIN = mint('9b35bb4d-844b-4fc8-af5d-aa20ce41858f', 'admin@livingdocs.com', ['ADMIN']);
const LEAD = mint('11111111-1111-1111-1111-111111111111', 'lead@livingdocs.test', [
  'TECHNICAL_LEAD',
]);
// A genuine DEVELOPER: this account holds *only* the DEVELOPER platform
// role, so a refusal here proves the tier boundary rather than a data
// accident. Do not point this at a real account — an account that also
// holds MANAGER would legitimately pass the review guards.
const DEVELOPER_ID = '33333333-3333-3333-3333-333333333333';
const DEVELOPER = mint(DEVELOPER_ID, 'puredev@livingdocs.test', ['DEVELOPER']);
// A genuine STAFF: this user holds *only* the STAFF platform role, so a
// refusal here proves the tier boundary rather than a data accident.
const STAFF = mint('22222222-2222-2222-2222-222222222222', 'purestaff@livingdocs.test', [
  'STAFF',
]);

const WS = WORKSPACE_ID;
const AUTO_APPLY_DOC = '33333333-3333-3333-3333-333333333333';
const REVIEW_DOC = '44444444-4444-4444-4444-444444444444';

// ---------------------------------------------------------------------------
// setup
// ---------------------------------------------------------------------------

/**
 * Make sure every test account holds exactly the platform role it is
 * supposed to test.
 *
 * The role guards read the role assignments from the database, not from
 * the JWT — so an account that somebody promoted to MANAGER during an
 * earlier manual session would make the "DEVELOPER cannot review"
 * assertions fail for the wrong reason. Pinning the roles here keeps the
 * suite deterministic no matter what the database looks like.
 */
async function setupTestRoles() {
  console.log('\n== setup ==');

  const wanted = [
    [DEVELOPER_ID, 'DEVELOPER'],
    ['11111111-1111-1111-1111-111111111111', 'TECHNICAL_LEAD'],
    ['22222222-2222-2222-2222-222222222222', 'STAFF'],
  ];

  for (const [userId, role] of wanted) {
    const res = await call(ADMIN, `/admin/users/${userId}/roles`, {
      method: 'PUT',
      body: { roles: [role] },
    });
    check(
      `test account ${userId} holds exactly ${role}`,
      res.status === 200,
      `HTTP ${res.status} ${JSON.stringify(res.body)}`,
    );
  }
}

// ---------------------------------------------------------------------------
// checks
// ---------------------------------------------------------------------------

async function checkCapabilities() {
  console.log('\n== capabilities ==');

  const lead = await call(LEAD, `/workspaces/${WS}/capabilities`);
  check('TECHNICAL_LEAD is a member', lead.body?.member === true, JSON.stringify(lead.body));
  check(
    'TECHNICAL_LEAD can staff-review',
    lead.body?.canStaffReview === true,
    JSON.stringify(lead.body),
  );
  check(
    'TECHNICAL_LEAD can manager-approve',
    lead.body?.canManagerApprove === true,
    JSON.stringify(lead.body),
  );
  check(
    'TECHNICAL_LEAD is not a workspace manager',
    lead.body?.workspaceManager === false,
    JSON.stringify(lead.body),
  );

  const dev = await call(DEVELOPER, `/workspaces/${WS}/capabilities`);
  check('DEVELOPER cannot staff-review', dev.body?.canStaffReview === false);
  check('DEVELOPER cannot manager-approve', dev.body?.canManagerApprove === false);
}

async function checkReviewQueues() {
  console.log('\n== review queues ==');

  const lead = await call(LEAD, `/workspaces/${WS}/reviews/pending-on-me`);
  check('pending-on-me returns 200 for a member', lead.status === 200);
  check('pending-on-me has a staffQueue array', Array.isArray(lead.body?.staffQueue));
  check('pending-on-me has a managerQueue array', Array.isArray(lead.body?.managerQueue));
  check('pending-on-me has a publishQueue array', Array.isArray(lead.body?.publishQueue));

  const dev = await call(DEVELOPER, `/workspaces/${WS}/reviews/pending-on-me`);
  check('DEVELOPER sees empty queues', dev.status === 200);
  check(
    'DEVELOPER staff queue is empty',
    (dev.body?.staffQueue ?? []).length === 0,
    JSON.stringify(dev.body?.staffQueue),
  );

  const devQueue = await call(DEVELOPER, `/workspaces/${WS}/reviews/staff-queue`);
  check('DEVELOPER is refused the staff queue', devQueue.status === 403);

  const staffManagerQueue = await call(STAFF, `/workspaces/${WS}/reviews/manager-queue`);
  check('STAFF is refused the manager queue', staffManagerQueue.status === 403);
}

async function checkGovernance() {
  console.log('\n== governance ==');

  // The effective policy is capability-gated (GOVERNANCE_VIEW), not
  // merely membership-gated: a plain DEVELOPER must not be able to
  // read the thresholds the runtime enforces.
  const devPolicy = await call(DEVELOPER, `/workspaces/${WS}/governance`);
  check('DEVELOPER is refused the governance policy', devPolicy.status === 403, `got ${devPolicy.status}`);

  const policy = await call(LEAD, `/workspaces/${WS}/governance`);
  check('governance policy is readable by a permitted member', policy.status === 200, `got ${policy.status}`);
  check('policy has a confidence threshold', typeof policy.body?.confidenceThreshold === 'number');
  check(
    'policy has a merge policy',
    policy.body?.mergePolicy === 'WARN' || policy.body?.mergePolicy === 'BLOCK',
  );

  const docType = await call(LEAD, `/workspaces/${WS}/governance/doc-types/README`);
  check('doc-type policy is readable', docType.status === 200, `got ${docType.status}`);
  check(
    'doc-type workflow is a known value',
    docType.body?.workflow === 'AUTO_APPLY' || docType.body?.workflow === 'MANAGER_REVIEW',
    JSON.stringify(docType.body),
  );

  const mergeGate = await call(LEAD, `/workspaces/${WS}/governance/merge-gate`);
  check('merge gate is readable', mergeGate.status === 200, `got ${mergeGate.status}`);
  check('merge gate reports blocked as a boolean', typeof mergeGate.body?.blocked === 'boolean');
  check('merge gate explains itself', typeof mergeGate.body?.reason === 'string');
}

/**
 * The capability surfaces that must be invisible to a plain DEVELOPER.
 *
 * <p>These are the endpoints that leak operational information (DB
 * latency, JVM memory, the AI provider configuration, the audit trail)
 * or grant administrative leverage (publishing to GitHub). Every one of
 * them answers 403 for a DEVELOPER and 200 for a role that holds the
 * matching permission.
 */
async function checkCapabilitySurfaces() {
  console.log('\n== capability surfaces ==');

  const surfaces = [
    { path: '/system-health', permitted: ['HEALTH_DASHBOARD_VIEW'], label: 'system health' },
    { path: `/workspaces/${WS}/audit-logs`, permitted: ['AUDIT_LOG_VIEW'], label: 'audit log' },
    { path: `/workspaces/${WS}/analytics`, permitted: ['ANALYTICS_VIEW'], label: 'analytics' },
    { path: `/workspaces/${WS}/ai-settings`, permitted: ['AI_CONFIG_READ'], label: 'AI settings' },
    {
      path: `/workspaces/${WS}/notification-policies`,
      permitted: ['NOTIFICATION_POLICY_VIEW'],
      label: 'notification policies',
    },
    { path: `/workspaces/${WS}/integrations`, permitted: ['INTEGRATION_VIEW'], label: 'integrations' },
    { path: `/workspaces/${WS}/ai-usage`, permitted: ['AI_USAGE_VIEW'], label: 'AI usage' },
  ];

  for (const surface of surfaces) {
    const dev = await call(DEVELOPER, surface.path);
    check(
      `DEVELOPER is refused the ${surface.label}`,
      dev.status === 403,
      `got ${dev.status}`,
    );
  }

  // STAFF holds a subset of the oversight permissions: it reviews
  // content against the policy, so it may read the health dashboard and
  // the notification policies, but not the audit trail.
  const staffHealth = await call(STAFF, '/system-health');
  check('STAFF may read the system health', staffHealth.status === 200, `got ${staffHealth.status}`);

  const staffAudit = await call(STAFF, `/workspaces/${WS}/audit-logs`);
  check('STAFF is refused the audit log', staffAudit.status === 403, `got ${staffAudit.status}`);

  const staffAiSettings = await call(STAFF, `/workspaces/${WS}/ai-settings`);
  check(
    'STAFF is refused the AI provider configuration',
    staffAiSettings.status === 403,
    `got ${staffAiSettings.status}`,
  );

  // ADMIN is granted everything by the matrix.
  const adminHealth = await call(ADMIN, '/system-health');
  check('ADMIN may read the system health', adminHealth.status === 200, `got ${adminHealth.status}`);
}

/**
 * The effective permission set the backend hands to the front-end.
 */
async function checkPermissionExposure() {
  console.log('\n== permission exposure ==');

  const dev = await call(DEVELOPER, '/users/me');
  check('/users/me returns the developer profile', dev.status === 200, `got ${dev.status}`);
  check(
    '/users/me exposes the effective permission list',
    Array.isArray(dev.body?.permissions),
    JSON.stringify(dev.body?.permissions),
  );
  check(
    'DEVELOPER holds no oversight permission',
    !(dev.body?.permissions ?? []).some((code) =>
      [
        'HEALTH_DASHBOARD_VIEW',
        'ANALYTICS_VIEW',
        'AUDIT_LOG_VIEW',
        'GOVERNANCE_VIEW',
        'GOVERNANCE_MANAGE',
        'NOTIFICATION_POLICY_VIEW',
        'NOTIFICATION_POLICY_MANAGE',
        'INTEGRATION_VIEW',
        'SCM_COMMIT',
        'AI_CONFIG_READ',
        'AI_USAGE_VIEW',
      ].includes(code),
    ),
    JSON.stringify(dev.body?.permissions),
  );

  const lead = await call(LEAD, '/users/me');
  check(
    'TECHNICAL_LEAD holds the oversight permissions',
    (lead.body?.permissions ?? []).includes('HEALTH_DASHBOARD_VIEW'),
    JSON.stringify(lead.body?.permissions),
  );
}

async function checkGeneration() {
  if (SKIP_GENERATE) {
    console.log('\n== generation (skipped) ==');
    return;
  }
  console.log('\n== generation ==');

  const files = [{ path: 'src/Widget.tsx', content: 'export function Widget() { return <div/>; }' }];

  // The call must be answered with 202 and a job id, not a finished version.
  const accepted = await call(
    DEVELOPER,
    `/workspaces/${WS}/documents/${AUTO_APPLY_DOC}/generate?template=README`,
    { method: 'POST', body: files },
  );
  check('generate answers 202 Accepted', accepted.status === 202, `got ${accepted.status}`);
  check('generate returns a job id', typeof accepted.body?.jobId === 'string');

  // Poll the status endpoint until the job reaches a terminal state.
  let status = null;
  for (let i = 0; i < 30; i++) {
    await sleep(2000);
    const res = await call(
      DEVELOPER,
      `/workspaces/${WS}/documents/${AUTO_APPLY_DOC}/generation-status`,
    );
    status = res.body;
    if (['SUCCEEDED', 'FAILED', 'SKIPPED'].includes(status?.status)) break;
  }
  check(
    'generation reaches a terminal state',
    status?.status === 'SUCCEEDED',
    JSON.stringify(status),
  );
  check('generation records a version id', typeof status?.documentVersionId === 'string');
  check('generation records token usage', (status?.promptTokens ?? 0) > 0);

  const versions = await call(DEVELOPER, `/documents/${AUTO_APPLY_DOC}/versions`);
  check('the new version is listed', Array.isArray(versions.body) && versions.body.length > 0);
  check(
    'the new version is queued for review or published',
    ['PENDING', 'PUBLISHED'].includes(versions.body?.[0]?.status),
    versions.body?.[0]?.status,
  );
}

async function checkBudget() {
  if (SKIP_GENERATE) return;
  console.log('\n== token budget ==');

  // With the limit at its default the call must not be refused. When the
  // workspace has exhausted its budget the API answers 429.
  const res = await call(
    DEVELOPER,
    `/workspaces/${WS}/documents/${REVIEW_DOC}/generate?template=MODULE_GUIDE`,
    { method: 'POST', body: [{ path: 'a.ts', content: 'export const a = 1;' }] },
  );
  const expected = res.status === 202 || res.status === 429;
  check(
    'generate either queues or refuses on budget',
    expected,
    `unexpected status ${res.status}`,
  );
  if (res.status === 429) {
    check('the 429 explains the budget', typeof res.body?.message === 'string');
  }
}

// ---------------------------------------------------------------------------
// entry point
// ---------------------------------------------------------------------------

(async () => {
  console.log(`LivingDocs integration checks against ${API_BASE}`);
  console.log(`workspace: ${WS}`);

  try {
    await setupTestRoles();
    await checkCapabilities();
    await checkReviewQueues();
    await checkGovernance();
    await checkCapabilitySurfaces();
    await checkPermissionExposure();
    await checkGeneration();
    await checkBudget();
  } catch (err) {
    console.error('\nUnexpected error:', err);
    failed++;
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) {
    console.log('\nFailures:');
    for (const f of failures) console.log(`  - ${f}`);
    process.exit(1);
  }
})();
