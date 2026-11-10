#!/usr/bin/env node
/**
 * Verifies the document-ownership reassignment (P2-8) against a
 * running stack.
 *
 * <p>The checks that matter:
 * <ul>
 *   <li>a workspace MANAGER can hand a document to another member;</li>
 *   <li>the owner themselves cannot (it is a management act);</li>
 *   <li>somebody outside the workspace is refused as the new owner;</li>
 *   <li>the audit trail names both the old and the new owner;</li>
 *   <li>omitting the owner leaves the owner alone.</li>
 * </ul>
 *
 * Usage:
 *   node scripts/verify-document-owner.js
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

/**
 * The audit payload is a JSON-encoded string, not an object — the
 * server keeps it encoded so a reader expands it lazily.
 */
function parsePayload(raw) {
  if (!raw) return null;
  if (typeof raw === 'object') return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function call(token, path, opts = {}) {  const res = await fetch(`${API_BASE}${path}`, {
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

// The workspace MANAGER (holds the MANAGER platform role).
const WS_MANAGER = mint(
  'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de',
  'dxuan8461@gmail.com',
  ['MANAGER'],
);
// A genuine workspace MEMBER who owns a document. The account holds
// only the DEVELOPER platform role, so it holds DOCUMENT_UPDATE — the
// capability a metadata update needs — but no management capability.
// It must not be able to hand the document to somebody else.
const WS_MEMBER_USER = '33333333-3333-3333-3333-333333333333';
const WS_MEMBER = mint(WS_MEMBER_USER, 'puredev@livingdocs.test', ['DEVELOPER']);

async function main() {
  console.log('== setup ==');

  const members = await call(WS_MANAGER, `/workspaces/${WORKSPACE_ID}/members`);
  if (members.status !== 200) {
    console.log(`  FAIL  could not list members — HTTP ${members.status}`);
    process.exit(1);
  }
  const memberList = members.body;
  console.log('  members in workspace:');
  for (const m of memberList) {
    console.log(`    ${m.userId}  ${m.role}  ${m.userEmail || m.displayName || '-'}`);
  }

  // The new owner has to be somebody in the workspace who is not the
  // current owner.
  const managerId = 'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de';
  const target = memberList.find(
    (m) => m.userId !== managerId && m.userId !== WS_MEMBER_USER,
  );
  if (!target) {
    console.log('  FAIL  need at least two workspace members');
    process.exit(1);
  }
  const targetId = target.userId;
  console.log(`  target new owner: ${target.userEmail || target.displayName} (${targetId})`);

  // A document owned by the manager — the manager can always edit it.
  const docs = await call(WS_MANAGER, `/workspaces/${WORKSPACE_ID}/documents`);
  if (docs.status !== 200 || !Array.isArray(docs.body) || docs.body.length === 0) {
    console.log(`  FAIL  could not list documents — HTTP ${docs.status}`);
    process.exit(1);
  }
  const doc = docs.body.find((d) => d.ownerId === managerId) || docs.body[0];
  const docId = doc.id;
  const originalOwner = doc.ownerId;
  console.log(`  document: ${doc.title} (${docId}), owner ${originalOwner}`);

  console.log('\n== checks ==');

  // 1. A workspace MANAGER can reassign the document.
  const reassign = await call(WS_MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    body: {
      title: doc.title,
      summary: doc.summary ?? null,
      repositoryId: doc.repositoryId ?? undefined,
      templateId: doc.templateId ?? undefined,
      ownerId: targetId,
      autoUpdateEnabled: doc.autoUpdateEnabled,
    },
  });
  check(
    'manager can reassign the document',
    reassign.status === 200 && reassign.body.ownerId === targetId,
    `HTTP ${reassign.status}, ownerId=${reassign.body && reassign.body.ownerId}`,
  );

  // 2. The audit trail names both the old and the new owner.
  const trail = await call(
    WS_MANAGER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}/audit-trail`,
  );
  const entries = Array.isArray(trail.body) ? trail.body : trail.body?.items || [];
  const ownerEntry = entries.find((e) => {
    if (e.action !== 'document.updated') return false;
    const p = parsePayload(e.payload);
    return p && p.ownerIdChanged === true;
  });
  check(
    'the audit trail records the owner change',
    !!ownerEntry,
    'no document.updated entry with ownerIdChanged',
  );
  if (ownerEntry) {
    const p = parsePayload(ownerEntry.payload);
    check(
      'the audit trail names the previous owner',
      p.previousOwnerId === originalOwner,
      `previousOwnerId=${p.previousOwnerId}, expected ${originalOwner}`,
    );
    check(
      'the audit trail names the new owner',
      p.ownerId === targetId,
      `ownerId=${p.ownerId}, expected ${targetId}`,
    );
  }

  // 3. Omitting the owner leaves the owner alone.
  const noOwner = await call(WS_MANAGER, `/workspaces/${WORKSPACE_ID}/documents/${docId}`, {
    method: 'PUT',
    body: {
      title: doc.title,
      summary: doc.summary ?? null,
      autoUpdateEnabled: doc.autoUpdateEnabled,
    },
  });
  check(
    'omitting the owner leaves the owner alone',
    noOwner.status === 200 && noOwner.body.ownerId === targetId,
    `HTTP ${noOwner.status}, ownerId=${noOwner.body && noOwner.body.ownerId}`,
  );

  // 4. Somebody outside the workspace cannot become the owner.
  const outsider = '99999999-9999-9999-9999-999999999999';
  const outsiderAttempt = await call(
    WS_MANAGER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}`,
    {
      method: 'PUT',
      body: {
        title: doc.title,
        summary: doc.summary ?? null,
        ownerId: outsider,
        autoUpdateEnabled: doc.autoUpdateEnabled,
      },
    },
  );
  check(
    'a non-member cannot become the owner',
    outsiderAttempt.status === 403,
    `HTTP ${outsiderAttempt.status}`,
  );

  // 5. The owner themselves cannot hand the document to somebody else.
  // The document must be owned by the member for this check to mean
  // anything, so give it back to the manager first — no: give it to the
  // member, then let the member try to pass it on.
  const giveToMember = await call(
    WS_MANAGER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}`,
    {
      method: 'PUT',
      body: {
        title: doc.title,
        summary: doc.summary ?? null,
        ownerId: WS_MEMBER_USER,
        autoUpdateEnabled: doc.autoUpdateEnabled,
      },
    },
  );
  check(
    'manager can hand the document to a member',
    giveToMember.status === 200 &&
      giveToMember.body.ownerId === WS_MEMBER_USER,
    `HTTP ${giveToMember.status}`,
  );

  const memberAttempt = await call(
    WS_MEMBER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}`,
    {
      method: 'PUT',
      body: {
        title: doc.title,
        summary: doc.summary ?? null,
        ownerId: targetId,
        autoUpdateEnabled: doc.autoUpdateEnabled,
      },
    },
  );
  check(
    'the owner cannot hand the document to somebody else',
    memberAttempt.status === 403,
    `HTTP ${memberAttempt.status}`,
  );

  // 6. Restore the original owner so the workspace is left as it was.
  const restore = await call(
    WS_MANAGER,
    `/workspaces/${WORKSPACE_ID}/documents/${docId}`,
    {
      method: 'PUT',
      body: {
        title: doc.title,
        summary: doc.summary ?? null,
        ownerId: originalOwner,
        autoUpdateEnabled: doc.autoUpdateEnabled,
      },
    },
  );
  check(
    'the original owner is restored',
    restore.status === 200 && restore.body.ownerId === originalOwner,
    `HTTP ${restore.status}, ownerId=${restore.body && restore.body.ownerId}`,
  );

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
