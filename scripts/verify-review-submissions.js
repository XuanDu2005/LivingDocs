#!/usr/bin/env node
/**
 * Verification for the author-side review queue.
 *
 * The review queue page used to render three tabs (Staff / Manager /
 * Publish), all of them gated behind reviewing roles. A DEVELOPER — whose
 * entire job is to author drafts and hand them over — therefore saw an
 * empty page: no way to find out what happened to the version they
 * submitted.
 *
 * This script proves the new behaviour end to end:
 *
 *   1. a DEVELOPER submits a draft for review;
 *   2. the version shows up in the DEVELOPER's "my submissions" list;
 *   3. a STAFF reviewer rejects it;
 *   4. the DEVELOPER sees the rejection, the reviewer's comment and the
 *      reviewer who made the call;
 *   5. a DEVELOPER still cannot *act* on the review queues.
 *
 * Usage: node scripts/verify-review-submissions.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';

const DEVELOPER_ID = '33333333-3333-3333-3333-333333333333';
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

const DEVELOPER = mint(DEVELOPER_ID, 'puredev@livingdocs.test', ['DEVELOPER']);
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

/** A document id the test can safely write to. */
let docId = null;

async function findOrCreateDocument() {
  const list = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/documents`);
  const docs = Array.isArray(list.body) ? list.body : [];
  const reusable = docs.find((d) => d.status === 'DRAFT' || d.status === 'REJECTED');
  if (reusable) {
    return reusable.id;
  }
  const created = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/documents`, {
    method: 'POST',
    body: {
      title: `Review submission probe ${Date.now()}`,
      slug: `review-probe-${Date.now()}`,
      docType: 'README',
      summary: 'Scratch document used by verify-review-submissions.js',
      initialBody: '# Probe\n\nScratch document.\n',
    },
  });
  return created.body?.id ?? null;
}

async function findDraftVersion(documentId) {
  const res = await call(DEVELOPER, `/documents/${documentId}/versions`);
  const versions = Array.isArray(res.body) ? res.body : [];
  const draft = versions.find((v) => v.status === 'DRAFT');
  return draft ?? null;
}

