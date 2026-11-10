#!/usr/bin/env node
/**
 * Verify the dependency graph and the impact analysis (P2-6 / A5).
 *
 * The rule under test:
 *   - the dependency graph of a workspace connects a document to the
 *     code entities it documents, and carries the containment chain
 *     (repository → module → entity);
 *   - the impact analysis walks the links in reverse: given a code
 *     entity, it names the documents that document it, the entities
 *     that move with it, the drift alerts that touch it and the pull
 *     requests that are open;
 *   - the whole thing is gated behind DEPENDENCY_GRAPH_VIEW — a reader
 *     without the capability gets a 403, not a graph.
 *
 * The verification mirrors the requirement's own acceptance criterion:
 * change a method in UserService.java, and the impact endpoint has to
 * name the document that documents it.
 *
 * Usage:
 *   node scripts/verify-dependency-graph.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';

const WS = process.env.WS_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';

// The technical lead holds DEPENDENCY_GRAPH_VIEW (see V50); the
// developer deliberately does not — the graph is an oversight surface.
//
// The account has to hold *only* DEVELOPER: the permission is resolved
// from the role assignments in the database, so a user who also holds
// STAFF would pass the gate and the check below would prove nothing.
const TECHNICAL_LEAD = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'lead@livingdocs.test',
  roles: ['TECHNICAL_LEAD'],
};
const DEVELOPER = {
  id: '33333333-3333-3333-3333-333333333333',
  email: 'puredev@livingdocs.test',
  roles: ['DEVELOPER'],
};

const b64url = (buf) =>
  Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

/**
 * The namespace prefix of each node kind.
 *
 * <p>The prefixes are not simply the lowercased kind: a code entity is
 * namespaced "entity", not "code_entity". The mapping mirrors
 * `DependencyNode.id` in the backend.
 */
const NODE_PREFIX = {
  DOCUMENT: 'document',
  CODE_ENTITY: 'entity',
  MODULE: 'module',
  REPOSITORY: 'repository',
};

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

/**
 * Find the code entity of a method inside UserService.java.
 *
 * <p>The acceptance criterion of the requirement names that file
 * explicitly: "change a method in UserService.java → the entity
 * changes → the impact endpoint returns the right document".
 */
async function findUserServiceEntity(token) {
  const repos = await call(token, `/workspaces/${WS}/repositories`);
  const repositories = Array.isArray(repos.body) ? repos.body : [];
  if (repositories.length === 0) {
    return { error: 'the workspace has no repository' };
  }

  for (const repo of repositories) {
    const entities = await call(
      token,
      `/workspaces/${WS}/repositories/${repo.id}/code-entities`,
    );
    if (!Array.isArray(entities.body)) continue;
    const userService = entities.body.find(
      (e) =>
        e.filePath &&
        e.filePath.includes('UserService') &&
        e.entityType &&
        e.entityType.toUpperCase() === 'METHOD',
    );
    if (userService) {
      return { entity: userService, repository: repo };
    }
  }
  return { error: 'no UserService method found in any repository' };
}

