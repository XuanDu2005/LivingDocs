#!/usr/bin/env node
/**
 * LivingDocs — bulk ("Generate All Docs") generation check.
 *
 * Verifies that the asynchronous bulk generation flow works end to end:
 *   - POST generate-all answers 202 with a job id (no timeout)
 *   - the job reaches a terminal state while the client polls
 *   - progress counters advance and the per-entity outcome is returned
 *
 * Usage:
 *   node scripts/bulk-generation-check.js [workspaceId] [repositoryId]
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const USER_ID = process.env.USER_ID || 'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de';
const EMAIL = process.env.EMAIL || 'dxuan8461@gmail.com';
const WS = process.argv[2] || '43a3821d-3071-4242-add5-25c8dbe3d88e';
const REPO = process.argv[3] || '903b25ea-db50-4b48-830b-3422c1bd792a';

const b64url = (buf) =>
  Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

function token() {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(
    JSON.stringify({ sub: USER_ID, email: EMAIL, iat: now, exp: now + 3600, roles: ['MANAGER'] }),
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

async function call(method, path, body) {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token()}`,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  return { status: res.status, data };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  console.log('\nBulk generation check');
  console.log(`  API      ${API_BASE}`);
  console.log(`  workspace ${WS}`);
  console.log(`  repository ${REPO}\n`);

  // --- 1. enqueue ------------------------------------------------------
  const queued = await call(
    'POST',
    `/workspaces/${WS}/repositories/${REPO}/generate-all?template=MODULE_GUIDE`,
    {},
  );
  check('generate-all answers 202', queued.status === 202, `got ${queued.status}`);
  check(
    'response carries a job id',
    !!(queued.data && queued.data.jobId),
    JSON.stringify(queued.data),
  );

  if (!queued.data || !queued.data.jobId) {
    console.log('\nCannot continue without a job id.');
    process.exit(1);
  }

  const jobId = queued.data.jobId;
  console.log(`\n  job ${jobId} — total entities: ${queued.data.totalEntities}`);

  // --- 2. poll ---------------------------------------------------------
  const terminal = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED']);
  let job = null;
  const started = Date.now();
  let sawProgress = false;

  for (let i = 0; i < 180; i++) {
    await sleep(2000);
    const res = await call('GET', `/workspaces/${WS}/bulk-generation-jobs/${jobId}`);
    if (res.status !== 200) {
      check('poll endpoint answers 200', false, `got ${res.status}`);
      break;
    }
    job = res.data;
    process.stdout.write(
      `\r  status=${job.status} processed=${job.processedEntities}/${job.totalEntities} ` +
        `generated=${job.generated} skipped=${job.skipped} failed=${job.failed}   `,
    );
    if (job.processedEntities > 0) sawProgress = true;
    if (terminal.has(job.status)) break;
  }
  console.log('\n');
  console.log(`  elapsed ${Math.round((Date.now() - started) / 1000)}s`);

  check('job reached a terminal state', !!(job && terminal.has(job.status)),
    job ? job.status : 'no job');
  check('progress counters were reported', sawProgress);
  check('processed count matches the outcome',
    !!(job && job.processedEntities === job.totalEntities),
    job ? `${job.processedEntities}/${job.totalEntities}` : '');
  check('job result carries the per-entity documents',
    !!(job && Array.isArray(job.documents)),
    job ? JSON.stringify(job.documents) : '');
  check('no entity was silently dropped',
    !!(job && job.generated + job.skipped + job.failed === job.totalEntities),
    job ? `gen=${job.generated} skip=${job.skipped} fail=${job.failed} total=${job.totalEntities}` : '');

  console.log(`\n  generated=${job?.generated} skipped=${job?.skipped} failed=${job?.failed}`);
  if (job?.errors?.length) {
    console.log(`  errors: ${job.errors.slice(0, 5).join(' | ')}`);
  }

  console.log(`\n${passed} passed, ${failed} failed\n`);
  if (failures.length) {
    failures.forEach((f) => console.log(`  - ${f}`));
    process.exit(1);
  }
})();
