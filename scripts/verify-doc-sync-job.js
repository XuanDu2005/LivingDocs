#!/usr/bin/env node
/**
 * Verify the repository-wide documentation sync.
 *
 * The behaviour under test:
 *   - POST .../scan-docs answers 202 with a job id, immediately — the
 *     sync must not hold the HTTP request open while the AI works;
 *   - the job is then observable through GET .../doc-sync-jobs/{jobId},
 *     and it reaches a terminal state;
 *   - GET .../doc-sync-jobs lists the run for the workspace.
 *
 * The old flow did all of this work inside a single request, so a
 * repository with a few drifted files always ended in a browser-side
 * timeout while the server kept spending the LLM budget.
 *
 * Usage:
 *   node scripts/verify-doc-sync-job.js
 */
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

/**
 * Read a variable from the repository's .env file.
 *
 * The script has to run against the same secret the backend uses, and
 * that secret lives in .env — not in the environment of whoever runs
 * the script. Reading it here keeps the secret out of the command line
 * and out of the shell history.
 */
function fromEnvFile(name) {
  const file = path.join(__dirname, '..', '.env');
  if (!fs.existsSync(file)) return undefined;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z0-9_]+)\s*=\s*(.*)\s*$/);
    if (!match || match[1] !== name) continue;
    return match[2].replace(/^["']|["']$/g, '');
  }
  return undefined;
}

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.APP_JWT_SECRET ||
  fromEnvFile('APP_JWT_SECRET') ||
  process.env.JWT_SECRET ||
  'change-me-please-this-is-a-development-only-secret-32b+';

const WS = '43a3821d-3071-4242-add5-25c8dbe3d88e';
const REPO = '903b25ea-db50-4b48-830b-3422c1bd792a';
const MANAGER = {
  id: 'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de',
  email: 'dxuan8461@gmail.com',
  roles: ['MANAGER'],
};

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
  return { status: res.status, body, ms: 0 };
}

const token = mint(MANAGER.id, MANAGER.email, MANAGER.roles);

async function main() {
  console.log('Documentation sync job\n');

  // 1. The endpoint must answer immediately with 202 and a job id.
  const started = Date.now();
  const queued = await call(
    token,
    `/workspaces/${WS}/repositories/${REPO}/scan-docs`,
    { method: 'POST' },
  );
  const elapsed = Date.now() - started;

  check(
    'POST scan-docs answers 202',
    queued.status === 202,
    `got ${queued.status}: ${JSON.stringify(queued.body).slice(0, 200)}`,
  );
  check(
    'the response carries a job id',
    typeof queued.body?.jobId === 'string' && queued.body.jobId.length > 0,
    JSON.stringify(queued.body).slice(0, 200),
  );
  check(
    'the request returns without waiting for the AI',
    elapsed < 10_000,
    `took ${elapsed}ms`,
  );

  if (!queued.body?.jobId) {
    console.log('\nno job id — stopping here');
    process.exit(1);
  }

  const jobId = queued.body.jobId;

  // 2. The job must be observable and reach a terminal state.
  const terminal = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED']);
  let job = null;
  let sawProgress = false;

  for (let attempt = 0; attempt < 300; attempt++) {
    await new Promise((r) => setTimeout(r, 2000));
    const res = await call(token, `/workspaces/${WS}/doc-sync-jobs/${jobId}`);
    if (res.status !== 200) {
      check(
        'GET doc-sync-jobs/{jobId} answers 200',
        false,
        `got ${res.status}: ${JSON.stringify(res.body).slice(0, 200)}`,
      );
      break;
    }
    job = res.body;
    if (!terminal.has(job.status)) {
      sawProgress = true;
    } else {
      break;
    }
  }

  check('the job status endpoint answers 200', job !== null);
  if (job) {
    check(
      'the job reaches a terminal state',
      terminal.has(job.status),
      `status=${job.status}`,
    );
    check(
      'the job reports the files it scanned',
      typeof job.totalFiles === 'number',
      JSON.stringify(job).slice(0, 200),
    );
    check(
      'the job reports how many proposals it queued',
      typeof job.queuedProposals === 'number',
      JSON.stringify(job).slice(0, 200),
    );
    console.log(
      `\n  job: status=${job.status} files=${job.totalFiles} drifted=${job.driftedFiles} ` +
        `queued=${job.queuedProposals} skipped=${job.skippedProposals} failed=${job.failedFiles}`,
    );
    if (job.errorMessage) {
      console.log(`  message: ${job.errorMessage}`);
    }
  }

  // 3. The run must be listed for the workspace.
  const list = await call(token, `/workspaces/${WS}/doc-sync-jobs`);
  check('GET doc-sync-jobs answers 200', list.status === 200, `got ${list.status}`);
  check(
    'the run appears in the workspace list',
    Array.isArray(list.body) && list.body.some((j) => j.jobId === jobId),
    JSON.stringify(list.body).slice(0, 200),
  );

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
