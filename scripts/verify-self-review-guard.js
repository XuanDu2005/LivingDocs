#!/usr/bin/env node
/**
 * LivingDocs — end-to-end check of the self-review guard.
 *
 * The bug this proves the absence of: a Manager created a document,
 * submitted it for review, and then saw it in the review queue. Clicking
 * it produced "You cannot review a version you submitted yourself" —
 * the queue offered an action the API always refuses.
 *
 * The checks:
 *
 *   1. the viewer's own submission is not in their review queue;
 *   2. it is still visible under "My submissions" — nothing is lost;
 *   3. the verdict is still refused on the API (the guard holds);
 *   4. somebody else's submission IS in the queue, and they can act on it;
 *   5. the approval tier has the same guard (a Manager may not approve
 *      their own submission).
 *
 * Usage: node scripts/verify-self-review-guard.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';

// The author: a MANAGER who also holds the review and approve
// permissions, so they see both tiers of the queue.
const AUTHOR_ID = 'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de';
// A second reviewer, so the workspace is not in the single-reviewer
// case (where the guard deliberately yields).
const REVIEWER_ID = '11111111-1111-1111-1111-111111111111';

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

const AUTHOR = mint(AUTHOR_ID, 'author@example.com', ['MANAGER', 'DEVELOPER']);
const REVIEWER = mint(REVIEWER_ID, 'reviewer@example.com', ['TECHNICAL_LEAD']);

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
  console.log('\n== The self-review guard ==');

  // The author creates a document and submits it for review.
  const created = await call(AUTHOR, `/workspaces/${WORKSPACE_ID}/documents`, {
    method: 'POST',
    body: {
      title: `Self-review probe ${Date.now()}`,
      slug: `self-review-probe-${Date.now()}`,
      docType: 'README',
      summary: 'Scratch document used by verify-self-review-guard.js',
      initialBody: '# Probe\n\nScratch document.\n',
    },
  });
  const docId = created.body?.id;
  check('the author created a document', !!docId, JSON.stringify(created.body));
  if (!docId) {
    finish();
    return;
  }

  const versions = await call(AUTHOR, `/documents/${docId}/versions`);
  const draft = (Array.isArray(versions.body) ? versions.body : []).find(
    (v) => v.status === 'DRAFT',
  );
  check('the document has a draft version', !!draft, JSON.stringify(versions.body));

  const submitted = await call(
    AUTHOR,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/submit-for-review`,
    { method: 'POST' },
  );
  check(
    'the author submitted the version for review',
    submitted.status === 200,
    `HTTP ${submitted.status} ${JSON.stringify(submitted.body)}`,
  );

  // 1. The author's own submission must not be in their review queue.
  const authorQueue = await call(
    AUTHOR,
    `/workspaces/${WORKSPACE_ID}/reviews/pending-on-me`,
  );
  const staffQueue = authorQueue.body?.staffQueue ?? [];
  const managerQueue = authorQueue.body?.managerQueue ?? [];
  const inStaff = staffQueue.some((i) => i.versionId === draft.id);
  const inManager = managerQueue.some((i) => i.versionId === draft.id);

  check(
    "the author's own submission is not in their Staff review queue",
    !inStaff,
    `staffQueue=${JSON.stringify(staffQueue.map((i) => i.versionId))}`,
  );
  check(
    "the author's own submission is not in their Manager queue",
    !inManager,
    `managerQueue=${JSON.stringify(managerQueue.map((i) => i.versionId))}`,
  );

  // 2. It must still be visible under "My submissions" — the author
  //    needs to watch their document move through the workflow.
  const mine = authorQueue.body?.mySubmissions ?? [];
  check(
    "the author's own submission is still listed under My submissions",
    mine.some((i) => i.versionId === draft.id),
    JSON.stringify(mine.map((i) => i.versionId)),
  );

  // 3. The verdict is still refused — the guard holds.
  const verdict = await call(
    AUTHOR,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/staff-review`,
    { method: 'POST', body: { decision: 'APPROVED', comment: 'self' } },
  );
  check(
    'the self-review verdict is refused',
    verdict.status === 403,
    `HTTP ${verdict.status} ${JSON.stringify(verdict.body)}`,
  );

  // 4. Somebody else's submission IS in the queue, and they can act on it.
  //    The reviewer reviews the AUTHOR's submission — the one the
  //    reviewer is allowed to act on.
  const reviewerQueue = await call(
    REVIEWER,
    `/workspaces/${WORKSPACE_ID}/reviews/pending-on-me`,
  );
  const reviewerStaff = reviewerQueue.body?.staffQueue ?? [];
  check(
    "somebody else's submission IS in the reviewer's queue",
    reviewerStaff.some((i) => i.versionId === draft.id),
    `staffQueue=${JSON.stringify(reviewerStaff.map((i) => i.versionId))}`,
  );

  // And they can act on it.
  const otherVerdict = await call(
    REVIEWER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/staff-review`,
    { method: 'POST', body: { decision: 'APPROVED', comment: 'looks good' } },
  );
  check(
    "the reviewer can review somebody else's submission",
    otherVerdict.status === 200,
    `HTTP ${otherVerdict.status} ${JSON.stringify(otherVerdict.body)}`,
  );

  // 5. The approval tier has the same guard.
  const managerVerdict = await call(
    AUTHOR,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/manager-decision`,
    { method: 'POST', body: { decision: 'APPROVED', comment: 'self' } },
  );
  check(
    'the self-approval is refused as well',
    managerVerdict.status === 403,
    `HTTP ${managerVerdict.status} ${JSON.stringify(managerVerdict.body)}`,
  );

  finish();
}

function finish() {
  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) {
    console.log('\nFailures:');
    failures.forEach((f) => console.log(`  - ${f}`));
    process.exit(1);
  }
  console.log('\nAll checks passed.');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
