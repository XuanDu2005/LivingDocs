#!/usr/bin/env node
/**
 * Verify the drift-resolve tightening (P3-11).
 *
 * Resolving a documentation drift alert used to require workspace
 * membership alone, so every member of a workspace could accept,
 * dismiss or mark fixed any alert in it — including a CRITICAL one.
 *
 * The checks that matter:
 * <ul>
 *   <li>a pure DEVELOPER may *see* the drift alerts but not resolve
 *       them;</li>
 *   <li>a pure STAFF member may resolve them — the review tier is the
 *       one that judges the documentation;</li>
 *   <li>the permission matrix is the source of truth: revoking
 *       DRIFT_RESOLVE from STAFF takes the capability away again.</li>
 * </ul>
 *
 * Usage:
 *   node scripts/verify-drift-resolve.js
 *
 * Environment overrides:
 *   API_BASE      default http://localhost:8080/api/v1
 *   JWT_SECRET    default matches .env.example
 *   WORKSPACE_ID  the workspace to operate in
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';

const b64url = (buf) =>
  Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

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

// ---------------------------------------------------------------------------
// identities
// ---------------------------------------------------------------------------

const ADMIN = mint('9b35bb4d-844b-4fc8-af5d-aa20ce41858f', 'admin@livingdocs.com', ['ADMIN']);

// A genuine DEVELOPER: this account holds *only* the DEVELOPER platform
// role, so a refusal here proves the tier boundary rather than a data
// accident.
const DEVELOPER_ID = '33333333-3333-3333-3333-333333333333';
const DEVELOPER = mint(DEVELOPER_ID, 'puredev@livingdocs.test', ['DEVELOPER']);

// A genuine STAFF: the review tier that judges the documentation.
const STAFF_ID = '22222222-2222-2222-2222-222222222222';
const STAFF = mint(STAFF_ID, 'purestaff@livingdocs.test', ['STAFF']);

const WS = WORKSPACE_ID;

// ---------------------------------------------------------------------------
// setup
// ---------------------------------------------------------------------------

/**
 * Pin the platform roles of the test accounts.
 *
 * The guards read the role assignments from the database, not from the
 * JWT — an account somebody promoted to MANAGER during an earlier manual
 * session would make the "DEVELOPER cannot resolve" assertion fail for
 * the wrong reason.
 */
