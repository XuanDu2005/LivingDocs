#!/usr/bin/env node
/**
 * Verify the health & compliance report export (P2-7 / A8).
 *
 * The rules under test:
 *   - `GET /workspaces/{id}/health/export` returns one row per document,
 *     joining the documents, their versions, their drift alerts and
 *     their templates;
 *   - `GET /workspaces/{id}/compliance/export` returns one row per live
 *     version, with the template compliance of that version;
 *   - both are available as CSV and as JSON, and both answer with the
 *     `Content-Disposition` of a downloadable file;
 *   - the whole thing is gated behind HEALTH_DASHBOARD_VIEW — a reader
 *     without the capability gets a 403, not a report.
 *
 * Usage:
 *   node scripts/verify-health-export.js
 */
'use strict';

const crypto = require('crypto');

const API_BASE = process.env.API_BASE || 'http://localhost:8080/api/v1';
const JWT_SECRET =
  process.env.JWT_SECRET || 'change-me-please-this-is-a-development-only-secret-32b+';

const WS = process.env.WS_ID || '43a3821d-3071-4242-add5-25c8dbe3d88e';

// The manager holds HEALTH_DASHBOARD_VIEW (see V36); the developer
// deliberately does not.
//
// The account has to hold *only* DEVELOPER: the permission is resolved
// from the role assignments in the database, so a user who also holds
// MANAGER would pass the gate and the check below would prove nothing.
const MANAGER = {
  id: 'b2ddb3c7-d48a-44dc-961a-f58a5d39c0de',
  email: 'dxuan8461@gmail.com',
  roles: ['MANAGER'],
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
  return {
    status: res.status,
    body,
    text,
    disposition: res.headers.get('content-disposition') || '',
    contentType: res.headers.get('content-type') || '',
  };
}

/**
 * The header of the health report, in the order the requirement names.
 */
const HEALTH_COLUMNS = [
  'document_id',
  'title',
  'slug',
  'doc_type',
  'status',
  'owner_id',
  'template_name',
  'template_compliant',
  'missing_headings',
  'last_version',
  'last_updated',
  'open_drift',
  'max_drift_severity',
  'days_since_update',
];

/**
 * The header of the compliance report, in the order the requirement
 * names.
 */
const COMPLIANCE_COLUMNS = [
  'document_id',
  'version_number',
  'template_name',
  'compliant',
  'missing_headings',
  'out_of_order',
  'generated_by',
  'created_at',
];

