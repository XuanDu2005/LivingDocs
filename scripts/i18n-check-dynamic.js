/**
 * Verify the dynamic translation keys resolve for every runtime value.
 *
 * Some call sites build the key at runtime with a template literal, so a
 * static scan cannot prove the key exists. This script resolves each of
 * those templates against the known set of possible values and reports
 * the ones that would render a raw key.
 */
const fs = require('fs');

const vi = JSON.parse(fs.readFileSync('d:/Project/LivingDocs/frontend/src/i18n/vi.json', 'utf8'));
const en = JSON.parse(fs.readFileSync('d:/Project/LivingDocs/frontend/src/i18n/en.json', 'utf8'));

const get = (obj, dotted) =>
  dotted.split('.').reduce((node, part) => (node == null ? undefined : node[part]), obj);

/** Each entry: the key template plus the runtime values it can produce. */
const DYNAMIC = [
  {
    label: 'notifications.kindLabels.${...}',
    values: [
      'ADMIN_BROADCAST',
      'drift.detected',
      'doc.generation.failed',
      'github.tokenInvalid',
      'system.test',
      'SCM_PR_EVENT',
      'SCM_PUSH',
      'SCM_ISSUE_EVENT',
      'SCM_RELEASE',
      'JIRA_ISSUE_EVENT',
      'REVIEW_ASSIGNED',
      'INDEXING_DONE',
      'GITHUB_PR_EVENT',
      'GITHUB_PUSH',
      'GITHUB_ISSUE_EVENT',
      'GITHUB_RELEASE',
      'GITLAB_PUSH',
      'GITLAB_MR_EVENT',
      'GITLAB_ISSUE_EVENT',
    ].map((k) => `notifications.kindLabels.${k.replace(/\./g, '_')}`),
  },
  {
    label: 'knowledge.kind${...}',
    values: ['INDEX', 'REINDEX_ALL'].map(
      (k) =>
        `knowledge.kind${k
          .toLowerCase()
          .split('_')
          .map((part, i) => (i === 0 ? part : part.charAt(0).toUpperCase() + part.slice(1)))
          .join('')
          .replace(/^./, (c) => c.toUpperCase())}`,
    ),
  },
  {
    label: 'repository.filter${...}',
    values: ['ALL', 'CONNECTED', 'ERROR', 'ARCHIVED', 'DISCONNECTED'].map(
      (s) => `repository.filter${s.charAt(0)}${s.slice(1).toLowerCase()}`,
    ),
  },
  {
    label: 'templates.outputFormat_${f}',
    values: ['MARKDOWN', 'HTML', 'PDF'].map((f) => `templates.outputFormat_${f}`),
  },
];

let problems = 0;

for (const { label, values } of DYNAMIC) {
  console.log(`\n${label}`);
  for (const key of values) {
    const v = get(vi, key);
    const e = get(en, key);
    const ok = v !== undefined && e !== undefined;
    if (!ok) problems++;
    console.log(`  ${ok ? 'OK  ' : 'MISS'} ${key}  vi=${JSON.stringify(v)}  en=${JSON.stringify(e)}`);
  }
}

// roleLabels / retentionLabels are object maps keyed by an enum code, so
// every child of the map must exist in both locales.
for (const ns of ['roleLabels', 'retentionLabels']) {
  const vKeys = Object.keys(vi[ns] || {});
  const eKeys = Object.keys(en[ns] || {});
  console.log(`\n${ns}: vi=${vKeys.length} en=${eKeys.length}`);
  const onlyVi = vKeys.filter((k) => !eKeys.includes(k));
  const onlyEn = eKeys.filter((k) => !vKeys.includes(k));
  if (onlyVi.length) { problems++; console.log('  only in vi: ' + onlyVi.join(', ')); }
  if (onlyEn.length) { problems++; console.log('  only in en: ' + onlyEn.join(', ')); }

  for (const code of vKeys) {
    const v = vi[ns][code];
    const e = en[ns] ? en[ns][code] : undefined;
    if (!e) { problems++; console.log(`  MISS in en: ${ns}.${code}`); continue; }
    for (const field of Object.keys(v)) {
      if (e[field] === undefined) {
        problems++;
        console.log(`  MISS in en: ${ns}.${code}.${field}`);
      }
    }
  }
}

console.log(problems ? `\n${problems} PROBLEM(S)` : '\nAll dynamic keys resolve.');
process.exit(problems ? 1 : 0);
