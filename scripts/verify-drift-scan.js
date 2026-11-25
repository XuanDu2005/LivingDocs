#!/usr/bin/env node
/**
 * Verify that the repository-wide drift scan finds real drift.
 *
 * The scan used to answer "the code has not changed since the document
 * was written" for every document of every repository, because the
 * GitHub compare response was read as if it were a bare array instead
 * of the object it actually is. The scan looked healthy — it reported
 * SUCCEEDED — and found nothing at all.
 *
 * The check that matters here is the end-to-end one: run the scan
 * against a repository whose code has demonstrably moved on, and
 * assert that the scan reports documents with drift instead of
 * skipping every one of them.
 *
 * Usage:
 *   node scripts/verify-drift-scan.js
 *
 * Environment overrides:
 *   API_BASE        default http://localhost:8080/api/v1
 *   JWT_SECRET      default matches .env.example
 *   WORKSPACE_ID    the workspace to operate in
 *   REPOSITORY_ID   the repository to scan
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID =
  process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';
const REPOSITORY_ID =
  process.env.REPOSITORY_ID || '903b25ea-db50-4b48-830b-3422c1bd792a';

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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The workspace MANAGER of the demo repository's workspace. The scan is
// a capability, not a plain member action, so the account has to hold
// the right roles.
const MANAGER = mint(
  'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de',
  'dxuan8461@gmail.com',
  ['MANAGER'],
);

async function main() {
  await verifyDriftScan();
  await verifyDocSync();

  console.log(
    `\n${failed === 0 ? 'ALL CHECKS PASSED' : 'CHECKS FAILED'} — ${passed} passed, ${failed} failed`,
  );
  if (failures.length) {
    console.log('\nFailures:');
    for (const f of failures) console.log(`  - ${f}`);
  }
  process.exit(failed === 0 ? 0 : 1);
}

async function verifyDriftScan() {
  console.log('== 1. queue a repository-wide drift scan ==');

  const queued = await call(
    MANAGER,
    `/workspaces/${WORKSPACE_ID}/repositories/${REPOSITORY_ID}/drift-scan`,
    { method: 'POST' },
  );
  check(
    'the scan is accepted',
    queued.status === 202,
    `status ${queued.status}: ${JSON.stringify(queued.body).slice(0, 300)}`,
  );
  if (queued.status !== 202) {
    return;
  }
  const jobId = queued.body.jobId;

  console.log('\n== 2. wait for the scan to finish ==');

  // The scan makes one LLM call per document, so it takes minutes.
  // Poll generously — quitting early would read a half-finished job
  // as a finished one.
  let job = null;
  for (let i = 0; i < 450; i++) {
    const polled = await call(
      MANAGER,
      `/workspaces/${WORKSPACE_ID}/repository-drift-scan-jobs/${jobId}`,
    );
    if (polled.status !== 200) {
      await sleep(2000);
      continue;
    }
    job = polled.body;
    if (['SUCCEEDED', 'PARTIAL', 'FAILED'].includes(job.status)) {
      break;
    }
    await sleep(2000);
  }

  check(
    'the scan reached a terminal state',
    !!job && ['SUCCEEDED', 'PARTIAL', 'FAILED'].includes(job.status),
    `the job was still ${job ? job.status : 'unknown'} after the poll window`,
  );
  if (!job) return;

  console.log(
    `  job ${job.jobId}: status=${job.status} scanned=${job.scannedDocuments} ` +
      `withDrift=${job.documentsWithDrift} alerts=${job.alertsCreated} ` +
      `failed=${job.failedDocuments}`,
  );

  check(
    'the scan did not fail',
    job.status !== 'FAILED',
    job.errorMessage || `status ${job.status}`,
  );

  const documents = job.documents || [];
  check('the scan looked at documents', documents.length > 0, 'no documents were scanned');

  // --- the actual regression ------------------------------------------------
  //
  // Every document answering "the code has not changed since the
  // document was written" is the symptom of the compare-response bug:
  // the changed-file list came back empty, so the resolver could not
  // tell "unchanged" from "GitHub answered with something we did not
  // understand".
  const allSkippedAsUnchanged = documents.filter(
    (d) =>
      d.status === 'SKIPPED' &&
      typeof d.reason === 'string' &&
      d.reason.includes('has not changed'),
  );

  check(
    'not every document was skipped as "the code has not changed"',
    allSkippedAsUnchanged.length < documents.length,
    `${allSkippedAsUnchanged.length}/${documents.length} documents were skipped that way`,
  );

  const compared = documents.filter((d) => d.status === 'SCANNED');
  check(
    'at least one document was actually compared',
    compared.length > 0,
    `no document reached the comparison stage (reasons: ${JSON.stringify(
      documents.map((d) => d.reason).filter(Boolean).slice(0, 5),
    )})`,
  );

  check(
    'the scan found drift or alerts',
    (job.documentsWithDrift || 0) > 0 || (job.alertsCreated || 0) > 0,
    `withDrift=${job.documentsWithDrift} alerts=${job.alertsCreated}`,
  );

  console.log('\n== 3. the drift alerts are visible ==');

  const alerts = await call(MANAGER, `/workspaces/${WORKSPACE_ID}/drift-alerts`);
  check(
    'the drift alerts can be listed',
    alerts.status === 200 && Array.isArray(alerts.body),
    `status ${alerts.status}`,
  );
  if (alerts.status === 200 && Array.isArray(alerts.body)) {
    console.log(`  ${alerts.body.length} drift alert(s) in the workspace`);
    for (const alert of alerts.body.slice(0, 5)) {
      console.log(`   - [${alert.severity}] ${alert.title}`);
    }
  }
}

// ---------------------------------------------------------------------------
// documentation sync
// ---------------------------------------------------------------------------

/**
 * The documentation sync walks the repository's Markdown files and
 * compares each of them against the code. It used to find nothing,
 * because it asked GitHub for the file tree with the caller's own
 * OAuth token — and that token had expired, so GitHub answered 401
 * and the scanner swallowed the error into "zero files".
 *
 * The check: the sync must actually see the repository's documentation
 * files. A sync that reports zero files has not run.
 */
