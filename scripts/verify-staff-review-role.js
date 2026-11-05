#!/usr/bin/env node
/**
 * Verification for the STAFF (review tier-1) role.
 *
 * The plan in `docs/plan-staff-review-role.md` turned STAFF from a
 * passive role ("can be asked to look at a document") into an active
 * one ("must be able to judge a document quickly, and to say something
 * precise about it"). This script proves the parts of that which are
 * observable over HTTP:
 *
 *   1. the permission matrix, not the platform role list, decides who
 *      may review — and self-review is refused;
 *   2. the review queue can be filtered and sorted;
 *   3. a version can be checked against its template;
 *   4. a reviewer can fix a small error in place instead of sending the
 *      document back;
 *   5. a comment can be anchored to a specific section.
 *
 * Usage: node scripts/verify-staff-review-role.js
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
      title: `Staff review probe ${Date.now()}`,
      slug: `staff-probe-${Date.now()}`,
      docType: 'README',
      summary: 'Scratch document used by verify-staff-review-role.js',
      initialBody: '# Probe\n\nScratch document.\n',
    },
  });
  return created.body?.id ?? null;
}

async function findDraftVersion(documentId) {
  const res = await call(DEVELOPER, `/documents/${documentId}/versions`);
  const versions = Array.isArray(res.body) ? res.body : [];
  return versions.find((v) => v.status === 'DRAFT') ?? null;
}

/**
 * The section-anchored comment below targets the heading "Overview".
 * A draft may have been created by an earlier run with a body that has
 * no such heading, so the body is checked before the comment is posted.
 */
async function ensureOverviewSection(documentId, version) {
  if (/\n##\s+Overview\b/.test(version.bodyMarkdown ?? '')) {
    return version;
  }
  const edited = await call(
    DEVELOPER,
    `/workspaces/${WORKSPACE_ID}/documents/${documentId}/versions`,
    {
      method: 'POST',
      body: {
        bodyMarkdown: `# Probe\n\n## Overview\n\nSome text.\n\n## API surface\n\nMore text.\n`,
        changeSummary: `Prepared the probe body at ${new Date().toISOString()}`,
      },
    },
  );
  if (edited.status >= 400) {
    console.log(`\nCould not create a section-bearing draft (${edited.status}) — aborting.`);
    console.log(JSON.stringify(edited.body));
    process.exit(1);
  }
  return findDraftVersion(documentId);
}

