#!/usr/bin/env node
/**
 * Verify the template compliance guard on publish (P1-3).
 *
 * The rule under test:
 *   - a version that does not follow a STRICT template (a required
 *     section is missing) cannot be published — the API answers 400 and
 *     names the missing section;
 *   - a non-strict template only produces a warning and an audit entry;
 *   - sections that are merely in the wrong order never block a
 *     publication.
 *
 * Usage:
 *   node scripts/verify-template-compliance-publish.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';

// The workspace under test and the four roles that drive the workflow.
// They have to be four different people: the review workflow refuses a
// self-review, and the publish guard is the thing under test.
const WS = '43a3821d-3071-4242-add5-25c8dbe3d88e';
const AUTHOR = { id: '22222222-2222-2222-2222-222222222222', email: 'purestaff@livingdocs.test', roles: ['STAFF'] };
const STAFF_REVIEWER = { id: 'cb7580a0-1a96-4b3b-bcd1-8cb03c6a5eb5', email: 'xdu393519@gmail.com', roles: ['STAFF'] };
const MANAGER = { id: 'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de', email: 'dxuan8461@gmail.com', roles: ['MANAGER'] };
const PUBLISHER = { id: '11111111-1111-1111-1111-111111111111', email: 'lead@livingdocs.test', roles: ['TECHNICAL_LEAD'] };

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

/**
 * The body of a template with three required sections.
 */
function templateBody(strict) {
  return JSON.stringify({
    version: 1,
    titleHint: 'Compliance probe',
    instructions: '',
    strict,
    sections: [
      { id: 'overview', heading: 'Overview', level: 2, required: true, placeholders: [] },
      { id: 'api-surface', heading: 'API surface', level: 2, required: true, placeholders: [] },
      { id: 'testing', heading: 'Testing', level: 2, required: true, placeholders: [] },
    ],
  });
}

/**
 * A document body that carries every section of the template.
 */
const COMPLIANT_BODY = ['# Overview', 'text', '# API surface', 'text', '# Testing', 'text'].join('\n');

/**
 * The same body with the "Testing" section removed.
 */
const MISSING_BODY = ['# Overview', 'text', '# API surface', 'text'].join('\n');

/**
 * The same body with the sections in the wrong order.
 */
const OUT_OF_ORDER_BODY = ['# API surface', 'text', '# Overview', 'text', '# Testing', 'text'].join('\n');

/**
 * Drive a version from a draft to APPROVED.
 *
 * <p>Three different people are involved: the author submits it, the
 * Staff tier approves it, the Manager tier approves it. The self-review
 * guard refuses a verdict from the submitter, so they have to be
 * different people.
 */
async function makeApproved(tokens, docId, bodyMarkdown) {
  const appended = await call(
    tokens.author,
    `/workspaces/${WS}/documents/${docId}/versions`,
    { method: 'POST', body: { bodyMarkdown, changeSummary: 'compliance probe' } },
  );
  const version = appended.body;
  const versionId = version?.id;
  if (!versionId) return { error: `append failed: ${JSON.stringify(appended)}` };

  const submitted = await call(
    tokens.author,
    `/workspaces/${WS}/documents/${docId}/versions/${versionId}/submit-for-review`,
    { method: 'POST' },
  );
  if (submitted.status !== 200) {
    return { error: `submit failed: ${submitted.status} ${JSON.stringify(submitted.body)}` };
  }
  const staff = await call(
    tokens.staff,
    `/workspaces/${WS}/documents/${docId}/versions/${versionId}/staff-review`,
    { method: 'POST', body: { decision: 'APPROVED', comment: 'ok' } },
  );
  if (staff.status !== 200) {
    return { error: `staff review failed: ${staff.status} ${JSON.stringify(staff.body)}` };
  }
  const manager = await call(
    tokens.manager,
    `/workspaces/${WS}/documents/${docId}/versions/${versionId}/manager-decision`,
    { method: 'POST', body: { decision: 'APPROVED', comment: 'ok' } },
  );
  if (manager.status !== 200) {
    return { error: `manager decision failed: ${manager.status} ${JSON.stringify(manager.body)}` };
  }
  return { versionId, versionNumber: version.versionNumber };
}

