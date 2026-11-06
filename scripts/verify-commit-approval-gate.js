#!/usr/bin/env node
/**
 * LivingDocs — end-to-end check of the commit approval gate.
 *
 * The gate is the thing that makes the Manager approval tier mean
 * something: a document may only leave the platform after a Manager
 * approved the exact version that is being published, and only the
 * reviewed bytes may be published.
 *
 * The checks run against the real API, so they exercise the gate as the
 * caller sees it — the gate has to refuse before anything is sent to
 * GitHub, so no branch or pull request is created by a refused commit.
 *
 * Usage:
 *   WORKSPACE_ID=... REPOSITORY_ID=... DOCUMENT_ID=... \
 *   APPROVED_VERSION=... DRAFT_VERSION=... \
 *   node scripts/verify-commit-approval-gate.js
 */
'use strict';
const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WS = process.env.WORKSPACE_ID;
const REPO = process.env.REPOSITORY_ID;
const DOC = process.env.DOCUMENT_ID;
const APPROVED_VERSION = process.env.APPROVED_VERSION;
const DRAFT_VERSION = process.env.DRAFT_VERSION;
const USER_ID = process.env.USER_ID || '9b35bb4d-844b-4fc8-af5d-aa20ce41858f';
const EMAIL = process.env.USER_EMAIL || 'admin@livingdocs.com';

if (!WS || !REPO || !DOC || !APPROVED_VERSION || !DRAFT_VERSION) {
  console.error(
    'WORKSPACE_ID, REPOSITORY_ID, DOCUMENT_ID, APPROVED_VERSION and DRAFT_VERSION are required.',
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
  const token = mint(USER_ID, EMAIL);

  console.log('\n== Commit approval gate ==');

  // The approved body of the version that carries the approval. The
  // commit has to carry exactly these bytes.
  const version = await call(
    token,
    `/documents/${DOC}/versions/${APPROVED_VERSION}`,
  );
  const approvedBody = version.body?.bodyMarkdown || version.body?.content || null;

  const commit = (body, versionNumber) =>
    call(token, `/workspaces/${WS}/repositories/${REPO}/commit`, {
      method: 'POST',
      body: {
        filePath: 'docs/gate-check.md',
        content: body,
        commitMessage: 'gate check',
        prTitle: 'gate check',
        prBody: '',
        documentId: DOC,
        versionNumber,
      },
    });

  // 1. A draft version must be refused. This is the core rule: the
  //    Manager approval tier has to gate the commit.
  const draft = await commit('# Draft\n\nnot reviewed yet', DRAFT_VERSION);
  check(
    'a commit of an unapproved version is refused',
    draft.status === 409,
    `HTTP ${draft.status} ${JSON.stringify(draft.body)}`,
  );
  check(
    'the refusal explains that the version was not approved',
    /approv/i.test(String(draft.body?.message || '')),
    JSON.stringify(draft.body),
  );

  // 2. A missing version number must be refused by validation.
  const noVersion = await call(token, `/workspaces/${WS}/repositories/${REPO}/commit`, {
    method: 'POST',
    body: {
      filePath: 'docs/gate-check.md',
      content: '# whatever',
      commitMessage: 'gate check',
      prTitle: 'gate check',
      documentId: DOC,
    },
  });
  check(
    'a commit without a version number is refused',
    noVersion.status === 400,
    `HTTP ${noVersion.status} ${JSON.stringify(noVersion.body)}`,
  );

  // 3. A missing document id must be refused by validation.
  const noDocument = await call(token, `/workspaces/${WS}/repositories/${REPO}/commit`, {
    method: 'POST',
    body: {
      filePath: 'docs/gate-check.md',
      content: '# whatever',
      commitMessage: 'gate check',
      prTitle: 'gate check',
      versionNumber: APPROVED_VERSION,
    },
  });
  check(
    'a commit without a document id is refused',
    noDocument.status === 400,
    `HTTP ${noDocument.status} ${JSON.stringify(noDocument.body)}`,
  );

  // 4. The loophole: point the request at the APPROVED version but send
  //    different bytes. The gate must notice that the content does not
  //    match the approved body.
  if (approvedBody) {
    const mismatch = await commit('# Sneaky\n\ndifferent content', APPROVED_VERSION);
    check(
      'a commit that points at an approved version but sends other bytes is refused',
      mismatch.status === 409,
      `HTTP ${mismatch.status} ${JSON.stringify(mismatch.body)}`,
    );
    check(
      'the refusal explains the content mismatch',
      /match/i.test(String(mismatch.body?.message || '')),
      JSON.stringify(mismatch.body),
    );
  } else {
    console.log('  SKIP  the content-mismatch check (no approved body could be read)');
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
