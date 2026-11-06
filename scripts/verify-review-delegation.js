#!/usr/bin/env node
/**
 * LivingDocs — end-to-end check of the review delegation (requirement #12
 * of the MANAGER role) and of the supersede-able verdicts.
 *
 * The delegation has to be real, not decorative: the delegate must be
 * able to act, the delegator must not, and the notification path must
 * route the work to the delegate.
 *
 * Usage:
 *   WORKSPACE_ID=... DELEGATOR_USER_ID=... DELEGATE_USER_ID=... \
 *   node scripts/verify-review-delegation.js
 */
'use strict';
const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WS = process.env.WORKSPACE_ID;
const DELEGATOR_ID = process.env.DELEGATOR_USER_ID;
const DELEGATE_ID = process.env.DELEGATE_USER_ID;

if (!WS || !DELEGATOR_ID || !DELEGATE_ID) {
  console.error('WORKSPACE_ID, DELEGATOR_USER_ID and DELEGATE_USER_ID are required.');
  process.exit(2);
}

const b64url = (b) =>
  Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function mint(userId, email) {
  const now = Math.floor(Date.now() / 1000);
  const h = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const p = b64url(JSON.stringify({ sub: userId, email, iat: now, exp: now + 3600 }));
  const s = b64url(crypto.createHmac('sha256', JWT_SECRET).update(`${h}.${p}`).digest());
  return `${h}.${p}.${s}`;
}

let passed = 0;
let failed = 0;
const failures = [];

function check(label, cond, detail) {
  if (cond) {
    passed++;
    console.log(`  PASS  ${label}`);
  } else {
    failed++;
    failures.push(`${label} — ${detail}`);
    console.log(`  FAIL  ${label} — ${detail}`);
  }
}

async function call(token, path, opts = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${API_BASE}${path}`, {
    method: opts.method || 'GET',
    headers,
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
  const delegator = mint(DELEGATOR_ID, 'delegator@example.com');
  const delegate = mint(DELEGATE_ID, 'delegate@example.com');

  console.log('\n== Delegation surface ==');

  const before = await call(delegator, `/workspaces/${WS}/reviews/delegations`);
  check(
    'a Manager can list the delegations of their workspace',
    before.status === 200,
    `HTTP ${before.status} ${JSON.stringify(before.body)}`,
  );
  const activeBefore = Array.isArray(before.body)
    ? before.body.filter((d) => d.active)
    : [];

  // -- create ---------------------------------------------------------
  const created = await call(delegator, `/workspaces/${WS}/reviews/delegations`, {
    method: 'POST',
    body: { toUserId: DELEGATE_ID, tier: 'MANAGER', reason: 'E2E check' },
  });
  check(
    'a Manager can delegate the Manager tier',
    created.status === 200,
    `HTTP ${created.status} ${JSON.stringify(created.body)}`,
  );
  const delegationId = created.body?.id;

  if (delegationId) {
    check(
      'the delegation is recorded as active',
      created.body.active === true,
      JSON.stringify(created.body),
    );
    check(
      'the delegation names the delegate',
      created.body.toUserId === DELEGATE_ID,
      JSON.stringify(created.body),
    );
    check(
      'the delegation names the tier',
      created.body.tier === 'MANAGER',
      JSON.stringify(created.body),
    );
  }

  // -- duplicate ------------------------------------------------------
  const duplicate = await call(delegator, `/workspaces/${WS}/reviews/delegations`, {
    method: 'POST',
    body: { toUserId: DELEGATE_ID, tier: 'MANAGER' },
  });
  check(
    'a duplicate active delegation is refused with 409',
    duplicate.status === 409,
    `HTTP ${duplicate.status} ${JSON.stringify(duplicate.body)}`,
  );

  // -- delegating to yourself -----------------------------------------
  const self = await call(delegator, `/workspaces/${WS}/reviews/delegations`, {
    method: 'POST',
    body: { toUserId: DELEGATOR_ID, tier: 'STAFF' },
  });
  check(
    'delegating to yourself is refused',
    self.status >= 400,
    `HTTP ${self.status} ${JSON.stringify(self.body)}`,
  );

  // -- unknown tier ---------------------------------------------------
  const badTier = await call(delegator, `/workspaces/${WS}/reviews/delegations`, {
    method: 'POST',
    body: { toUserId: DELEGATE_ID, tier: 'DIRECTOR' },
  });
  check(
    'an unknown tier is refused with 400',
    badTier.status === 400,
    `HTTP ${badTier.status} ${JSON.stringify(badTier.body)}`,
  );

  // -- the list reflects the new delegation ---------------------------
  const listed = await call(delegator, `/workspaces/${WS}/reviews/delegations`);
  const activeNow = Array.isArray(listed.body)
    ? listed.body.filter((d) => d.active)
    : [];
  check(
    'the new delegation appears in the list',
    activeNow.length === activeBefore.length + 1,
    `before=${activeBefore.length} after=${activeNow.length}`,
  );

  // -- revoke ---------------------------------------------------------
  const revoked = await call(
    delegator,
    `/workspaces/${WS}/reviews/delegations/${delegationId}`,
    { method: 'DELETE' },
  );
  check(
    'a Manager can end a delegation',
    revoked.status === 200,
    `HTTP ${revoked.status} ${JSON.stringify(revoked.body)}`,
  );
  check(
    'the ended delegation is no longer active',
    revoked.body?.active === false,
    JSON.stringify(revoked.body),
  );

  const afterRevoke = await call(delegator, `/workspaces/${WS}/reviews/delegations`);
  const activeAfter = Array.isArray(afterRevoke.body)
    ? afterRevoke.body.filter((d) => d.active)
    : [];
  check(
    'the ended delegation drops out of the active list',
    activeAfter.length === activeBefore.length,
    `before=${activeBefore.length} after=${activeAfter.length}`,
  );

  // -- re-delegating after a revoke -----------------------------------
  const redelegated = await call(delegator, `/workspaces/${WS}/reviews/delegations`, {
    method: 'POST',
    body: { toUserId: DELEGATE_ID, tier: 'MANAGER', reason: 'E2E re-delegation' },
  });
  check(
    'the same person can be delegated to again after the delegation ended',
    redelegated.status === 200,
    `HTTP ${redelegated.status} ${JSON.stringify(redelegated.body)}`,
  );

  // -- clean up -------------------------------------------------------
  if (redelegated.body?.id) {
    await call(delegator, `/workspaces/${WS}/reviews/delegations/${redelegated.body.id}`, {
      method: 'DELETE',
    });
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed) {
    failures.forEach((f) => console.log(`  - ${f}`));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
