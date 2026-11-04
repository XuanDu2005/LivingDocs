#!/usr/bin/env node
/**
 * Verification for the notification-preference, attribution and
 * coverage-gap fixes.
 *
 * Usage: node scripts/verify-developer-fixes.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';

const DEV_ID = '33333333-3333-3333-3333-333333333333';

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

const DEV = mint(DEV_ID, 'puredev@livingdocs.test', ['DEVELOPER']);

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

async function checkPreferencesRoundTrip() {
  console.log('\n== notification preferences ==');

  const before = await call(DEV, '/users/me/notification-preferences');
  check('preferences are readable', before.status === 200, `got ${before.status}`);
  const original = before.body;

  // Turn every toggle off and confirm the server persisted the change
  // (the toggles used to be stored but never read).
  const allOff = {
    emailOnDriftDetected: false,
    emailOnReviewRequested: false,
    emailOnAutoUpdate: false,
    emailOnTokenInvalid: false,
    inAppOnDriftDetected: false,
    inAppOnReviewRequested: false,
    inAppOnAutoUpdate: false,
    inAppOnTokenInvalid: false,
    digestFrequency: original.digestFrequency,
  };
  const put = await call(DEV, '/users/me/notification-preferences', {
    method: 'PUT',
    body: allOff,
  });
  check('preferences are writable', put.status === 200, `got ${put.status}`);
  check(
    'the server persisted inAppOnDriftDetected=false',
    put.body?.inAppOnDriftDetected === false,
    JSON.stringify(put.body),
  );

  const reread = await call(DEV, '/users/me/notification-preferences');
  check(
    'the toggles survive a re-read',
    reread.body?.inAppOnDriftDetected === false &&
      reread.body?.inAppOnAutoUpdate === false,
    JSON.stringify(reread.body),
  );

  // Restore.
  await call(DEV, '/users/me/notification-preferences', {
    method: 'PUT',
    body: original,
  });
}

async function checkNotificationDelivery() {
  console.log('\n== notification delivery ==');

  const before = await call(DEV, '/notifications');
  const beforeCount = Array.isArray(before.body) ? before.body.length : 0;
  const beforeTestRows = (before.body ?? []).filter(
    (n) => n.kind === 'system.test' && n.title === 'Hello from the event bus',
  ).length;

  // A test notification is a kind without a user-facing toggle, so it is
  // always delivered in-app. The point of the check is that exactly ONE
  // row appears — the old code published an IN_APP Kafka event that the
  // consumer turned back into a second row, and again into a third, …
  const sent = await call(DEV, '/notifications/test', { method: 'POST' });
  check('the test notification is accepted', sent.status === 200, `got ${sent.status}`);

  await new Promise((r) => setTimeout(r, 4000));

  const after = await call(DEV, '/notifications');
  const afterCount = Array.isArray(after.body) ? after.body.length : 0;
  check(
    'exactly one notification row was persisted (no duplicate loop)',
    afterCount - beforeCount === 1,
    `before=${beforeCount} after=${afterCount}`,
  );

  const afterTestRows = (after.body ?? []).filter(
    (n) => n.kind === 'system.test' && n.title === 'Hello from the event bus',
  ).length;
  check(
    'exactly one system.test row was added',
    afterTestRows - beforeTestRows === 1,
    `before=${beforeTestRows} after=${afterTestRows}`,
  );
}

async function checkPermissionSurface() {
  console.log('\n== developer capability surface ==');

  const me = await call(DEV, '/users/me');
  const perms = me.body?.permissions ?? [];
  check(
    'DEVELOPER holds the author capabilities',
    [
      'DOCUMENT_VERSION_DISMISS',
      'DOCUMENT_ROLLBACK',
      'DOCUMENT_REGENERATE_AI',
      'REPOSITORY_AUTO_UPDATE_MANAGE',
      'MODULE_AUTO_UPDATE_MANAGE',
      'DRIFT_DETECT_RUN',
    ].every((p) => perms.includes(p)),
    JSON.stringify(perms),
  );
  check(
    'DEVELOPER does not hold DOCUMENT_VERSION_PUBLISH',
    !perms.includes('DOCUMENT_VERSION_PUBLISH'),
    JSON.stringify(perms),
  );
  check(
    'DEVELOPER does not hold SCM_COMMIT',
    !perms.includes('SCM_COMMIT'),
    JSON.stringify(perms),
  );
}

(async () => {
  console.log(`LivingDocs fix verification against ${API_BASE}`);
  try {
    await checkPreferencesRoundTrip();
    await checkNotificationDelivery();
    await checkPermissionSurface();
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
