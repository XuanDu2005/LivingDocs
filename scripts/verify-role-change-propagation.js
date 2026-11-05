#!/usr/bin/env node
/**
 * Proves that changing a user's platform role takes effect *immediately*.
 *
 * The bug this guards against: the effective permission set of a user is
 * derived from their active roles and cached in Redis under the
 * `user-permissions` cache for `app.cache.user-permissions-ttl-seconds`
 * (15 minutes). If the role-change endpoint does not evict that cache,
 * a user promoted from DEVELOPER to STAFF keeps the developer's
 * permissions — the change looks like it did nothing, both in the UI and
 * behind the API guards.
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';

const ADMIN_ID = process.env.ADMIN_USER_ID || '9b35bb4d-844b-4fc8-af5d-aa20ce41858f';
const ADMIN_EMAIL = process.env.ADMIN_USER_EMAIL || 'admin@livingdocs.com';
const STAFF_ID = '22222222-2222-2222-2222-222222222222';

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

const ADMIN = mint(ADMIN_ID, ADMIN_EMAIL, ['ADMIN']);
const STAFF = mint(STAFF_ID, 'purestaff@livingdocs.test', ['STAFF']);

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

async function main() {
  console.log(`LivingDocs role-change propagation check against ${API_BASE}`);

  // -------------------------------------------------------------------------
  console.log('\n== the staff account starts as a developer ==');

  const setRoles = async (roles) =>
    call(ADMIN, `/admin/users/${STAFF_ID}/roles`, {
      method: 'PUT',
      body: { roles },
    });

  const downgrade = await setRoles(['DEVELOPER']);
  check(
    'the admin can downgrade the account',
    downgrade.status === 200,
    `got ${downgrade.status} ${JSON.stringify(downgrade.body)}`,
  );

  // The cache must be evicted by the change itself, so this read has to
  // reflect the *new* role set without waiting for the TTL to expire.
  const devProfile = await call(STAFF, '/users/me');
  check(
    'the profile reports the developer role',
    devProfile.body?.roles?.includes('DEVELOPER'),
    JSON.stringify(devProfile.body?.roles),
  );
  check(
    'the profile carries no review permission',
    !devProfile.body?.permissions?.includes('DOCUMENT_VERSION_REVIEW'),
    JSON.stringify(devProfile.body?.permissions),
  );

  const devQueue = await call(STAFF, `/workspaces/${WORKSPACE_ID}/reviews/staff-queue`);
  check(
    'the developer is refused the staff queue',
    devQueue.status === 403,
    `got ${devQueue.status}`,
  );

  // -------------------------------------------------------------------------
  console.log('\n== promoting to STAFF takes effect at once ==');

  const promote = await setRoles(['STAFF']);
  check(
    'the admin can promote the account',
    promote.status === 200,
    `got ${promote.status} ${JSON.stringify(promote.body)}`,
  );

  const staffProfile = await call(STAFF, '/users/me');
  check(
    'the profile reports the staff role',
    staffProfile.body?.roles?.includes('STAFF'),
    JSON.stringify(staffProfile.body?.roles),
  );
  check(
    'the profile carries the review permission',
    staffProfile.body?.permissions?.includes('DOCUMENT_VERSION_REVIEW'),
    JSON.stringify(staffProfile.body?.permissions),
  );
  check(
    'the profile no longer carries the developer-only rollback grant',
    !staffProfile.body?.permissions?.includes('DOCUMENT_ROLLBACK') ||
      staffProfile.body?.permissions?.includes('DOCUMENT_VERSION_DISMISS'),
    JSON.stringify(staffProfile.body?.permissions),
  );

  const staffQueue = await call(STAFF, `/workspaces/${WORKSPACE_ID}/reviews/staff-queue`);
  check(
    'the staff member is admitted to the staff queue',
    staffQueue.status === 200,
    `got ${staffQueue.status} ${JSON.stringify(staffQueue.body)}`,
  );

  const caps = await call(STAFF, `/workspaces/${WORKSPACE_ID}/capabilities`);
  check(
    'the capability snapshot says the user may review',
    caps.body?.canStaffReview === true,
    JSON.stringify(caps.body),
  );
  check(
    'the capability snapshot says the user may not approve',
    caps.body?.canManagerApprove === false,
    JSON.stringify(caps.body),
  );

  // -------------------------------------------------------------------------
  console.log('\n== the manager tier is still refused ==');

  const managerQueue = await call(STAFF, `/workspaces/${WORKSPACE_ID}/reviews/manager-queue`);
  check(
    'the staff member is refused the manager queue',
    managerQueue.status === 403,
    `got ${managerQueue.status}`,
  );
}

(async () => {
  try {
    await main();
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