async function verifyDocSync() {
  console.log('\n== 4. the documentation sync sees the documentation files ==');

  const queued = await call(
    MANAGER,
    `/workspaces/${WORKSPACE_ID}/repositories/${REPOSITORY_ID}/scan-docs`,
    { method: 'POST' },
  );
  check(
    'the sync is accepted',
    queued.status === 202,
    `status ${queued.status}: ${JSON.stringify(queued.body).slice(0, 300)}`,
  );
  if (queued.status !== 202) return;

  const jobId = queued.body.jobId;

  let job = null;
  for (let i = 0; i < 300; i++) {
    const polled = await call(
      MANAGER,
      `/workspaces/${WORKSPACE_ID}/doc-sync-jobs/${jobId}`,
    );
    if (polled.status !== 200) {
      await sleep(2000);
      continue;
    }
    job = polled.body;
    if (['SUCCEEDED', 'PARTIAL', 'FAILED'].includes(job.status)) {
      break;
    }
    await sleep(2000);
  }

  check(
    'the sync reached a terminal state',
    !!job && ['SUCCEEDED', 'PARTIAL', 'FAILED'].includes(job.status),
    `the job was still ${job ? job.status : 'unknown'} after the poll window`,
  );
  if (!job) return;

  console.log(
    `  job ${job.jobId}: status=${job.status} total=${job.totalFiles} ` +
      `scanned=${job.scannedFiles} drifted=${job.driftedFiles} ` +
      `queued=${job.queuedProposals} skipped=${job.skippedProposals} ` +
      `failed=${job.failedFiles}`,
  );

  check(
    'the sync did not fail',
    job.status !== 'FAILED',
    job.errorMessage || `status ${job.status}`,
  );

  check(
    'the sync saw the documentation files',
    (job.totalFiles || 0) > 0,
    `the sync reported ${job.totalFiles} files — it found no documentation to compare`,
  );

  check(
    'the sync actually scanned files',
    (job.scannedFiles || 0) > 0,
    `the sync scanned ${job.scannedFiles} files`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
