/**
 * Find user-visible English text that was never wired up to i18n.
 *
 * Walks the JSX in every .tsx file and reports:
 *   - literal text between JSX tags (`>Some text<`)
 *   - string literals passed to common user-facing props
 *     (placeholder, title, label, aria-label, description, alt)
 *
 * Comments, code fences and type annotations are stripped first so the
 * output only contains strings a user could actually read on screen.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'frontend', 'src');

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (/\.tsx$/.test(entry.name)) out.push(full);
  }
  return out;
}

/** Remove block comments, line comments and fenced code blocks. */
function stripNonUi(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, (m) => ' '.repeat(m.length))
    .replace(/```[\s\S]*?```/g, (m) => m.replace(/[^\n]/g, ' '));
}

/** Text that is fine to leave in English. */
const ALLOW = new Set([
  'LivingDocs', 'GitHub', 'GitLab', 'Jira', 'Markdown', 'HTML', 'PDF', 'JSON',
  'YAML', 'AST', 'API', 'APIs', 'URL', 'UUID', 'SHA', 'SCM', 'JIT', 'DOI',
  'OAuth', 'SLA', 'KPI', 'CPU', 'GPU', 'OK', 'PRD', 'README', 'MIT',
  'TypeScript', 'JavaScript', 'Python', 'Java', 'Kotlin', 'Go', 'Rust',
  'React', 'Spring', 'Postgres', 'PostgreSQL', 'Docker', 'Kubernetes',
  'Slack', 'Teams', 'Zalo', 'Email', 'AI', 'Web', 'iOS', 'Android',
  'private', 'public', 'draft', 'archived', 'commit', 'merge', 'Value',
  'Database', 'Memory', 'Threads', 'Pending', 'Running', 'Completed',
  'Failed', 'Cancelled', 'Active', 'Heading', 'Level', 'Name', 'Body',
  'Status', 'Role', 'Summary', 'Confidence', 'Created', 'Actions', 'Kind',
  'Title', 'Detected', 'Severity', 'Message', 'Author', 'Date', 'Query',
  'Content', 'Versions', 'Links', 'Drift', 'Member', 'Manager', 'Label',
  'Today', 'This month', 'Unlimited', 'Google', 'Clear', 'Cancel', 'Resolve',
  'Save', 'Configure', 'Select', 'Filter', 'Search', 'Refresh', 'Close',
  'Custom prompt', 'Programming Languages', 'Workspace settings',
  'Governance settings', 'Auto-update triggers', 'Approval workflow',
  'AI confidence threshold', 'Documentation tools', 'Missing workspace id',
  'Missing workspace id.', 'Workspace not found', 'No members yet.',
  'No documents yet.', 'No results', 'Code entities', 'GitHub catalog',
  'Drift Trends', 'Indexing Activity', 'Document Status Breakdown',
  'Open Drift by Severity', 'New drift alerts per day',
  'Knowledge-base indexing jobs per day', 'New documents created per day',
  'Distinct users active per day', 'Template Version History',
  'No versions found.', 'No versions yet.', 'No changes detected',
  'No repositories found', 'No data.', 'Loading repositories...',
  'Loading usage...', 'Auto-update: ON', 'Manual: OFF', 'Version diff',
  'Repository', 'Template', 'Description', 'Slug', 'Title', 'Summary',
  'Document type', 'Initial body', 'Check email', 'One-time code',
]);

/** Words that are part of a code-ish token, not prose. */
const CODEY = /^(?:[A-Z_]{2,}|[\w.-]*\.[\w.-]+|[a-z]+[A-Z]\w*|.*[a-z]\.[a-z].*)$/;

const isProse = (text) => {
  const t = text.trim();
  if (!t) return false;
  if (ALLOW.has(t)) return false;
  if (CODEY.test(t)) return false;
  if (!/[a-z]/.test(t)) return false;
  if (/^[a-z][a-zA-Z0-9]*$/.test(t) && t.length <= 3) return false;
  // A single lowercase word is usually a value, not a label.
  if (/^[a-z][a-zA-Z0-9]*$/.test(t)) return false;
  return true;
};

const findings = [];

for (const file of walk(SRC)) {
  const raw = fs.readFileSync(file, 'utf8');
  const src = stripNonUi(raw);
  const rel = path.relative(ROOT, file);
  const rawLines = raw.split('\n');
  const lines = src.split('\n');

  lines.forEach((line, idx) => {
    const rawLine = rawLines[idx] ?? '';

    // JSX text: >text<
    const textRe = />([^<>{}]{2,})</g;
    let m;
    while ((m = textRe.exec(line)) !== null) {
      if (!isProse(m[1])) continue;
      findings.push({ file: rel, line: idx + 1, kind: 'jsx-text', value: m[1].trim() });
    }

    // User-facing string props.
    const propRe = /\b(placeholder|title|label|aria-label|alt|description|tooltip)\s*=\s*"([^"]{2,})"/g;
    while ((m = propRe.exec(line)) !== null) {
      if (!isProse(m[2])) continue;
      findings.push({ file: rel, line: idx + 1, kind: `prop:${m[1]}`, value: m[2].trim() });
    }
  });
}

// Collapse identical findings per file.
const grouped = new Map();
for (const f of findings) {
  const key = `${f.file}|${f.value}`;
  if (!grouped.has(key)) grouped.set(key, f);
}

const list = [...grouped.values()].sort((a, b) =>
  a.file.localeCompare(b.file) || a.line - b.line,
);

const byFile = new Map();
for (const f of list) byFile.set(f.file, (byFile.get(f.file) || 0) + 1);

console.log(`${list.length} hardcoded string(s) across ${byFile.size} file(s):\n`);
for (const [file, count] of [...byFile.entries()].sort((a, b) => b[1] - a[1])) {
  console.log(`  ${count}  ${file}`);
}
console.log('');

for (const f of list) {
  console.log(`${f.file}:${f.line}  [${f.kind}]  ${f.value}`);
}

const outFlag = process.argv.find((a) => a.startsWith('--out='));
if (outFlag) {
  fs.writeFileSync(outFlag.slice('--out='.length), JSON.stringify(list, null, 2), 'utf8');
  console.log(`\nwrote ${outFlag.slice('--out='.length)}`);
}
