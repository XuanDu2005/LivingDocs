#!/usr/bin/env node
/**
 * LivingDocs — AI documentation generation check.
 *
 * End-to-end verification of the AI generation pipeline against a running
 * stack (backend + Kafka + AI service + database).
 *
 * Usage:
 *   node scripts/ai-generation-check.js [workspaceId] [repositoryId]
 *
 * Environment overrides:
 *   API_BASE      default http://localhost:8080/api/v1
 *   JWT_SECRET    default matches the dev secret in .env.example
 *   USER_ID       the user the calls are made as
 *   EMAIL         that user's email
 *
 * What it checks:
 *   - preview generation returns real (non-mock) markdown
 *   - async generation returns 202 and reaches a terminal state
 *   - the resulting version is stored and queued for review
 *   - the grounding evidence cites the source file
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
      Authorization: `Bearer ${token()}`,
      'Content-Type': 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    parsed = text;
  }
  return { status: res.status, body: parsed };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const SOURCE = {
  path: 'src/services/ApiClient.ts',
  content: [
    '/**',
    ' * HTTP client for the dashboard API.',
    ' */',
    'export class ApiClient {',
    '  private baseUrl: string;',
    '  constructor(baseUrl = \'https://api.example.com\') {',
    '    this.baseUrl = baseUrl.replace(/\\/$/, \'\');',
    '  }',
    '  /**',
    '   * Fetch a JSON payload from the given path.',
    '   */',
    '  async get<T>(path: string): Promise<T> {',
    '    const response = await fetch(this.baseUrl + path);',
    '    if (!response.ok) {',
    '      throw new Error(`GET ${path} failed with status ${response.status}`);',
    '    }',
    '    return (await response.json()) as T;',
    '  }',
    '}',
  ].join('\n'),
};

async function findDocument() {
  const gaps = await call(
    'GET',
    `/workspaces/${WS}/repositories/${REPO}/code-entities/gaps`,
  );
  const entities = Array.isArray(gaps.body) ? gaps.body : [];
  if (!entities.length) return null;
  const docs = await call('GET', `/workspaces/${WS}/documents`);
  const byTitle = new Map(
    (Array.isArray(docs.body) ? docs.body : []).map((d) => [d.title, d.id]),
  );
  return byTitle.get(entities[0].simpleName) || null;
}

(async () => {
  console.log(`AI generation check — workspace ${WS}, repo ${REPO}`);

  const docId = await findDocument();
  if (!docId) {
    console.log('\n  SKIP  no document available — ingest the repository first.');
    console.log(`        POST /workspaces/${WS}/repositories/${REPO}/code-entities/ingest`);
    process.exit(1);
  }

  console.log('\n== preview generation ==');
  const preview = await call(
    'POST',
    `/workspaces/${WS}/documents/${docId}/preview-generate?template=MODULE_GUIDE`,
    [SOURCE],
  );
  check('preview returns 200', preview.status === 200, `got ${preview.status}`);
  const markdown = preview.body?.markdown || '';
  check('preview produced markdown', markdown.length > 100, `${markdown.length} chars`);
  check(
    'preview is real LLM output, not the mock stub',
    !markdown.includes('offline mock provider'),
    markdown.slice(0, 80),
  );
  check('preview mentions the class', /ApiClient/.test(markdown), markdown.slice(0, 120));

  console.log('\n== async generation (Kafka) ==');
  const accepted = await call(
    'POST',
    `/workspaces/${WS}/documents/${docId}/generate?template=MODULE_GUIDE`,
    [SOURCE],
  );
  check('generate returns 202 Accepted', accepted.status === 202, `got ${accepted.status}`);
  check('generate returns a job id', typeof accepted.body?.jobId === 'string');

  let status = null;
  for (let i = 0; i < 40; i++) {
    await sleep(2000);
    const res = await call(
      'GET',
      `/workspaces/${WS}/documents/${docId}/generation-status`,
    );
    status = res.body;
    if (['SUCCEEDED', 'FAILED', 'SKIPPED'].includes(status?.status)) break;
  }
  check(
    'generation reaches a terminal state',
    status?.status === 'SUCCEEDED',
    JSON.stringify(status),
  );
  check('generation records a version id', typeof status?.documentVersionId === 'string');

  if (status?.documentVersionId) {
    const versions = await call('GET', `/documents/${docId}/versions`);
    const list = Array.isArray(versions.body) ? versions.body : [];
    check('the new version is listed', list.length > 0);
    check(
      'the new version is queued for review or published',
      ['PENDING', 'PUBLISHED', 'IN_REVIEW'].includes(list[0]?.status),
      list[0]?.status,
    );

    const grounding = await call(
      'GET',
      `/workspaces/${WS}/documents/${docId}/versions/${status.documentVersionId}/grounding`,
    );
    check('grounding is readable', grounding.status === 200, `got ${grounding.status}`);
    check(
      'grounding cites the source file',
      (grounding.body?.sources || []).some((s) => s.file === SOURCE.path),
      JSON.stringify(grounding.body?.sources),
    );
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) {
    console.log('\nFailures:');
    for (const f of failures) console.log(`  - ${f}`);
    process.exit(1);
  }
})().catch((err) => {
  console.error('Unexpected error:', err);
  process.exit(1);
});
