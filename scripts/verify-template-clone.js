#!/usr/bin/env node
/**
 * Verify the template clone flow (P0-2).
 *
 * A global template must be cloneable INTO a workspace: the copy is
 * workspace-scoped so the workspace can edit it.
 *
 * Usage:
 *   node scripts/verify-template-clone.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';

// The workspace manager and the global template under test.
const WS_MANAGER_ID = 'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de';
const WS_MANAGER_EMAIL = 'dxuan8461@gmail.com';
const WS = '43a3821d-3071-4242-add5-25c8dbe3d88e';
const GLOBAL_TEMPLATE = '341a0834-8247-4a88-b880-38c78e66a2ed'; // default-api-reference

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
  return { status: res.status, body };
}

(async () => {
  console.log(`LivingDocs template-clone verification against ${API_BASE}`);
  console.log(`workspace: ${WS}`);

  const token = mint(WS_MANAGER_ID, WS_MANAGER_EMAIL, ['MANAGER']);
  const unique = `clone-check-${Date.now()}`;

  // --- 1. clone a GLOBAL template into the workspace -------------------
  console.log('\n== clone a global template ==');
  const cloned = await call(
    token,
    `/workspaces/${WS}/templates/${GLOBAL_TEMPLATE}/clone?newName=${encodeURIComponent(unique)}`,
    { method: 'POST' },
  );
  check(
    'cloning a global template answers 201',
    cloned.status === 201,
    `got ${cloned.status} ${JSON.stringify(cloned.body)}`,
  );
  check(
    'the clone is workspace-scoped',
    cloned.body?.workspaceId === WS,
    `workspaceId=${cloned.body?.workspaceId}`,
  );
  check(
    'the clone starts at version 1',
    cloned.body?.version === 1,
    `version=${cloned.body?.version}`,
  );
  check(
    'the clone is not marked default',
    cloned.body?.isDefault === false,
    `isDefault=${cloned.body?.isDefault}`,
  );
  check(
    'the clone keeps the source doc type',
    cloned.body?.docType === 'API_REFERENCE',
    `docType=${cloned.body?.docType}`,
  );
  check(
    'the clone carries a body',
    typeof cloned.body?.bodyJson === 'string' && cloned.body.bodyJson.length > 0,
  );

  // --- 2. the clone shows up in the workspace template list -------------
  console.log('\n== the clone is listed for the workspace ==');
  const list = await call(token, `/workspaces/${WS}/templates`);
  check('the template list answers 200', list.status === 200, `got ${list.status}`);
  check(
    'the clone appears in the workspace templates',
    Array.isArray(list.body?.workspaceTemplates) &&
      list.body.workspaceTemplates.some((t) => t.id === cloned.body?.id),
    JSON.stringify(list.body?.workspaceTemplates?.map((t) => t.id)),
  );
  check(
    'the clone does NOT appear as a global template',
    Array.isArray(list.body?.globalTemplates) &&
      !list.body.globalTemplates.some((t) => t.id === cloned.body?.id),
  );

  // --- 3. the clone is editable (proves it is a real workspace row) ----
  console.log('\n== the clone is editable in the workspace ==');
  const updated = await call(
    token,
    `/workspaces/${WS}/templates/${cloned.body?.id}`,
    {
      method: 'PUT',
      body: {
        name: `${unique}-edited`,
        description: 'edited after clone',
        docType: 'API_REFERENCE',
        bodyJson: cloned.body.bodyJson,
        outputFormat: 'MARKDOWN',
        autoGenerateOnCommit: false,
        autoGenerateOnPr: false,
        autoGenerateOnMerge: false,
        isDefault: false,
      },
    },
  );
  check(
    'the clone can be edited (it is workspace-scoped)',
    updated.status === 200,
    `got ${updated.status} ${JSON.stringify(updated.body)}`,
  );

  // --- 4. clone a name that needs slugification ------------------------
  console.log('\n== clone with a name that needs slugification ==');
  // The slug is unique across the whole table, so the name has to be
  // unique per run for the expected slug to be predictable.
  const trickyName = `My API Reference (v2)! ${Date.now()}`;
  const tricky = await call(
    token,
    `/workspaces/${WS}/templates/${GLOBAL_TEMPLATE}/clone?newName=${encodeURIComponent(trickyName)}`,
    { method: 'POST' },
  );
  check(
    'a name with punctuation and spaces is cloned',
    tricky.status === 201,
    `got ${tricky.status} ${JSON.stringify(tricky.body)}`,
  );
  check(
    'the tricky clone gets a clean slug',
    /^my-api-reference-v2-\d+$/.test(tricky.body?.slug ?? ''),
    `slug=${tricky.body?.slug}`,
  );

  // --- 5. a non-manager may not clone into the workspace ----------------
  console.log('\n== a non-manager cannot clone ==');
  const memberToken = mint(
    '11111111-1111-1111-1111-111111111111',
    'lead@livingdocs.test',
    ['TECHNICAL_LEAD'],
  );
  const refused = await call(
    memberToken,
    `/workspaces/${WS}/templates/${GLOBAL_TEMPLATE}/clone?newName=${encodeURIComponent(unique + '-member')}`,
    { method: 'POST' },
  );
  check(
    'a plain workspace member is refused',
    refused.status === 403,
    `got ${refused.status} ${JSON.stringify(refused.body)}`,
  );

  // --- 6. clone a workspace template stays in that workspace ------------
  console.log('\n== clone a workspace template ==');
  const wsClone = await call(
    token,
    `/workspaces/${WS}/templates/${cloned.body?.id}/clone?newName=${encodeURIComponent(unique + '-ws')}`,
    { method: 'POST' },
  );
  check(
    'cloning a workspace template answers 201',
    wsClone.status === 201,
    `got ${wsClone.status} ${JSON.stringify(wsClone.body)}`,
  );
  check(
    'the copy stays in the same workspace',
    wsClone.body?.workspaceId === WS,
    `workspaceId=${wsClone.body?.workspaceId}`,
  );
  check(
    'the copy starts a fresh version history',
    wsClone.body?.version === 1,
    `version=${wsClone.body?.version}`,
  );

  // --- 7. a workspace template cannot be cloned into another workspace --
  console.log('\n== a template cannot be cloned across workspaces ==');
  const crossWs = await call(
    token,
    `/workspaces/05b35ee3-c384-4575-b17b-88cad2cb1ac0/templates/${cloned.body?.id}/clone?newName=${encodeURIComponent(unique + '-cross')}`,
    { method: 'POST' },
  );
  check(
    'cloning into a different workspace is refused',
    crossWs.status === 403,
    `got ${crossWs.status} ${JSON.stringify(crossWs.body)}`,
  );

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
})();