(async () => {
  console.log(`LivingDocs template-compliance-on-publish verification against ${API_BASE}`);
  console.log(`workspace: ${WS}`);

  const tokens = {
    author: mint(AUTHOR.id, AUTHOR.email, AUTHOR.roles),
    staff: mint(STAFF_REVIEWER.id, STAFF_REVIEWER.email, STAFF_REVIEWER.roles),
    manager: mint(MANAGER.id, MANAGER.email, MANAGER.roles),
    publisher: mint(PUBLISHER.id, PUBLISHER.email, PUBLISHER.roles),
  };
  const stamp = Date.now();

  // --- 1. create a STRICT template with three required sections -------
  console.log('\n== create a strict template ==');
  const strictTemplate = await call(tokens.manager, `/workspaces/${WS}/templates`, {
    method: 'POST',
    body: {
      name: `compliance-strict-${stamp}`,
      slug: `compliance-strict-${stamp}`,
      description: 'strict template for the compliance-on-publish check',
      docType: 'MODULE_GUIDE',
      bodyJson: templateBody(true),
      outputFormat: 'MARKDOWN',
      autoGenerateOnCommit: false,
      autoGenerateOnPr: false,
      autoGenerateOnMerge: false,
      isDefault: false,
    },
  });
  check(
    'the strict template is created',
    strictTemplate.status === 201,
    `got ${strictTemplate.status} ${JSON.stringify(strictTemplate.body)}`,
  );
  const strictId = strictTemplate.body?.id;

  // --- 2. a document that uses it -------------------------------------
  console.log('\n== create a document bound to the template ==');
  const doc = await call(tokens.author, `/workspaces/${WS}/documents`, {
    method: 'POST',
    body: {
      title: `compliance-probe-${stamp}`,
      slug: `compliance-probe-${stamp}`,
      docType: 'MODULE_GUIDE',
      templateId: strictId,
      initialBody: COMPLIANT_BODY,
    },
  });
  check(
    'the document is created',
    doc.status === 201,
    `got ${doc.status} ${JSON.stringify(doc.body)}`,
  );
  const docId = doc.body?.id;
  check(
    'the document points at the template',
    doc.body?.templateId === strictId,
    `templateId=${doc.body?.templateId}`,
  );

  // The seed version is a DRAFT; it has to go through the review
  // workflow to become APPROVED, which is what publishing requires.
  const timeline = await call(tokens.author, `/documents/${docId}/versions`);
  const seeded = timeline.body?.[0];
  check('the document has a seeded version', !!seeded, JSON.stringify(timeline.body));

  // --- 3. the compliance endpoint agrees with the template ------------
  console.log('\n== the compliance endpoint ==');
  const compliance = await call(
    tokens.author,
    `/workspaces/${WS}/documents/${docId}/versions/${seeded.id}/template-compliance`,
  );
  check(
    'the compliance endpoint answers 200',
    compliance.status === 200,
    `got ${compliance.status}`,
  );
  check(
    'the seeded body is compliant',
    compliance.body?.compliant === true,
    JSON.stringify(compliance.body),
  );
  check(
    'the endpoint reports the template as strict',
    compliance.body?.strict === true,
    `strict=${compliance.body?.strict}`,
  );

  // --- 4. a body that misses a required section cannot be published ----
  console.log('\n== publishing a version that misses a required section ==');
  const broken = await makeApproved(tokens, docId, MISSING_BODY);
  if (broken.error) {
    check('the broken version reaches APPROVED', false, broken.error);
  } else {
    const brokenCompliance = await call(
      tokens.author,
      `/workspaces/${WS}/documents/${docId}/versions/${broken.versionId}/template-compliance`,
    );
    check(
      'the broken body is reported as non-compliant',
      brokenCompliance.body?.compliant === false,
      JSON.stringify(brokenCompliance.body),
    );
    check(
      'the missing section is reported as required',
      (brokenCompliance.body?.missingRequiredHeadings ?? []).includes('Testing'),
      JSON.stringify(brokenCompliance.body?.missingRequiredHeadings),
    );

    const published = await call(
      tokens.publisher,
      `/documents/${docId}/versions/${broken.versionNumber}/publish`,
      { method: 'POST' },
    );
    check(
      'publishing a non-compliant version is refused with 400',
      published.status === 400,
      `got ${published.status} ${JSON.stringify(published.body)}`,
    );
    const message =
      published.body?.message ?? published.body?.detail ?? JSON.stringify(published.body);
    check('the message names the missing section', String(message).includes('Testing'), String(message));
    check(
      'the message names the template',
      String(message).includes('compliance-strict'),
      String(message),
    );

    // The version must NOT have become the published head.
    const after = await call(tokens.author, `/workspaces/${WS}/documents/${docId}`);
    check(
      'the refused version did not become the published head',
      after.body?.headVersionId !== broken.versionId,
      `headVersionId=${after.body?.headVersionId}`,
    );
  }

  // --- 5. a compliant body publishes ---------------------------------
  console.log('\n== publishing a version that follows the template ==');
  const good = await makeApproved(tokens, docId, COMPLIANT_BODY);
  if (good.error) {
    check('the compliant version reaches APPROVED', false, good.error);
  } else {
    const published = await call(
      tokens.publisher,
      `/documents/${docId}/versions/${good.versionNumber}/publish`,
      { method: 'POST' },
    );
    check(
      'publishing a compliant version answers 200',
      published.status === 200,
      `got ${published.status} ${JSON.stringify(published.body)}`,
    );
    const after = await call(tokens.author, `/workspaces/${WS}/documents/${docId}`);
    check(
      'the compliant version became the published head',
      after.body?.headVersionId === good.versionId,
      `headVersionId=${after.body?.headVersionId}`,
    );
  }

  // --- 6. the wrong order never blocks a publication ------------------
  console.log('\n== publishing a version whose sections are out of order ==');
  const scrambled = await makeApproved(tokens, docId, OUT_OF_ORDER_BODY);
  if (scrambled.error) {
    check('the scrambled version reaches APPROVED', false, scrambled.error);
  } else {
    const published = await call(
      tokens.publisher,
      `/documents/${docId}/versions/${scrambled.versionNumber}/publish`,
      { method: 'POST' },
    );
    check(
      'a version that is merely out of order still publishes',
      published.status === 200,
      `got ${published.status} ${JSON.stringify(published.body)}`,
    );
  }

  // --- 7. a NON-strict template only warns ----------------------------
  console.log('\n== a non-strict template only warns ==');
  const softTemplate = await call(tokens.manager, `/workspaces/${WS}/templates`, {
    method: 'POST',
    body: {
      name: `compliance-soft-${stamp}`,
      slug: `compliance-soft-${stamp}`,
      description: 'non-strict template for the compliance-on-publish check',
      docType: 'MODULE_GUIDE',
      bodyJson: templateBody(false),
      outputFormat: 'MARKDOWN',
      autoGenerateOnCommit: false,
      autoGenerateOnPr: false,
      autoGenerateOnMerge: false,
      isDefault: false,
    },
  });
  const softId = softTemplate.body?.id;
  check(
    'the non-strict template is created',
    softTemplate.status === 201,
    `got ${softTemplate.status} ${JSON.stringify(softTemplate.body)}`,
  );

  const softDoc = await call(tokens.author, `/workspaces/${WS}/documents`, {
    method: 'POST',
    body: {
      title: `compliance-soft-probe-${stamp}`,
      slug: `compliance-soft-probe-${stamp}`,
      docType: 'MODULE_GUIDE',
      templateId: softId,
      initialBody: COMPLIANT_BODY,
    },
  });
  const softDocId = softDoc.body?.id;
  check('the soft document is created', softDoc.status === 201, `got ${softDoc.status}`);

  const softTimeline = await call(tokens.author, `/documents/${softDocId}/versions`);
  const softSeeded = softTimeline.body?.[0];
  const softCompliance = await call(
    tokens.author,
    `/workspaces/${WS}/documents/${softDocId}/versions/${softSeeded.id}/template-compliance`,
  );
  check(
    'the non-strict template is reported as not strict',
    softCompliance.body?.strict === false,
    `strict=${softCompliance.body?.strict}`,
  );

  // A non-compliant version of the soft document still publishes.
  const softBroken = await makeApproved(tokens, softDocId, MISSING_BODY);
  if (softBroken.error) {
    check('the soft broken version reaches APPROVED', false, softBroken.error);
  } else {
    const softPublished = await call(
      tokens.publisher,
      `/documents/${softDocId}/versions/${softBroken.versionNumber}/publish`,
      { method: 'POST' },
    );
    check(
      'a non-strict template does not block the publication',
      softPublished.status === 200,
      `got ${softPublished.status} ${JSON.stringify(softPublished.body)}`,
    );
  }

  // --- 8. the audit trail records the non-compliant publication --------
  console.log('\n== the audit trail ==');
  const audit = await call(
    tokens.publisher,
    `/workspaces/${WS}/documents/${softDocId}/audit-trail`,
  );
  const entries = Array.isArray(audit.body)
    ? audit.body
    : (audit.body?.items ?? audit.body?.entries ?? audit.body?.content ?? []);
  const actions = entries.map((e) => e.action);
  check(
    'the non-compliant publication is recorded in the audit trail',
    actions.includes('document.version.published_noncompliant'),
    JSON.stringify(actions),
  );

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
