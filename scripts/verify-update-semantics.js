#!/usr/bin/env node
/**
 * Verifies the two backend fixes against a running stack:
 *
 *   1. A metadata update that does not mention the summary keeps the
 *      current summary. It used to be wiped on every update.
 *   2. A malformed request body is answered with 400 Bad Request
 *      instead of 500 Internal Server Error.
 *
 * Usage:
 *   node scripts/verify-update-semantics.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';

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
    body:
      opts.rawBody !== undefined
        ? opts.rawBody
        : opts.body
          ? JSON.stringify(opts.body)
          : undefined,
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

const MANAGER = mint(
  'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de',
  'dxuan8461@gmail.com',
  ['MANAGER'],
);

async function main() {
  console.log(`LivingDocs update-semantics verification against ${API_BASE}`);
  console.log(`workspace: ${WORKSPACE_ID}`);

  const docs = await call(MANAGER, `/workspaces/${WORKSPACE_ID}/documents`);
  if (docs.status !== 200 || !Array.isArray(docs.body) || docs.body.length === 0) {
    console.log(`  FAIL  could not list documents — HTTP ${docs.status}`);
    process.exit(1);
  }
  const doc = docs.body[0];
  const docId = doc.id;
  console.log(`document: ${doc.title} (${docId})`);

  // ---------------------------------------------------------------
  console.log('\n== the summary ==');

  // 1. Set a known summary.
  const marker = `verification-summary-${Date.now()}`;
  const set = await call(MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    body: {
      title: doc.title,
      summary: marker,
      autoUpdateEnabled: doc.autoUpdateEnabled,
    },
  });
  check(
    'a summary can be written',
    set.status === 200 && set.body.summary === marker,
    `HTTP ${set.status}, summary=${set.body && set.body.summary}`,
  );

  // 2. An update that does not mention the summary keeps it. This is
  //    the bug: the summary used to be wiped by any update.
  const titleOnly = await call(
    MANAGER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}`,
    {
      method: 'PUT',
      body: {
        title: doc.title,
        autoUpdateEnabled: doc.autoUpdateEnabled,
      },
    },
  );
  check(
    'an update without a summary keeps the summary',
    titleOnly.status === 200 && titleOnly.body.summary === marker,
    `HTTP ${titleOnly.status}, summary=${titleOnly.body && titleOnly.body.summary}`,
  );

  // 3. An explicit null keeps it too — null and absent mean the
  //    same thing.
  const explicitNull = await call(
    MANAGER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}`,
    {
      method: 'PUT',
      body: {
        title: doc.title,
        summary: null,
        autoUpdateEnabled: doc.autoUpdateEnabled,
      },
    },
  );
  check(
    'an update with a null summary keeps the summary',
    explicitNull.status === 200 && explicitNull.body.summary === marker,
    `HTTP ${explicitNull.status}, summary=${explicitNull.body && explicitNull.body.summary}`,
  );

  // 4. An empty string clears it.
  const cleared = await call(MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    body: {
      title: doc.title,
      summary: '',
      autoUpdateEnabled: doc.autoUpdateEnabled,
    },
  });
  check(
    'an empty summary clears it',
    cleared.status === 200 && (cleared.body.summary === null || cleared.body.summary === ''),
    `HTTP ${cleared.status}, summary=${JSON.stringify(cleared.body && cleared.body.summary)}`,
  );

  // 5. Put the summary back so the workspace is left as it was.
  await call(MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    body: {
      title: doc.title,
      summary: doc.summary ?? '',
      autoUpdateEnabled: doc.autoUpdateEnabled,
    },
  });

  // ---------------------------------------------------------------
  console.log('\n== a malformed body ==');

  // 6. A body that is not JSON at all.
  const notJson = await call(MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    rawBody: 'this is not json',
  });
  check(
    'a body that is not JSON is answered with 400',
    notJson.status === 400,
    `HTTP ${notJson.status}`,
  );

  // 7. A body of the wrong shape — a string where an object was
  //    expected.
  const wrongShape = await call(MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    rawBody: JSON.stringify('"just a string"'),
  });
  check(
    'a body of the wrong shape is answered with 400',
    wrongShape.status === 400,
    `HTTP ${wrongShape.status}`,
  );

  // 8. A truncated JSON document.
  const truncated = await call(MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    rawBody: '{"title": "Broken", "summary":',
  });
  check(
    'a truncated body is answered with 400',
    truncated.status === 400,
    `HTTP ${truncated.status}`,
  );

  // 9. A field of the wrong type — a number where a UUID was
  //    expected.
  const wrongType = await call(MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    body: {
      title: doc.title,
      ownerId: 12345,
      autoUpdateEnabled: doc.autoUpdateEnabled,
    },
  });
  check(
    'a field of the wrong type is answered with 400',
    wrongType.status === 400,
    `HTTP ${wrongType.status}`,
  );

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