(async () => {
  console.log(`LivingDocs health-export verification against ${API_BASE}`);
  console.log(`workspace: ${WS}`);

  const managerToken = mint(MANAGER.id, MANAGER.email, MANAGER.roles);
  const developerToken = mint(DEVELOPER.id, DEVELOPER.email, DEVELOPER.roles);

  // --- 1. the health report as CSV -------------------------------------
  console.log('\n== the health report (CSV) ==');
  const healthCsv = await call(managerToken, `/workspaces/${WS}/health/export?format=csv`);
  check('the health export answers 200', healthCsv.status === 200, `got ${healthCsv.status} ${healthCsv.text}`);
  check(
    'the health export is a CSV download',
    healthCsv.contentType.includes('text/csv') &&
      healthCsv.disposition.includes('attachment') &&
      healthCsv.disposition.includes('health-report.csv'),
    `contentType=${healthCsv.contentType} disposition=${healthCsv.disposition}`,
  );

  const healthLines = healthCsv.text.trim().split(/\r?\n/);
  check(
    'the health report has the expected columns',
    healthLines[0] === HEALTH_COLUMNS.join(','),
    `header=${healthLines[0]}`,
  );
  check(
    'the health report has a row per document',
    healthLines.length > 1,
    `${healthLines.length - 1} rows`,
  );

  // The first data row must carry the same number of columns as the
  // header — a row with fewer fields means the escaping is broken.
  const firstRow = healthLines[1] || '';
  check(
    'the health rows carry every column',
    firstRow.split(',').length >= HEALTH_COLUMNS.length,
    `${firstRow.split(',').length} fields in "${firstRow}"`,
  );
  check(
    'the health rows name their document',
    /^[0-9a-f-]{36},/.test(firstRow),
    `first field of "${firstRow}"`,
  );

  // --- 2. the health report as JSON ------------------------------------
  console.log('\n== the health report (JSON) ==');
  const healthJson = await call(managerToken, `/workspaces/${WS}/health/export?format=json`);
  check('the JSON health export answers 200', healthJson.status === 200, `got ${healthJson.status}`);
  check(
    'the JSON health export is a JSON download',
    healthJson.contentType.includes('application/json') &&
      healthJson.disposition.includes('health-report.json'),
    `contentType=${healthJson.contentType} disposition=${healthJson.disposition}`,
  );
  check(
    'the JSON health export is an array',
    Array.isArray(healthJson.body),
    `got ${typeof healthJson.body}`,
  );
  if (Array.isArray(healthJson.body) && healthJson.body.length > 0) {
    const row = healthJson.body[0];
    check(
      'the JSON rows carry the report fields',
      'documentId' in row &&
        'templateName' in row &&
        'templateCompliant' in row &&
        'missingHeadings' in row &&
        'openDrift' in row &&
        'maxDriftSeverity' in row &&
        'daysSinceUpdate' in row,
      JSON.stringify(Object.keys(row)),
    );
    check(
      'the JSON rows carry the same documents as the CSV',
      healthJson.body.length === healthLines.length - 1,
      `json=${healthJson.body.length} csv=${healthLines.length - 1}`,
    );
  }

  // --- 3. the compliance report ----------------------------------------
  console.log('\n== the compliance report ==');
  const complianceCsv = await call(
    managerToken,
    `/workspaces/${WS}/compliance/export?format=csv`,
  );
  check('the compliance export answers 200', complianceCsv.status === 200, `got ${complianceCsv.status}`);
  check(
    'the compliance export is a CSV download',
    complianceCsv.contentType.includes('text/csv') &&
      complianceCsv.disposition.includes('compliance-report.csv'),
    `contentType=${complianceCsv.contentType} disposition=${complianceCsv.disposition}`,
  );
  const complianceLines = complianceCsv.text.trim().split(/\r?\n/);
  check(
    'the compliance report has the expected columns',
    complianceLines[0] === COMPLIANCE_COLUMNS.join(','),
    `header=${complianceLines[0]}`,
  );
  check(
    'the compliance report has at least one live version',
    complianceLines.length > 1,
    `${complianceLines.length - 1} rows`,
  );

  const complianceJson = await call(
    managerToken,
    `/workspaces/${WS}/compliance/export?format=json`,
  );
  check('the JSON compliance export answers 200', complianceJson.status === 200, `got ${complianceJson.status}`);
  check(
    'the JSON compliance export is an array',
    Array.isArray(complianceJson.body),
    `got ${typeof complianceJson.body}`,
  );
  if (Array.isArray(complianceJson.body) && complianceJson.body.length > 0) {
    const row = complianceJson.body[0];
    check(
      'the JSON compliance rows carry the report fields',
      'documentId' in row &&
        'versionNumber' in row &&
        'templateName' in row &&
        'compliant' in row &&
        'missingHeadings' in row &&
        'outOfOrder' in row &&
        'generatedBy' in row &&
        'createdAt' in row,
      JSON.stringify(Object.keys(row)),
    );
  }

  // --- 4. the permission gate ------------------------------------------
  console.log('\n== the permission gate ==');
  const refusedHealth = await call(
    developerToken,
    `/workspaces/${WS}/health/export?format=csv`,
  );
  check(
    'a reader without HEALTH_DASHBOARD_VIEW is refused the health export',
    refusedHealth.status === 403,
    `got ${refusedHealth.status}`,
  );
  const refusedCompliance = await call(
    developerToken,
    `/workspaces/${WS}/compliance/export?format=csv`,
  );
  check(
    'a reader without HEALTH_DASHBOARD_VIEW is refused the compliance export',
    refusedCompliance.status === 403,
    `got ${refusedCompliance.status}`,
  );
  const anonymous = await call('', `/workspaces/${WS}/health/export?format=csv`);
  check(
    'an anonymous reader is refused',
    anonymous.status === 401 || anonymous.status === 403,
    `got ${anonymous.status}`,
  );

  // --- 5. the default format is CSV ------------------------------------
  console.log('\n== the default format ==');
  const defaulted = await call(managerToken, `/workspaces/${WS}/health/export`);
  check(
    'the export without a format parameter is a CSV',
    defaulted.status === 200 && defaulted.contentType.includes('text/csv'),
    `status=${defaulted.status} contentType=${defaulted.contentType}`,
  );

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed > 0 ? 1 : 0);
})();