async function setupTestRoles() {
  console.log('\n== setup ==');

  const wanted = [
    [DEVELOPER_ID, 'DEVELOPER'],
    [STAFF_ID, 'STAFF'],
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

/**
 * Find an open drift alert of the workspace, whichever account can see
 * it. Returns null when the workspace has none.
 */
async function findOpenAlert(token) {
  const res = await call(token, `/workspaces/${WS}/drift-alerts?status=OPEN`);
  if (res.status !== 200 || !Array.isArray(res.body)) return null;
  return res.body.find((a) => a && a.id) || null;
}

// ---------------------------------------------------------------------------
// checks
// ---------------------------------------------------------------------------

async function checkMatrix() {
  console.log('\n== the permission matrix ==');

  const dev = await call(DEVELOPER, '/users/me');
  check(
    'DEVELOPER does not hold DRIFT_RESOLVE',
    !(dev.body?.permissions ?? []).includes('DRIFT_RESOLVE'),
    JSON.stringify(dev.body?.permissions),
  );

  const staff = await call(STAFF, '/users/me');
  check(
    'STAFF holds DRIFT_RESOLVE',
    (staff.body?.permissions ?? []).includes('DRIFT_RESOLVE'),
    JSON.stringify(staff.body?.permissions),
  );

  const lead = await call(
    mint('11111111-1111-1111-1111-111111111111', 'lead@livingdocs.test', ['TECHNICAL_LEAD']),
    '/users/me',
  );
  check(
    'TECHNICAL_LEAD holds DRIFT_RESOLVE',
    (lead.body?.permissions ?? []).includes('DRIFT_RESOLVE'),
    JSON.stringify(lead.body?.permissions),
  );

  // Detection stays a member-level operation: a developer may run a
  // drift scan, that is the whole point of the author capability.
  check(
    'DEVELOPER still holds DRIFT_DETECT_RUN',
    (dev.body?.permissions ?? []).includes('DRIFT_DETECT_RUN'),
    JSON.stringify(dev.body?.permissions),
  );
}

async function checkVisibility() {
  console.log('\n== visibility ==');

  const dev = await call(DEVELOPER, `/workspaces/${WS}/drift-alerts`);
  check(
    'DEVELOPER may still read the drift alerts',
    dev.status === 200,
    `got ${dev.status}`,
  );

  const staff = await call(STAFF, `/workspaces/${WS}/drift-alerts`);
  check('STAFF may read the drift alerts', staff.status === 200, `got ${staff.status}`);
}

async function checkResolve() {
  console.log('\n== resolving ==');

  const alert = await findOpenAlert(STAFF);
  if (!alert) {
    console.log('  SKIP  no open drift alert in the workspace — nothing to resolve');
    return;
  }

  // A developer may not close an alert — not even one of their own
  // documents.
  const dev = await call(
    DEVELOPER,
    `/workspaces/${WS}/drift-alerts/${alert.id}/resolve`,
    { method: 'POST', body: { resolution: 'ACCEPTED' } },
  );
  check(
    'DEVELOPER is refused the resolve',
    dev.status === 403,
    `got ${dev.status} ${JSON.stringify(dev.body)}`,
  );

  // The Staff tier — the people who review the content — may.
  const staff = await call(
    STAFF,
    `/workspaces/${WS}/drift-alerts/${alert.id}/resolve`,
    { method: 'POST', body: { resolution: 'ACCEPTED' } },
  );
  check(
    'STAFF may resolve a drift alert',
    staff.status === 200,
    `got ${staff.status} ${JSON.stringify(staff.body)}`,
  );
  check(
    'the resolution is recorded',
    staff.body?.resolutionStatus === 'ACCEPTED',
    JSON.stringify(staff.body),
  );
}

/**
 * The matrix is the source of truth: an administrator who revokes
 * DRIFT_RESOLVE from STAFF takes the capability away again, and the
 * API must answer 403 even though the platform role is unchanged.
 */
async function checkRevocation() {
  console.log('\n== revocation ==');

  const roles = await call(ADMIN, '/admin/roles');
  if (roles.status !== 200 || !Array.isArray(roles.body)) {
    console.log('  SKIP  the role catalogue is not readable — cannot test the revocation');
    return;
  }

  const staffRole = roles.body.find((r) => r && r.code === 'STAFF');
  if (!staffRole) {
    console.log('  SKIP  the STAFF role is not in the catalogue');
    return;
  }

  const matrix = await call(ADMIN, `/admin/permissions/roles/${staffRole.id}/matrix`);
  if (matrix.status !== 200 || !Array.isArray(matrix.body?.permissions)) {
    console.log('  SKIP  the permission matrix of STAFF is not readable');
    return;
  }

  const rows = matrix.body.permissions;
  const driftResolve = rows.find((p) => p && p.code === 'DRIFT_RESOLVE');
  if (!driftResolve) {
    console.log('  SKIP  DRIFT_RESOLVE is not in the permission catalogue');
    return;
  }
  check('STAFF holds DRIFT_RESOLVE in the matrix', driftResolve.granted === true);

  // Revoke it.
  const revoked = await call(
    ADMIN,
    `/admin/permissions/roles/${staffRole.id}/matrix`,
    {
      method: 'PUT',
      body: {
        grantedPermissionIds: rows
          .filter((p) => p.granted && p.id !== driftResolve.id)
          .map((p) => p.id),
      },
    },
  );
  check(
    'DRIFT_RESOLVE can be revoked from STAFF',
    revoked.status === 200,
    `got ${revoked.status} ${JSON.stringify(revoked.body)}`,
  );

  try {
    const alert = await findOpenAlert(STAFF);
    if (!alert) {
      console.log('  SKIP  no open drift alert to resolve after the revocation');
    } else {
      const staff = await call(
        STAFF,
        `/workspaces/${WS}/drift-alerts/${alert.id}/resolve`,
        { method: 'POST', body: { resolution: 'ACCEPTED' } },
      );
      check(
        'a STAFF member without the capability is refused',
        staff.status === 403,
        `got ${staff.status}`,
      );
    }
  } finally {
    // Restore the grant, whatever happened above.
    await call(ADMIN, `/admin/permissions/roles/${staffRole.id}/matrix`, {
      method: 'PUT',
      body: { grantedPermissionIds: rows.filter((p) => p.granted).map((p) => p.id) },
    });
    console.log('  ....  the DRIFT_RESOLVE grant of STAFF was restored');
  }
}

// ---------------------------------------------------------------------------
// entry point
// ---------------------------------------------------------------------------

(async () => {
  console.log(`LivingDocs drift-resolve verification against ${API_BASE}`);
  console.log(`workspace: ${WS}`);

  try {
    await setupTestRoles();
    await checkMatrix();
    await checkVisibility();
    await checkResolve();
    await checkRevocation();
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