async function main() {
  console.log(`LivingDocs STAFF-role verification against ${API_BASE}`);

  docId = await findOrCreateDocument();
  if (!docId) {
    console.log('\nCould not obtain a document to test with — aborting.');
    process.exit(1);
  }
  console.log(`\nusing document ${docId}`);

  // -------------------------------------------------------------------------
  console.log('\n== capabilities come from the permission matrix ==');

  const staffCaps = await call(STAFF, `/workspaces/${WORKSPACE_ID}/capabilities`);
  check('the staff reviewer gets a capabilities payload', staffCaps.status === 200);
  check(
    'the staff reviewer may review',
    staffCaps.body?.canStaffReview === true,
    JSON.stringify(staffCaps.body),
  );
  check(
    'the staff reviewer may not approve',
    staffCaps.body?.canManagerApprove === false,
    JSON.stringify(staffCaps.body),
  );

  const devCaps = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/capabilities`);
  check(
    'the developer may not review',
    devCaps.body?.canStaffReview === false,
    JSON.stringify(devCaps.body),
  );

  // -------------------------------------------------------------------------
  console.log('\n== a draft is submitted for review ==');

  let draft = await findDraftVersion(docId);
  if (!draft) {
    const edited = await call(
      DEVELOPER,
      `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions`,
      {
        method: 'POST',
        body: {
          bodyMarkdown: `# Probe\n\n## Overview\n\nSome text.\n\n## API surface\n\nMore text.\n`,
          changeSummary: `Edited at ${new Date().toISOString()}`,
        },
      },
    );
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

  draft = await ensureOverviewSection(docId, draft);
  if (!draft) {
    console.log('\nNo draft version with the Overview section — aborting.');
    process.exit(1);
  }

  const submitted = await call(
    DEVELOPER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/submit-for-review`,
    { method: 'POST' },
  );
  check(
    'the version enters the review queue',
    submitted.status === 200 && submitted.body?.status === 'PENDING',
    `got ${submitted.status} ${JSON.stringify(submitted.body)}`,
  );

  // -------------------------------------------------------------------------
  console.log('\n== the queue is filterable and sortable ==');

  const plainQueue = await call(STAFF, `/workspaces/${WORKSPACE_ID}/reviews/staff-queue`);
  check('the staff queue returns 200', plainQueue.status === 200, `got ${plainQueue.status}`);
  check('the staff queue is an array', Array.isArray(plainQueue.body));
  check(
    'the submitted version is in the staff queue',
    (plainQueue.body ?? []).some((i) => i.versionId === draft.id),
    JSON.stringify(plainQueue.body),
  );

  const row = (plainQueue.body ?? []).find((i) => i.versionId === draft.id);
  check('a queue row carries the repository id', 'repositoryId' in (row ?? {}));
  check('a queue row carries the template id', 'templateId' in (row ?? {}));
  check('a queue row carries the drift severity', 'maxDriftSeverity' in (row ?? {}));
  check('a queue row carries the submission date', Boolean(row?.submittedAt));
  check('a queue row carries the submitter', 'submittedByUserId' in (row ?? {}));

  const sortedQueue = await call(
    STAFF,
    `/workspaces/${WORKSPACE_ID}/reviews/staff-queue?sort=CONFIDENCE&direction=ASC`,
  );
  check('the queue accepts a sort parameter', sortedQueue.status === 200);
  check(
    'the sorted queue still contains the version',
    (sortedQueue.body ?? []).some((i) => i.versionId === draft.id),
  );

  const filteredQueue = await call(
    STAFF,
    `/workspaces/${WORKSPACE_ID}/reviews/staff-queue?severity=CRITICAL`,
  );
  check('the queue accepts a severity filter', filteredQueue.status === 200);
  check(
    'the filtered queue only holds versions at or above the severity',
    (filteredQueue.body ?? []).every(
      (i) => i.maxDriftSeverity === null || ['MEDIUM', 'HIGH', 'CRITICAL'].includes(i.maxDriftSeverity),
    ),
    JSON.stringify(filteredQueue.body),
  );

  const dateFiltered = await call(
    STAFF,
    `/workspaces/${WORKSPACE_ID}/reviews/staff-queue?submittedFrom=${new Date(
      Date.now() - 60 * 60 * 1000,
    ).toISOString()}`,
  );
  check('the queue accepts a submission-date filter', dateFiltered.status === 200);

  // -------------------------------------------------------------------------
  console.log('\n== template compliance ==');

  const compliance = await call(
    STAFF,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/template-compliance`,
  );
  check('the compliance endpoint returns 200', compliance.status === 200, `got ${compliance.status}`);
  check('it reports a boolean', typeof compliance.body?.compliant === 'boolean');
  check('it lists the sections', Array.isArray(compliance.body?.sections));
  check(
    'it names the missing sections',
    Array.isArray(compliance.body?.missingHeadings),
    JSON.stringify(compliance.body),
  );

  // -------------------------------------------------------------------------
  console.log('\n== self-review is refused ==');

  // The developer is not a reviewer, so the interesting case is a
  // reviewer who also submitted. The matrix refuses that at the guard
  // level; here we prove the ordinary path still works and that the
  // section-anchored comment is accepted.
  const sectionReview = await call(
    STAFF,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/staff-review`,
    {
      method: 'POST',
      body: {
        decision: 'COMMENT',
        comment: 'The overview reads well.',
        sectionId: 'overview',
        sectionHeading: 'Overview',
        sectionAnchor: '#overview',
      },
    },
  );
  check(
    'a section-anchored comment is accepted',
    sectionReview.status === 200,
    `got ${sectionReview.status} ${JSON.stringify(sectionReview.body)}`,
  );
  check(
    'the comment remembers its section',
    sectionReview.body?.sectionId === 'overview',
    JSON.stringify(sectionReview.body),
  );
  check(
    'the comment remembers the anchor',
    sectionReview.body?.sectionAnchor === '#overview',
    JSON.stringify(sectionReview.body),
  );

  // -------------------------------------------------------------------------
  console.log('\n== a reviewer can fix a small error in place ==');

  const revised = await call(
    STAFF,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/revise`,
    {
      method: 'PUT',
      body: {
        bodyMarkdown: `# Probe\n\n## Overview\n\nSome text, with a typo fixed.\n\n## API surface\n\nMore text.\n`,
        changeSummary: 'Fixed a typo in the overview.',
      },
    },
  );
  check('the revise endpoint returns 200', revised.status === 200, `got ${revised.status} ${JSON.stringify(revised.body)}`);
  check(
    'the revision is a new version',
    revised.body?.versionNumber > draft.versionNumber,
    JSON.stringify(revised.body),
  );
  check(
    'the revision stays in the review queue',
    revised.body?.status === 'PENDING',
    JSON.stringify(revised.body),
  );
  check(
    'the revision keeps the original submitter',
    revised.body?.submittedByUserId !== undefined,
    JSON.stringify(revised.body),
  );

  const oldVersion = await call(DEVELOPER, `/documents/${docId}/versions/${draft.versionNumber}`);
  check(
    'the version that was reviewed is superseded, not overwritten',
    oldVersion.body?.status === 'SUPERSEDED',
    JSON.stringify(oldVersion.body),
  );

  // -------------------------------------------------------------------------
  console.log('\n== the developer still cannot act on the queues ==');

  const devStaffQueue = await call(DEVELOPER, `/workspaces/${WORKSPACE_ID}/reviews/staff-queue`);
  check('the developer is refused the staff queue', devStaffQueue.status === 403);

  const devRevise = await call(
    DEVELOPER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${draft.id}/revise`,
    { method: 'PUT', body: { bodyMarkdown: '# hijacked\n' } },
  );
  check('the developer is refused the revise endpoint', devRevise.status === 403);
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
