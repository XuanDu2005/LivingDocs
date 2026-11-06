#!/usr/bin/env node
/**
 * LivingDocs — end-to-end check of the supersede-able review verdicts.
 *
 * Before the `superseded_at` column existed, a reviewer who mis-clicked
 * "Reject" could not correct it: the second verdict hit the partial
 * unique index and the request died with a 500. Correcting a verdict is
 * an ordinary action, so the trail has to support it.
 *
 * The checks need a version in the Staff review state and two actors
 * who may review it.
 *
 * Usage:
 *   WORKSPACE_ID=... DOCUMENT_ID=... VERSION_ID=... \
 *   REVIEWER_A_ID=... REVIEWER_B_ID=... \
 *   node scripts/verify-review-supersede.js
 */
'use strict';
const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WS = process.env.WORKSPACE_ID;
const DOC = process.env.DOCUMENT_ID;
const VERSION = process.env.VERSION_ID;
const REVIEWER_A = process.env.REVIEWER_A_ID;
const REVIEWER_B = process.env.REVIEWER_B_ID;

if (!WS || !DOC || !VERSION || !REVIEWER_A || !REVIEWER_B) {
  console.error(
    'WORKSPACE_ID, DOCUMENT_ID, VERSION_ID, REVIEWER_A_ID and REVIEWER_B_ID are required.',
  );
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
  const a = mint(REVIEWER_A, 'reviewer-a@example.com');
  const b = mint(REVIEWER_B, 'reviewer-b@example.com');

  console.log('\n== Supersede-able verdicts ==');

  const staffUrl = `/workspaces/${WS}/documents/${DOC}/versions/${VERSION}/staff-review`;

  // 1. The first verdict of the Staff tier.
  const first = await call(a, staffUrl, {
    method: 'POST',
    body: { decision: 'REJECTED', comment: 'E2E first verdict' },
  });
  check(
    'the first Staff verdict is recorded',
    first.status === 200,
    `HTTP ${first.status} ${JSON.stringify(first.body)}`,
  );
  check(
    'the first verdict is current',
    first.body?.supersededAt === null || first.body?.supersededAt === undefined,
    JSON.stringify(first.body),
  );

  // 2. The same reviewer corrects the verdict. Before the supersede
  //    mechanism this died with a 500, and before the "own verdict is
  //    correctable" rule it died with a 400 — the rejection had moved
  //    the version out of the reviewable state, so the mis-click could
  //    never be undone.
  const corrected = await call(a, staffUrl, {
    method: 'POST',
    body: { decision: 'APPROVED', comment: 'E2E corrected verdict' },
  });
  check(
    'a reviewer can correct their own verdict',
    corrected.status === 200,
    `HTTP ${corrected.status} ${JSON.stringify(corrected.body)}`,
  );
  check(
    'the corrected verdict is current',
    corrected.body?.supersededAt === null || corrected.body?.supersededAt === undefined,
    JSON.stringify(corrected.body),
  );

  // 3. A second reviewer in the same tier replaces the current verdict
  //    as well — one current verdict per tier, whoever made it. The
  //    version is IN_REVIEW now, so the second reviewer may act on it.
  const byOther = await call(b, staffUrl, {
    method: 'POST',
    body: { decision: 'REQUEST_CHANGES', comment: 'E2E second reviewer' },
  });
  check(
    'a second reviewer in the same tier can record a verdict',
    byOther.status === 200,
    `HTTP ${byOther.status} ${JSON.stringify(byOther.body)}`,
  );

  // 4. The trail keeps both earlier verdicts, stamped as superseded.
  const trail = await call(a, `/workspaces/${WS}/documents/${DOC}/versions/${VERSION}/reviews`);
  const reviews = Array.isArray(trail.body) ? trail.body : [];
  const superseded = reviews.filter((r) => r.supersededAt);
  const current = reviews.filter((r) => !r.supersededAt);

  check(
    'the trail keeps the superseded verdicts',
    superseded.length >= 2,
    `superseded=${superseded.length} current=${current.length}`,
  );
  check(
    'the trail records when a verdict was replaced',
    superseded.every((r) => !!r.supersededAt),
    JSON.stringify(superseded.map((r) => [r.decision, r.supersededAt])),
  );

  // 5. A COMMENT is a note, not an opinion: it must not replace a
  //    verdict and must not be replaced by one.
  const comment = await call(a, staffUrl, {
    method: 'POST',
    body: { decision: 'COMMENT', comment: 'E2E a note' },
  });
  check(
    'a COMMENT is accepted next to an existing verdict',
    comment.status === 200,
    `HTTP ${comment.status} ${JSON.stringify(comment.body)}`,
  );

  const afterComment = await call(
    a,
    `/workspaces/${WS}/documents/${DOC}/versions/${VERSION}/reviews`,
  );
  const afterReviews = Array.isArray(afterComment.body) ? afterComment.body : [];
  const commentRow = afterReviews.find((r) => r.decision === 'COMMENT');
  const verdictRows = afterReviews.filter(
    (r) => r.decision !== 'COMMENT' && !r.supersededAt,
  );
  check(
    'the COMMENT did not replace the current verdict',
    verdictRows.length === 1,
    `current verdicts=${verdictRows.length}`,
  );
  check(
    'the current verdict is the one the other reviewer recorded',
    verdictRows[0]?.decision === 'REQUEST_CHANGES',
    JSON.stringify(verdictRows[0]),
  );
  check(
    'the COMMENT stays current in the trail',
    !!commentRow && !commentRow.supersededAt,
    JSON.stringify(commentRow),
  );

  // 6. A third verdict in the same tier must not crash the request.
  //    This is the case that used to raise a constraint violation and
  //    answer 500.
  const third = await call(b, staffUrl, {
    method: 'POST',
    body: { decision: 'APPROVED', comment: 'E2E third verdict' },
  });
  check(
    'a third verdict in the same tier is accepted',
    third.status === 200,
    `HTTP ${third.status} ${JSON.stringify(third.body)}`,
  );

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
