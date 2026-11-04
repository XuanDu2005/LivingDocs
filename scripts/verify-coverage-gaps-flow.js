#!/usr/bin/env node
/**
 * End-to-end verification of the "Coverage Gaps" flow for a DEVELOPER.
 *
 * Reproduces exactly what CoverageGapsPanel does:
 *   1. create the document
 *   2. link the code entity  (query parameters, not a JSON body)
 *   3. trigger the AI generation (bare JSON array, not an object)
 *
 * and asserts that the link really landed and that the generation run
 * was grounded on the linked entity rather than on an empty stub.
 *
 * Usage: node scripts/verify-coverage-gaps-flow.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';
const REPOSITORY_ID = process.env.REPOSITORY_ID || '0d1a10da-8d15-4f25-a274-62a6c216ea56';

const DEV_ID = '33333333-3333-3333-3333-333333333333';

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

const DEV = mint(DEV_ID, 'puredev@livingdocs.test', ['DEVELOPER']);

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
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
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

async function main() {
  console.log(`Coverage Gaps flow verification against ${API_BASE}`);
  console.log(`workspace=${WORKSPACE_ID} repository=${REPOSITORY_ID}`);

  // ---- 1. the gaps endpoint returns camelCase fields -------------------
  let gaps = await call(
    DEV,
    `/workspaces/${WORKSPACE_ID}/repositories/${REPOSITORY_ID}/code-entities/gaps`,
  );
  check('the gaps endpoint answers 200', gaps.status === 200, `got ${gaps.status}`);
  check('the gaps endpoint returns a list', Array.isArray(gaps.body), JSON.stringify(gaps.body));
  let gap = (gaps.body ?? []).find((g) => g && g.simpleName && g.filePath);

  // The flow consumes the gap it picks (the entity ends up linked to a
  // document), so a second run against the same repository would find
  // nothing left to document. When that happens we re-open a gap by
  // clearing the links of a document that covers one of the entities of
  // this repository — the same "unlink then link again" cycle the UI
  // goes through when a document is regenerated.
  if (!gap) {
    console.log('  ... no open gap — re-opening one by clearing existing links');
    const entities = await call(
      DEV,
      `/workspaces/${WORKSPACE_ID}/repositories/${REPOSITORY_ID}/code-entities`,
    );
    const entityList = Array.isArray(entities.body) ? entities.body : [];
    const docs = await call(
      DEV,
      `/workspaces/${WORKSPACE_ID}/documents`,
    );
    for (const doc of Array.isArray(docs.body) ? docs.body : []) {
      const links = await call(
        DEV,
        `/workspaces/${WORKSPACE_ID}/documents/${doc.id}/links`,
      );
      const hit = (links.body ?? []).find((l) =>
        entityList.some((e) => e.id === l.codeEntityId),
      );
      if (!hit) continue;
      const cleared = await call(
        DEV,
        `/workspaces/${WORKSPACE_ID}/documents/${doc.id}/links`,
        { method: 'DELETE' },
      );
      if (cleared.status !== 204) {
        console.log(
          `  ... could not clear the links of "${doc.title}" (status ${cleared.status})`,
        );
        continue;
      }
      console.log(`  ... cleared the links of "${doc.title}"`);
      break;
    }
    gaps = await call(
      DEV,
      `/workspaces/${WORKSPACE_ID}/repositories/${REPOSITORY_ID}/code-entities/gaps`,
    );
    gap = (gaps.body ?? []).find((g) => g && g.simpleName && g.filePath);
  }

  check(
    'the gap payload carries camelCase fields (simpleName / entityType / filePath)',
    !!gap,
    JSON.stringify((gaps.body ?? [])[0]),
  );
  if (!gap) {
    console.log('\nNo gap available — the rest of the flow cannot be exercised.');
    process.exit(1);
  }
  check('the gap has a qualified name', typeof gap.qualifiedName === 'string');
  check('the gap has a start line', typeof gap.startLine === 'number');

  // ---- 2. create the document ----------------------------------------
  const slug = `gap-${gap.simpleName}-${Date.now().toString(36)}`
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, '-')
    .slice(0, 80);
  const created = await call(DEV, `/workspaces/${WORKSPACE_ID}/documents`, {
    method: 'POST',
    body: {
      title: gap.simpleName,
      slug,
      docType: 'AUTO_' + (gap.entityType || 'CODE').toUpperCase(),
      summary: `Auto-created from coverage gap at ${gap.filePath}`,
      repositoryId: REPOSITORY_ID,
      autoUpdateEnabled: true,
      initialBody: `# ${gap.simpleName}\n\n> Auto-generated stub.\n`,
    },
  });
  check('the document is created', created.status === 201, `got ${created.status} ${JSON.stringify(created.body)}`);
  const docId = created.body?.id;
  if (!docId) {
    process.exit(1);
  }

  // ---- 3. link the code entity (query params) ------------------------
  const link = await call(
    DEV,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/links` +
      `?codeEntityId=${gap.id}&linkKind=AUTO&confidence=1.0`,
    { method: 'POST' },
  );
  check('the code entity is linked', link.status === 201, `got ${link.status} ${JSON.stringify(link.body)}`);

  const links = await call(DEV, `/workspaces/${WORKSPACE_ID}/documents/${docId}/links`);
  check(
    'the link is persisted (the gap is now covered)',
    (links.body ?? []).some((l) => l.codeEntityId === gap.id),
    JSON.stringify(links.body),
  );

  // ---- 4. trigger the generation (bare array) ------------------------
  const gen = await call(
    DEV,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/generate`,
    { method: 'POST', body: [] },
  );
  check(
    'the generation is accepted with 202',
    gen.status === 202,
    `got ${gen.status} ${JSON.stringify(gen.body)}`,
  );
  check('the generation returns a job id', typeof gen.body?.jobId === 'string');

  // ---- 5. wait for the run and inspect the grounding -----------------
  let status = null;
  for (let i = 0; i < 40; i++) {
    await sleep(2000);
    const res = await call(
      DEV,
      `/workspaces/${WORKSPACE_ID}/documents/${docId}/generation-status`,
    );
    status = res.body;
    if (['SUCCEEDED', 'FAILED', 'SKIPPED'].includes(status?.status)) break;
  }
  console.log(`  ... generation status: ${JSON.stringify(status)}`);
  check('the generation reaches a terminal state', status?.status !== undefined, JSON.stringify(status));

  const versions = await call(DEV, `/documents/${docId}/versions`);
  const aiVersion = (versions.body ?? []).find((v) => v.actorRole === 'AI');
  check('an AI version was produced', !!aiVersion, JSON.stringify(versions.body));

  if (aiVersion) {
    const grounding = await call(
      DEV,
      `/workspaces/${WORKSPACE_ID}/documents/${docId}/versions/${aiVersion.id}/grounding`,
    );
    console.log(`  ... grounding: ${JSON.stringify(grounding.body)}`);
    check('the grounding endpoint answers', grounding.status === 200, `got ${grounding.status}`);
    const grounded = JSON.stringify(grounding.body ?? '');
    check(
      'the AI run was grounded on the linked code entity',
      grounded.includes(gap.filePath) || grounded.includes(gap.qualifiedName),
      grounded.slice(0, 400),
    );
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) {
    console.log('\nFailures:');
    for (const f of failures) console.log(`  - ${f}`);
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
