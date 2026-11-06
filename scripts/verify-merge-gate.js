#!/usr/bin/env node
/**
 * LivingDocs — merge gate / CI token checks.
 *
 * Exercises the merge-gate enforcement path end-to-end against a running
 * stack:
 *
 *   1. a workspace manager issues a CI token;
 *   2. the CI-facing merge-gate endpoint accepts that token and answers
 *      with a verdict (the workflow's only source of truth);
 *   3. a wrong token and a missing token are refused — a CI job cannot
 *      skip the gate by forgetting its credential;
 *   4. a token of one workspace cannot act on another workspace;
 *   5. the generated GitHub Actions workflow asks LivingDocs and does not
 *      decide on its own.
 *
 * Usage:
 *   node scripts/verify-merge-gate.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID;
const OTHER_WORKSPACE_ID = process.env.OTHER_WORKSPACE_ID;

const b64url = (buf) =>
  Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

function mint(userId, email) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(
    JSON.stringify({ sub: userId, email, iat: now, exp: now + 3600 }),
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
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.ciToken) headers['X-LivingDocs-CI-Token'] = opts.ciToken;
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
  return { status: res.status, body, headers: res.headers };
}

const ADMIN = mint('9b35bb4d-844b-4fc8-af5d-aa20ce41858f', 'admin@livingdocs.com');

async function main() {
  if (!WORKSPACE_ID) {
    console.error('WORKSPACE_ID is required.');
    process.exit(1);
  }
  console.log(`API: ${API_BASE}`);
  console.log(`Workspace: ${WORKSPACE_ID}`);

  // -- the token ------------------------------------------------------
  console.log('\n== ci token ==');
  const status = await call(ADMIN, `/workspaces/${WORKSPACE_ID}/ci-token`);
  check('the token status is readable', status.status === 200, `HTTP ${status.status}`);
  check(
    'the status carries hasToken',
    status.status === 200 && typeof status.body?.hasToken === 'boolean',
    JSON.stringify(status.body),
  );

  const issued = await call(ADMIN, `/workspaces/${WORKSPACE_ID}/ci-token/rotate`, {
    method: 'POST',
  });
  check('a manager can issue a CI token', issued.status === 200, `HTTP ${issued.status}`);
  const token = issued.body?.token;
  check(
    'the plaintext token is returned once',
    typeof token === 'string' && token.startsWith('ldci_'),
    JSON.stringify(issued.body),
  );

  // -- the verdict ----------------------------------------------------
  console.log('\n== merge gate verdict ==');
  const verdict = await call(
    null,
    `/cicd/workspaces/${WORKSPACE_ID}/repositories/${crypto.randomUUID()}/merge-gate?pullRequestNumber=1`,
    { ciToken: token },
  );
  check(
    'the CI token is accepted',
    verdict.status === 200 || verdict.status === 404,
    `HTTP ${verdict.status} ${JSON.stringify(verdict.body)}`,
  );
  if (verdict.status === 200) {
    check('the verdict carries the blocked flag', typeof verdict.body?.blocked === 'boolean');
    check('the verdict carries the effective policy', !!verdict.body?.policy, JSON.stringify(verdict.body));
    check(
      'the verdict carries the severity threshold',
      !!verdict.body?.severityThreshold,
      JSON.stringify(verdict.body),
    );
    check('the verdict carries a reason', !!verdict.body?.reason);
  }

  console.log('\n== credential enforcement ==');
  const noToken = await call(
    null,
    `/cicd/workspaces/${WORKSPACE_ID}/repositories/${crypto.randomUUID()}/merge-gate?pullRequestNumber=1`,
  );
  check('a missing token is refused', noToken.status === 401, `HTTP ${noToken.status}`);

  const badToken = await call(
    null,
    `/cicd/workspaces/${WORKSPACE_ID}/repositories/${crypto.randomUUID()}/merge-gate?pullRequestNumber=1`,
    { ciToken: 'ldci_not_a_real_token' },
  );
  check('an unknown token is refused', badToken.status === 401, `HTTP ${badToken.status}`);

  if (OTHER_WORKSPACE_ID) {
    const cross = await call(
      null,
      `/cicd/workspaces/${OTHER_WORKSPACE_ID}/repositories/${crypto.randomUUID()}/merge-gate?pullRequestNumber=1`,
      { ciToken: token },
    );
    check(
      'a token cannot act on another workspace',
      cross.status === 403,
      `HTTP ${cross.status}`,
    );
  }

  // -- the workflow ---------------------------------------------------
  console.log('\n== generated workflow ==');
  const repos = await call(ADMIN, `/workspaces/${WORKSPACE_ID}/repositories`);
  const repoList = Array.isArray(repos.body) ? repos.body : repos.body?.items;
  if (!repoList || repoList.length === 0) {
    console.log('  SKIP  no repository in this workspace — cannot fetch the workflow');
  } else {
    const repoId = repoList[0].id;
    const wf = await call(
      ADMIN,
      `/workspaces/${WORKSPACE_ID}/repositories/${repoId}/github-actions.yml`,
    );
    const yaml = typeof wf.body === 'string' ? wf.body : '';
    check('the workflow is generated', wf.status === 200 && yaml.length > 0, `HTTP ${wf.status}`);
    check('the workflow calls the merge gate', yaml.includes('/merge-gate'), yaml);
    check('the workflow presents the CI token', yaml.includes('X-LivingDocs-CI-Token'));
    check(
      'the workflow does not decide on its own',
      !yaml.includes('blockOnCritical') && !yaml.includes('driftSeverityThreshold'),
      yaml,
    );
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) {
    console.log('\nFailures:');
    failures.forEach((f) => console.log(`  - ${f}`));
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
