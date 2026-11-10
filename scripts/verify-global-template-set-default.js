#!/usr/bin/env node
/**
 * Verify the global-template half of the "one default per doc type"
 * invariant (P1-4).
 *
 * The admin endpoint promotes a global template to be the platform default
 * for its doc type. Every other global default of that type has to lose
 * the badge in the same operation, so the resolver never has to guess.
 *
 * Usage:
 *   node scripts/verify-global-template-set-default.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';

const ADMIN_ID = '21ccf213-2d28-4327-8623-64b0f85c7fb6';
const ADMIN_EMAIL = 'adminp@live.local';

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

const DOC_TYPE = 'CUSTOM';
const BODY_JSON = JSON.stringify({
  version: 1,
  titleHint: '{{projectName}}',
  sections: [
    {
      id: 'overview',
      heading: 'Overview',
      level: 2,
      placeholders: [{ key: 'summary', prompt: 'What is it?', required: true }],
    },
  ],
  variables: [],
});

(async () => {
  console.log(`LivingDocs global template set-default verification against ${API_BASE}`);

  const token = mint(ADMIN_ID, ADMIN_EMAIL, ['ADMIN']);
  const stamp = Date.now();

  // --- 1. create two global templates of the same doc type --------------
  console.log('\n== create two global templates of the same doc type ==');
  const first = await call(token, '/admin/templates', {
    method: 'POST',
    body: {
      name: `Global A ${stamp}`,
      slug: `global-a-${stamp}`,
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
    'the first global template is created',
    first.status === 201,
    `got ${first.status} ${JSON.stringify(first.body)}`,
  );

  const second = await call(token, '/admin/templates', {
    method: 'POST',
    body: {
      name: `Global B ${stamp}`,
      slug: `global-b-${stamp}`,
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
    'the second global template is created',
    second.status === 201,
    `got ${second.status} ${JSON.stringify(second.body)}`,
  );

  // --- 2. promote the first one ------------------------------------------
  console.log('\n== promote the first global template ==');
  const promoted = await call(token, `/admin/templates/${first.body?.id}/set-default`, {
    method: 'POST',
  });
  check(
    'admin set-default answers 200',
    promoted.status === 200,
    `got ${promoted.status} ${JSON.stringify(promoted.body)}`,
  );
  check(
    'the promoted global template comes back flagged as default',
    promoted.body?.isDefault === true,
    `isDefault=${promoted.body?.isDefault}`,
  );

  // --- 3. promote the second one: the first must lose the badge ---------
  console.log('\n== promote the second global template ==');
  const promoted2 = await call(token, `/admin/templates/${second.body?.id}/set-default`, {
    method: 'POST',
  });
  check(
    'the second admin set-default answers 200',
    promoted2.status === 200,
    `got ${promoted2.status} ${JSON.stringify(promoted2.body)}`,
  );

  const list = await call(token, '/admin/templates');
  const firstAfter = list.body?.globalTemplates?.find((t) => t.id === first.body?.id);
  const secondAfter = list.body?.globalTemplates?.find((t) => t.id === second.body?.id);
  check(
    'the previous global default LOST its default badge',
    firstAfter?.isDefault === false,
    `isDefault=${firstAfter?.isDefault}`,
  );
  check(
    'the new global default carries the badge',
    secondAfter?.isDefault === true,
    `isDefault=${secondAfter?.isDefault}`,
  );

  const defaultsForType = (list.body?.globalTemplates ?? []).filter(
    (t) => t.docType === DOC_TYPE && t.isDefault,
  );
  check(
    'exactly one global template of this doc type is default',
    defaultsForType.length === 1,
    `found ${defaultsForType.length}`,
  );

  // --- 4. cleanup --------------------------------------------------------
  console.log('\n== cleanup ==');
  for (const t of [first.body?.id, second.body?.id]) {
    if (t) await call(token, `/admin/templates/${t}`, { method: 'DELETE' });
  }
  const list2 = await call(token, '/admin/templates');
  const leftovers = (list2.body?.globalTemplates ?? []).filter((t) =>
    t.slug.endsWith(`-${stamp}`),
  );
  check('the test templates are removed', leftovers.length === 0, `left ${leftovers.length}`);

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
})();