async function main() {
  console.log(`LivingDocs review-submission verification against ${API_BASE}`);

  docId = await findOrCreateDocument();
  if (!docId) {
    console.log('\nCould not obtain a document to test with — aborting.');
    process.exit(1);
  }
  console.log(`\nusing document ${docId}`);

  // -------------------------------------------------------------------------
  console.log('\n== author submits a draft ==');

  let draft = await findDraftVersion(docId);
  if (!draft) {
    // Create one by appending a human-edited version.
    const edited = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions`, {
      method: 'POST',
      body: {
        bodyMarkdown: `# Probe\n\nSubmitted at ${new Date().toISOString()}\n`,
        changeSummary: `Edited at ${new Date().toISOString()}`,
      },
    });
    if (edited.status >= 400) {
      console.log(`\nCould not create a draft version (${edited.status}) — aborting.`);
      console.log(JSON.stringify(edited.body));
      process.exit(1);
    }
    draft = await findDraftVersion(docId);
  }
  if (!draft) {
    console.log('\nNo draft version available — aborting.');
    process.exit(1);
  }
  check('the developer has a draft version', Boolean(draft?.id));

  const submitted = await call(
    DEVELOPER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/submit-for-review`,
    { method: 'POST' },
  );
  check(
    'the developer can submit a draft for review',
    submitted.status === 200,
    `got ${submitted.status} ${JSON.stringify(submitted.body)}`,
  );
  check(
    'the submitted version becomes PENDING',
    submitted.body?.status === 'PENDING',
    JSON.stringify(submitted.body),
  );

  // -------------------------------------------------------------------------
  console.log('\n== the submission is visible to its author ==');

  const mine = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/reviews/my-submissions`);
  check('my-submissions returns 200 for a developer', mine.status === 200, `got ${mine.status}`);
  check(
    'my-submissions is an array',
    Array.isArray(mine.body),
    JSON.stringify(mine.body),
  );

  const entry = (mine.body ?? []).find((s) => s.versionId === draft.id);
  check('the submitted version is listed', Boolean(entry), JSON.stringify(mine.body));
  check(
    'it starts in the WAITING_STAFF stage',
    entry?.stage === 'WAITING_STAFF',
    JSON.stringify(entry),
  );
  check('it carries the document title', typeof entry?.documentTitle === 'string');
  check('it carries the version number', entry?.versionNumber === draft.versionNumber);
  check('it records when it was submitted', Boolean(entry?.submittedAt));

  const queue = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/reviews/pending-on-me`);
  check('pending-on-me still returns 200', queue.status === 200);
  check(
    'pending-on-me carries the mySubmissions bucket',
    Array.isArray(queue.body?.mySubmissions),
    JSON.stringify(queue.body && Object.keys(queue.body)),
  );
  check(
    'the bucket contains the submission',
    (queue.body?.mySubmissions ?? []).some((s) => s.versionId === draft.id),
  );
  check(
    'the developer still has no reviewing queue',
    (queue.body?.staffQueue ?? []).length === 0 &&
      (queue.body?.managerQueue ?? []).length === 0 &&
      (queue.body?.publishQueue ?? []).length === 0,
    JSON.stringify(queue.body),
  );

  // -------------------------------------------------------------------------
  console.log('\n== a reviewer rejects it ==');

  const staffReview = await call(
    STAFF,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/staff-review`,
    { method: 'POST', body: { decision: 'REQUEST_CHANGES', comment: 'Please clarify the setup section.' } },
  );
  check(
    'a staff reviewer can act on the version',
    staffReview.status === 200,
    `got ${staffReview.status} ${JSON.stringify(staffReview.body)}`,
  );

  // -------------------------------------------------------------------------
  console.log('\n== the author sees the verdict ==');

  const afterReview = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/reviews/my-submissions`);
  const reviewed = (afterReview.body ?? []).find((s) => s.versionId === draft.id);
  check('the submission is still listed', Boolean(reviewed));
  check(
    'the stage becomes CHANGES_REQUESTED',
    reviewed?.stage === 'CHANGES_REQUESTED',
    JSON.stringify(reviewed),
  );
  check(
    'the decision is REQUEST_CHANGES',
    reviewed?.latestDecision === 'REQUEST_CHANGES',
    JSON.stringify(reviewed),
  );
  check(
    "the reviewer's comment is shown",
    reviewed?.latestComment === 'Please clarify the setup section.',
    JSON.stringify(reviewed),
  );
  check(
    'the reviewer role is shown',
    typeof reviewed?.latestReviewerRole === 'string',
    JSON.stringify(reviewed),
  );
  check(
    'the reviewer name is shown',
    typeof reviewed?.latestReviewerName === 'string' && reviewed.latestReviewerName.length > 0,
    JSON.stringify(reviewed),
  );
  check(
    'the decision time is shown',
    Boolean(reviewed?.latestDecidedAt),
    JSON.stringify(reviewed),
  );

  // -------------------------------------------------------------------------
  console.log('\n== the author still cannot act on the queues ==');

  const staffQueue = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/reviews/staff-queue`);
  check('the developer is refused the staff queue', staffQueue.status === 403);

  const managerQueue = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/reviews/manager-queue`);
  check('the developer is refused the manager queue', managerQueue.status === 403);

  const publishQueue = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/reviews/publish-queue`);
  check('the developer is refused the publish queue', publishQueue.status === 403);

  // -------------------------------------------------------------------------
  console.log('\n== the submitter is notified ==');

  const notifications = await call(DEVELOPER, '/notifications');
  const decisionNotifications = (notifications.body ?? []).filter(
    (n) => n.kind === 'review.decided',
  );
  check(
    'the author received a "review decided" notification',
    decisionNotifications.length > 0,
    `found ${decisionNotifications.length}`,
  );
  check(
    'the notification names the document',
    decisionNotifications.some((n) => (n.title ?? '').includes('Review decision')),
    JSON.stringify(decisionNotifications.slice(0, 2)),
  );
  check(
    'the notification carries the comment',
    decisionNotifications.some((n) => (n.body ?? '').includes('Please clarify the setup section.')),
    JSON.stringify(decisionNotifications.slice(0, 2)),
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
