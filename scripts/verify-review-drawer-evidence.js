#!/usr/bin/env node
/**
 * Verification for the Manager-facing review drawer.
 *
 * The review drawer used to fetch only the version timeline and the
 * template compliance, so a Manager opening it saw neither the Staff
 * comments nor the grounding evidence — even though both were already
 * stored and both endpoints existed.
 *
 * This script proves the two endpoints the drawer now calls answer with
 * the data the drawer renders:
 *
 *   1. `GET .../versions/{id}/reviews` returns the Staff comments, with
 *      the tier, the decision, the comment text and the reviewer;
 *   2. `GET .../versions/{id}/grounding` answers for a version (an empty
 *      list is a valid answer — the version may have been written by
 *      hand, which is not an error).
 *
 * Usage: node scripts/verify-review-drawer-evidence.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';

const DEVELOPER_ID = '33333333-3333-3333-3333-333333333333';
const STAFF_ID = '22222222-2222-2222-2222-222222222222';
const MANAGER_ID = '11111111-1111-1111-1111-111111111111';

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
const MANAGER = mint(MANAGER_ID, 'puremanager@livingdocs.test', ['MANAGER']);

let passed = 0;
let failed = 0;

function check(label, condition, detail) {
  if (condition) {
    passed++;
    console.log(`  PASS  ${label}`);
  } else {
    failed++;
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

async function findDocument() {
  const list = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/documents`);
  const docs = Array.isArray(list.body) ? list.body : [];
  return docs.find((d) => d.status === 'DRAFT' || d.status === 'REJECTED') ?? docs[0] ?? null;
}

async function findDraftVersion(documentId) {
  const res = await call(DEVELOPER, `/documents/${documentId}/versions`);
  const versions = Array.isArray(res.body) ? res.body : [];
  return versions.find((v) => v.status === 'DRAFT') ?? null;
}

async function main() {
  console.log(`LivingDocs review-drawer evidence verification against ${API_BASE}`);

  const doc = await findDocument();
  if (!doc) {
    console.log('\nNo document available to test with — aborting.');
    process.exit(1);
  }
  console.log(`\nusing document ${doc.id}`);

  let draft = await findDraftVersion(doc.id);
  if (!draft) {
    const edited = await call(
      DEVELOPER,
      `/workspaces/${WORKSPACE_ID}/documents/${doc.id}/versions`,
      {
        method: 'POST',
        body: {
          bodyMarkdown: `# Probe\n\nEvidence probe at ${new Date().toISOString()}\n`,
          changeSummary: `Edited at ${new Date().toISOString()}`,
        },
      },
    );
    if (edited.status >= 400) {
      console.log(`\nCould not create a draft version (${edited.status}) — aborting.`);
      process.exit(1);
    }
    draft = await findDraftVersion(doc.id);
  }
  if (!draft) {
    console.log('\nNo draft version available — aborting.');
    process.exit(1);
  }
  console.log(`using version ${draft.id}`);

  // -------------------------------------------------------------------------
  console.log('\n== the version is submitted for review ==');

  const submitted = await call(
    DEVELOPER,
    `/workspaces/${WORKSPACE_ID}/documents/${doc.id}/versions/${draft.id}/submit-for-review`,
    { method: 'POST' },
  );
  check(
    'the draft is submitted for review',
    submitted.status === 200,
    `got ${submitted.status} ${JSON.stringify(submitted.body)}`,
  );

  // -------------------------------------------------------------------------
  console.log('\n== the Staff tier comments on it ==');

  const staffReview = await call(
    STAFF,
    `/workspaces/${WORKSPACE_ID}/documents/${doc.id}/versions/${draft.id}/staff-review`,
    {
      method: 'POST',
      body: {
        decision: 'COMMENT',
        comment: 'The setup section needs the new flag documented.',
      },
    },
  );
  check(
    'the Staff tier can comment on the version',
    staffReview.status === 200,
    `got ${staffReview.status} ${JSON.stringify(staffReview.body)}`,
  );

  // -------------------------------------------------------------------------
  console.log('\n== the drawer endpoint returns the Staff comments ==');

  const reviews = await call(
    MANAGER,
    `/workspaces/${WORKSPACE_ID}/documents/${doc.id}/versions/${draft.id}/reviews`,
  );
  check(
    'the manager can read the review list',
    reviews.status === 200,
    `got ${reviews.status} ${JSON.stringify(reviews.body)}`,
  );
  check(
    'the review list is an array',
    Array.isArray(reviews.body),
    JSON.stringify(reviews.body),
  );

  const staffEntry = (reviews.body ?? []).find((r) => r.reviewerRole === 'STAFF');
  check('the Staff comment is in the list', Boolean(staffEntry), JSON.stringify(reviews.body));
  check(
    'it carries the decision',
    staffEntry?.decision === 'COMMENT',
    JSON.stringify(staffEntry),
  );
  check(
    'it carries the comment text',
    typeof staffEntry?.comment === 'string' && staffEntry.comment.length > 0,
    JSON.stringify(staffEntry),
  );
  check(
    'it carries the reviewer identity',
    Boolean(staffEntry?.reviewerUserId),
    JSON.stringify(staffEntry),
  );
  check(
    'it carries the reviewer display name',
    'reviewerName' in (staffEntry ?? {}),
    JSON.stringify(staffEntry),
  );
  check(
    'it carries the decision time',
    Boolean(staffEntry?.decidedAt),
    JSON.stringify(staffEntry),
  );

  // -------------------------------------------------------------------------
  console.log('\n== the grounding endpoint answers ==');

  const grounding = await call(
    MANAGER,
    `/workspaces/${WORKSPACE_ID}/documents/${doc.id}/versions/${draft.id}/grounding`,
  );
  check(
    'the manager can read the grounding evidence',
    grounding.status === 200,
    `got ${grounding.status} ${JSON.stringify(grounding.body)}`,
  );
  check(
    'it carries a sources array',
    Array.isArray(grounding.body?.sources),
    JSON.stringify(grounding.body),
  );
  check(
    'every source has the shape the drawer renders',
    (grounding.body?.sources ?? []).every(
      (s) =>
        // The AI service names the entity `qualified_name`, and the
        // backend stores that JSON verbatim — so the endpoint answers in
        // snake_case. The frontend normalises it to `qualifiedName`.
        typeof s.qualified_name === 'string' &&
        typeof s.kind === 'string' &&
        typeof s.file === 'string' &&
        Array.isArray(s.lines) &&
        typeof s.signature === 'string',
    ),
    JSON.stringify(grounding.body?.sources),
  );

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
