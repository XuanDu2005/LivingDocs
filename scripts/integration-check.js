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
// The workspace MANAGER of the test workspace. The doc-type policy
// overrides require the *workspace* MANAGER role on top of the
// GOVERNANCE_MANAGE capability: the capability says "may administer the
// governance policy", the workspace role says "of this workspace". A
// TECHNICAL_LEAD holds the capability but is only a MEMBER here, so it
// can read the policies but not change them.
const WS_MANAGER = mint(
  'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de',
  'dxuan8461@gmail.com',
  ['MANAGER'],
);

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

  // The doc-type policy the runtime enforces must say where it came
  // from, so a reader can tell a workspace override from an inherited
  // platform rule.
  check(
    'the doc-type policy reports its scope',
    ['WORKSPACE', 'PLATFORM', 'DEFAULT'].includes(docType.body?.scope),
    JSON.stringify(docType.body),
  );
}

/**
 * The per-workspace doc-type policy overrides.
 *
 * <p>A workspace manager must be able to decide, for their own
 * workspace, which documentation types need an approval — without the
 * platform administrator having to change a rule that applies to every
 * other workspace. The override has to win over the platform policy,
 * and dropping it has to restore the inherited behaviour.
 */
async function checkWorkspaceDocTypePolicies() {
  console.log('\n== workspace doc-type policies ==');

  const list = await call(LEAD, `/workspaces/${WS}/doc-type-policies`);
  check('the policy list is readable', list.status === 200, `got ${list.status}`);
  check('the policy list is an array', Array.isArray(list.body));
  check(
    'every row carries a scope',
    Array.isArray(list.body) &&
      list.body.every((r) => ['WORKSPACE', 'PLATFORM', 'DEFAULT'].includes(r.scope)),
  );

  // A plain DEVELOPER holds neither the capability nor the workspace
  // role, so both surfaces have to refuse.
  const devList = await call(DEVELOPER, `/workspaces/${WS}/doc-type-policies`);
  check('a DEVELOPER is refused the policy list', devList.status === 403, `got ${devList.status}`);

  const devPut = await call(DEVELOPER, `/workspaces/${WS}/doc-type-policies/README`, {
    method: 'PUT',
    body: { workflow: 'MANAGER_REVIEW' },
  });
  check('a DEVELOPER cannot change a policy', devPut.status === 403, `got ${devPut.status}`);

  // A TECHNICAL_LEAD holds GOVERNANCE_MANAGE but is only a workspace
  // MEMBER: it may read the policy, but the workspace role is what
  // proves the person owns this workspace's rules.
  const leadPut = await call(LEAD, `/workspaces/${WS}/doc-type-policies/README`, {
    method: 'PUT',
    body: { workflow: 'MANAGER_REVIEW' },
  });
  check(
    'a non-manager workspace member cannot change a policy',
    leadPut.status === 403,
    `got ${leadPut.status}`,
  );

  // The workspace must actually be able to change its own policy, and
  // the runtime must honour the change immediately.
  const before = await call(WS_MANAGER, `/workspaces/${WS}/governance/doc-types/README`);
  const flipped = before.body?.workflow === 'AUTO_APPLY' ? 'MANAGER_REVIEW' : 'AUTO_APPLY';

  const put = await call(WS_MANAGER, `/workspaces/${WS}/doc-type-policies/README`, {
    method: 'PUT',
    body: { workflow: flipped },
  });
  check('a workspace manager can override a doc type', put.status === 200, `got ${put.status} ${JSON.stringify(put.body)}`);
  check('the override is workspace-scoped', put.body?.scope === 'WORKSPACE', JSON.stringify(put.body));

  const after = await call(WS_MANAGER, `/workspaces/${WS}/governance/doc-types/README`);
  check('the runtime honours the override', after.body?.workflow === flipped, JSON.stringify(after.body));
  check('the runtime reports the workspace scope', after.body?.scope === 'WORKSPACE', JSON.stringify(after.body));

  // Dropping the override has to restore the inherited behaviour.
  const del = await call(WS_MANAGER, `/workspaces/${WS}/doc-type-policies/README`, { method: 'DELETE' });
  check('the override can be dropped', del.status === 204, `got ${del.status}`);

  const restored = await call(WS_MANAGER, `/workspaces/${WS}/governance/doc-types/README`);
  check('the doc type inherits again', restored.body?.scope !== 'WORKSPACE', JSON.stringify(restored.body));
  check(
    'the inherited workflow is the one it had before',
    restored.body?.workflow === before.body?.workflow,
    `${before.body?.workflow} -> ${restored.body?.workflow}`,
  );
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
    {
      path: `/workspaces/${WS}/doc-type-policies`,
      permitted: ['GOVERNANCE_VIEW'],
      label: 'doc-type policies',
    },
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

  // The MANAGER tier is the oversight tier of the audit trail. It has
  // to read the trail of a workspace — and the trail of a document —
  // before it grants an approval, so both surfaces must answer 200 for
  // it. They used to answer 403: the capability matrix granted
  // AUDIT_LOG_VIEW to ADMIN and TECHNICAL_LEAD only, while the role
  // model says the Manager owns the workspace with full oversight.
  const managerAudit = await call(WS_MANAGER, `/workspaces/${WS}/audit-logs`);
  check(
    'MANAGER may read the workspace audit log',
    managerAudit.status === 200,
    `got ${managerAudit.status} ${JSON.stringify(managerAudit.body)}`,
  );

  const managerTrail = await call(
    WS_MANAGER,
    `/workspaces/${WS}/documents/${REVIEW_DOC}/audit-trail`,
  );
  check(
    'MANAGER may read the audit trail of a document',
    managerTrail.status === 200,
    `got ${managerTrail.status} ${JSON.stringify(managerTrail.body)}`,
  );
  check(
    'the document trail carries its paging envelope',
    Array.isArray(managerTrail.body?.items),
    JSON.stringify(managerTrail.body),
  );
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

  // The MANAGER tier is the oversight tier of the audit trail. The
  // grant was missing from the matrix, so the Manager got a 403 on the
  // very surface it has to read before granting an approval.
  const manager = await call(WS_MANAGER, '/users/me');
  check(
    'MANAGER holds AUDIT_LOG_VIEW',
    (manager.body?.permissions ?? []).includes('AUDIT_LOG_VIEW'),
    JSON.stringify(manager.body?.permissions),
  );

  // STAFF reviews content against the governance policy — it reads the
  // policy (GOVERNANCE_VIEW), not the governance trail of the
  // workspace.
  const staff = await call(STAFF, '/users/me');
  check(
    'STAFF does not hold AUDIT_LOG_VIEW',
    !(staff.body?.permissions ?? []).includes('AUDIT_LOG_VIEW'),
    JSON.stringify(staff.body?.permissions),
  );
  check(
    'STAFF holds GOVERNANCE_VIEW',
    (staff.body?.permissions ?? []).includes('GOVERNANCE_VIEW'),
    JSON.stringify(staff.body?.permissions),
  );

  // Resolving a drift alert is a content judgement, so it belongs to
  // the review tiers — the DEVELOPER may run a drift scan and read the
  // alerts, but it may not close them.
  check(
    'DEVELOPER does not hold DRIFT_RESOLVE',
    !(dev.body?.permissions ?? []).includes('DRIFT_RESOLVE'),
    JSON.stringify(dev.body?.permissions),
  );
  check(
    'STAFF holds DRIFT_RESOLVE',
    (staff.body?.permissions ?? []).includes('DRIFT_RESOLVE'),
    JSON.stringify(staff.body?.permissions),
  );
  check(
    'DEVELOPER still holds DRIFT_DETECT_RUN',
    (dev.body?.permissions ?? []).includes('DRIFT_DETECT_RUN'),
    JSON.stringify(dev.body?.permissions),
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
  // The generated version is only published automatically when the
  // doc-type workflow says AUTO_APPLY *and* the confidence reaches the
  // workspace threshold. Otherwise it stays a DRAFT and the author
  // submits it from the document page — the version never enters the
  // review queue on its own. So DRAFT is a legitimate outcome here.
  check(
    'the new version is a draft, published or awaiting review',
    ['DRAFT', 'PENDING', 'PUBLISHED', 'IN_REVIEW', 'APPROVED'].includes(
      versions.body?.[0]?.status,
    ),
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
    await checkWorkspaceDocTypePolicies();
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