(async () => {
  console.log(`LivingDocs dependency-graph verification against ${API_BASE}`);
  console.log(`workspace: ${WS}`);

  const lead = mint(TECHNICAL_LEAD.id, TECHNICAL_LEAD.email, TECHNICAL_LEAD.roles);
  const dev = mint(DEVELOPER.id, DEVELOPER.email, DEVELOPER.roles);

  // --- 1. the gate ----------------------------------------------------
  console.log('\n== the DEPENDENCY_GRAPH_VIEW gate ==');
  const denied = await call(dev, `/workspaces/${WS}/dependency-graph`);
  check(
    'a reader without DEPENDENCY_GRAPH_VIEW is refused with 403',
    denied.status === 403,
    `got ${denied.status} ${JSON.stringify(denied.body)}`,
  );

  const allowed = await call(lead, `/workspaces/${WS}/dependency-graph`);
  check(
    'a reader with DEPENDENCY_GRAPH_VIEW gets the graph',
    allowed.status === 200,
    `got ${allowed.status} ${JSON.stringify(allowed.body).slice(0, 200)}`,
  );
  check(
    'the graph carries nodes and edges',
    Array.isArray(allowed.body?.nodes) && Array.isArray(allowed.body?.edges),
    JSON.stringify(Object.keys(allowed.body ?? {})),
  );
  check(
    'the graph names its workspace',
    allowed.body?.workspaceId === WS,
    `workspaceId=${allowed.body?.workspaceId}`,
  );

  // --- 2. the shape of the graph ---------------------------------------
  console.log('\n== the shape of the graph ==');
  const nodes = allowed.body?.nodes ?? [];
  const edges = allowed.body?.edges ?? [];

  const kinds = new Set(nodes.map((n) => n.kind));
  check(
    'every node carries a known kind',
    [...kinds].every((k) =>
      ['DOCUMENT', 'CODE_ENTITY', 'MODULE', 'REPOSITORY'].includes(k),
    ),
    [...kinds].join(', '),
  );
  check(
    'every node id is namespaced by its kind',
    nodes.every((n) => n.id.startsWith(`${NODE_PREFIX[n.kind] ?? '?'}:`)),
    nodes.map((n) => n.id).slice(0, 3).join(', '),
  );
  check(
    'every edge points at a node that exists',
    edges.every(
      (e) =>
        nodes.some((n) => n.id === e.from) && nodes.some((n) => n.id === e.to),
    ),
    `${edges.length} edges checked`,
  );
  check(
    'every edge carries a known kind',
    edges.every((e) =>
      ['DOCUMENTS', 'REFERENCES', 'CONTAINS'].includes(e.kind),
    ),
    [...new Set(edges.map((e) => e.kind))].join(', '),
  );

  // --- 3. the depth parameter ------------------------------------------
  console.log('\n== the depth parameter ==');
  const shallow = await call(lead, `/workspaces/${WS}/dependency-graph?depth=1`);
  check(
    'depth=1 is accepted',
    shallow.status === 200,
    `got ${shallow.status}`,
  );
  check(
    'depth=1 does not reach further than depth=2',
    (shallow.body?.nodes?.length ?? 0) <= (allowed.body?.nodes?.length ?? 0),
    `depth1=${shallow.body?.nodes?.length} depth2=${allowed.body?.nodes?.length}`,
  );

  const overDeep = await call(lead, `/workspaces/${WS}/dependency-graph?depth=99`);
  check(
    'an absurd depth is clamped instead of rejected',
    overDeep.status === 200,
    `got ${overDeep.status}`,
  );

  // --- 4. the acceptance criterion ------------------------------------
  console.log('\n== the acceptance criterion: change a UserService method ==');
  const found = await findUserServiceEntity(lead);
  if (found.error) {
    check('the UserService method is found', false, found.error);
  } else {
    const entity = found.entity;
    console.log(
      `  ..    ${entity.qualifiedName} (${entity.entityType}) in ${entity.filePath}`,
    );

    // The document that documents the entity.
    const impact = await call(
      lead,
      `/workspaces/${WS}/code-entities/${entity.id}/impact`,
    );
    check(
      'the impact endpoint answers 200',
      impact.status === 200,
      `got ${impact.status} ${JSON.stringify(impact.body).slice(0, 200)}`,
    );
    check(
      'the impact analysis names the analysed entity',
      impact.body?.entity?.id === entity.id,
      `entity.id=${impact.body?.entity?.id}`,
    );

    const documents = impact.body?.documents ?? [];
    check(
      'the impact analysis names the documents of the entity',
      documents.length > 0,
      'no document documents this entity — link one in the "Links" tab first',
    );
    if (documents.length > 0) {
      const documentId = documents[0].id;
      console.log(`  ..    documented by "${documents[0].title}"`);

      // The graph has to show the same edge, in the other direction.
      const documentGraph = await call(
        lead,
        `/workspaces/${WS}/dependency-graph?documentId=${documentId}`,
      );
      const graphEntityIds = (documentGraph.body?.nodes ?? [])
        .filter((n) => n.kind === 'CODE_ENTITY')
        .map((n) => n.id);
      check(
        'the graph of that document contains the entity',
        graphEntityIds.includes(`entity:${entity.id}`),
        graphEntityIds.join(', '),
      );
      check(
        'the graph of that document contains the document',
        (documentGraph.body?.nodes ?? []).some(
          (n) => n.kind === 'DOCUMENT' && n.id === `document:${documentId}`,
        ),
        'the document node is missing',
      );
      check(
        'the graph of that document is narrower than the workspace graph',
        (documentGraph.body?.nodes?.length ?? 0) <= (allowed.body?.nodes?.length ?? 0),
        `doc=${documentGraph.body?.nodes?.length} ws=${allowed.body?.nodes?.length}`,
      );

      // The impact endpoint has to be stable across calls — the
      // document detail page and the graph page both call it.
      const impactAgain = await call(
        lead,
        `/workspaces/${WS}/code-entities/${entity.id}/impact`,
      );
      check(
        'the impact analysis is stable across calls',
        JSON.stringify(impactAgain.body?.documents?.map((d) => d.id)) ===
          JSON.stringify(documents.map((d) => d.id)),
        'the document set changed between two calls',
      );
    }

    // The related entities of the same file.
    const related = impact.body?.relatedEntities ?? [];
    check(
      'the impact analysis names the entities of the same file',
      related.some((e) => e.relation === 'SAME_FILE'),
      `${related.length} related entities`,
    );
  }

  // --- 5. the repository filter ---------------------------------------
  console.log('\n== the repository filter ==');
  const filtered = await call(
    lead,
    `/workspaces/${WS}/dependency-graph?repositoryId=00000000-0000-0000-0000-000000000000`,
  );
  check(
    'an unknown repository yields an empty graph rather than an error',
    filtered.status === 200 && (filtered.body?.nodes?.length ?? 0) === 0,
    `got ${filtered.status} ${filtered.body?.nodes?.length} nodes`,
  );

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});