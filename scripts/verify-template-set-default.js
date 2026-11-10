#!/usr/bin/env node
/**
 * Verify the template "set as default" flow and the
 * "one default per (workspace, docType)" invariant (P1-4).
 *
 * A workspace manager promotes a template to be the default for its
 * documentation type. The previous default must lose its badge in the
 * same operation, and the resolver must return the newly-promoted
 * template — never an arbitrary row.
 *
 * Usage:
 *   node scripts/verify-template-set-default.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';

// The workspace manager and the workspace under test.
const WS_MANAGER_ID = 'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de';
const WS_MANAGER_EMAIL = 'dxuan8461@gmail.com';
const WS = '43a3821d-3071-4242-add5-25c8dbe3d88e';

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

const DOC_TYPE = 'ADR';
const BODY_JSON = JSON.stringify({
  version: 1,
  titleHint: '{{projectName}}',
  sections: [
    {
      id: 'context',
      heading: 'Context',
      level: 2,
      placeholders: [{ key: 'context', prompt: 'What problem?', required: true }],
    },
  ],
  variables: [],
});

(async () => {
  console.log(`LivingDocs template set-default verification against ${API_BASE}`);
  console.log(`workspace: ${WS}`);

  const token = mint(WS_MANAGER_ID, WS_MANAGER_EMAIL, ['MANAGER']);
  const stamp = Date.now();

  // --- 1. create two templates of the same doc type ---------------------
  console.log('\n== create two templates of the same doc type ==');
  const first = await call(token, `/workspaces/${WS}/templates`, {
    method: 'POST',
    body: {
      name: `Default A ${stamp}`,
      slug: `default-a-${stamp}`,
      description: 'first candidate',
      docType: DOC_TYPE,
      bodyJson: BODY_JSON,
      outputFormat: 'MARKDOWN',
      autoGenerateOnCommit: false,
      autoGenerateOnPr: false,
      autoGenerateOnMerge: false,
      isDefault: false,
    },
  });
  check(
    'the first template is created',
    first.status === 201,
    `got ${first.status} ${JSON.stringify(first.body)}`,
  );

  const second = await call(token, `/workspaces/${WS}/templates`, {
    method: 'POST',
    body: {
      name: `Default B ${stamp}`,
      slug: `default-b-${stamp}`,
      description: 'second candidate',
      docType: DOC_TYPE,
      bodyJson: BODY_JSON,
      outputFormat: 'MARKDOWN',
      autoGenerateOnCommit: false,
      autoGenerateOnPr: false,
      autoGenerateOnMerge: false,
      isDefault: false,
    },
  });
  check(
    'the second template is created',
    second.status === 201,
    `got ${second.status} ${JSON.stringify(second.body)}`,
  );

  // --- 2. promote the first one ------------------------------------------
  console.log('\n== promote the first template ==');
  const promoted = await call(
    token,
    `/workspaces/${WS}/templates/${first.body?.id}/set-default`,
    { method: 'POST' },
  );
  check(
    'set-default answers 200',
    promoted.status === 200,
    `got ${promoted.status} ${JSON.stringify(promoted.body)}`,
  );
  check(
    'the promoted template comes back flagged as default',
    promoted.body?.isDefault === true,
    `isDefault=${promoted.body?.isDefault}`,
  );

  // --- 3. the other template must NOT be default ------------------------
  console.log('\n== the other template keeps no default badge ==');
  const list = await call(token, `/workspaces/${WS}/templates`);
  const firstInList = list.body?.workspaceTemplates?.find((t) => t.id === first.body?.id);
  const secondInList = list.body?.workspaceTemplates?.find((t) => t.id === second.body?.id);
  check(
    'the promoted template is listed as default',
    firstInList?.isDefault === true,
    `isDefault=${firstInList?.isDefault}`,
  );
  check(
    'the other template is NOT listed as default',
    secondInList?.isDefault === false,
    `isDefault=${secondInList?.isDefault}`,
  );

  // --- 4. promote the second one: the first must lose the badge ---------
  console.log('\n== promote the second template ==');
  const promoted2 = await call(
    token,
    `/workspaces/${WS}/templates/${second.body?.id}/set-default`,
    { method: 'POST' },
  );
  check(
    'the second set-default answers 200',
    promoted2.status === 200,
    `got ${promoted2.status} ${JSON.stringify(promoted2.body)}`,
  );

  const list2 = await call(token, `/workspaces/${WS}/templates`);
  const firstAfter = list2.body?.workspaceTemplates?.find((t) => t.id === first.body?.id);
  const secondAfter = list2.body?.workspaceTemplates?.find((t) => t.id === second.body?.id);
  check(
    'the previous default LOST its default badge',
    firstAfter?.isDefault === false,
    `isDefault=${firstAfter?.isDefault}`,
  );
  check(
    'the new default carries the badge',
    secondAfter?.isDefault === true,
    `isDefault=${secondAfter?.isDefault}`,
  );

  // --- 5. exactly one default per doc type ------------------------------
  console.log('\n== the invariant holds for the whole workspace ==');
  const defaultsForType = (list2.body?.workspaceTemplates ?? []).filter(
    (t) => t.docType === DOC_TYPE && t.isDefault,
  );
  check(
    'exactly one template of this doc type is default',
    defaultsForType.length === 1,
    `found ${defaultsForType.length}: ${defaultsForType.map((t) => t.id).join(', ')}`,
  );
  check(
    'and it is the one that was promoted last',
    defaultsForType[0]?.id === second.body?.id,
    `default=${defaultsForType[0]?.id} expected=${second.body?.id}`,
  );

  // --- 6. creating a template with isDefault demotes the old one -------
  console.log('\n== creating a template as default demotes the old one ==');
  const third = await call(token, `/workspaces/${WS}/templates`, {
    method: 'POST',
    body: {
      name: `Default C ${stamp}`,
      slug: `default-c-${stamp}`,
      description: 'third candidate',
      docType: DOC_TYPE,
      bodyJson: BODY_JSON,
      outputFormat: 'MARKDOWN',
      autoGenerateOnCommit: false,
      autoGenerateOnPr: false,
      autoGenerateOnMerge: false,
      isDefault: true,
    },
  });
  check(
    'the third template is created as default',
    third.status === 201 && third.body?.isDefault === true,
    `got ${third.status} isDefault=${third.body?.isDefault}`,
  );

  const list3 = await call(token, `/workspaces/${WS}/templates`);
  const defaultsForType2 = (list3.body?.workspaceTemplates ?? []).filter(
    (t) => t.docType === DOC_TYPE && t.isDefault,
  );
  check(
    'still exactly one default for this doc type',
    defaultsForType2.length === 1,
    `found ${defaultsForType2.length}`,
  );
  check(
    'and it is the newly created one',
    defaultsForType2[0]?.id === third.body?.id,
    `default=${defaultsForType2[0]?.id}`,
  );

  // --- 7. a plain member may not promote a template ---------------------
  console.log('\n== a non-manager cannot set the default ==');
  const memberToken = mint(
    '11111111-1111-1111-1111-111111111111',
    'lead@livingdocs.test',
    ['TECHNICAL_LEAD'],
  );
  const refused = await call(
    memberToken,
    `/workspaces/${WS}/templates/${first.body?.id}/set-default`,
    { method: 'POST' },
  );
  check(
    'a plain workspace member is refused',
    refused.status === 403,
    `got ${refused.status} ${JSON.stringify(refused.body)}`,
  );

  // --- 8. cleanup --------------------------------------------------------
  console.log('\n== cleanup ==');
  for (const t of [first.body?.id, second.body?.id, third.body?.id]) {
    if (t) await call(token, `/workspaces/${WS}/templates/${t}`, { method: 'DELETE' });
  }
  const list4 = await call(token, `/workspaces/${WS}/templates`);
  const leftovers = (list4.body?.workspaceTemplates ?? []).filter(
    (t) => t.slug.endsWith(`-${stamp}`),
  );
  check('the test templates are removed', leftovers.length === 0, `left ${leftovers.length}`);

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
})();
