/**
 * Audit i18n coverage.
 *
 * Scans the frontend for every `t('...')` / `t("...")` call and reports:
 *   - keys used in code but missing from vi.json / en.json
 *   - keys present in one file but missing from the other
 *   - keys defined in the JSON files but never used in code
 *
 * Usage: node scripts/i18n-audit.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'frontend', 'src');
const I18N = path.join(SRC, 'i18n');

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (/\.(ts|tsx)$/.test(entry.name)) out.push(full);
  }
  return out;
}

/** Flatten a nested i18n object into dotted keys. */
function flatten(obj, prefix = '', out = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out);
    else out[key] = v;
  }
  return out;
}

/** Collect every literal translation key referenced in the source. */
function collectUsedKeys(files) {
  const used = new Map(); // key -> { files, hasDefault }
  // Matches t('key') and t('key', 'fallback'). A key without a fallback
  // renders as the raw key when the translation is missing — those are
  // the ones the user actually sees broken.
  const re = /\bt\(\s*(['"`])([^'"`]+)\1\s*([,)])/g;
  for (const file of files) {
    const src = fs.readFileSync(file, 'utf8');
    let m;
    while ((m = re.exec(src)) !== null) {
      const key = m[2];
      if (!key.includes('.')) continue; // not a namespaced key
      if (!used.has(key)) used.set(key, { files: new Set(), hasDefault: false });
      const entry = used.get(key);
      entry.files.add(path.relative(ROOT, file));
      // A comma right after the closing quote means a fallback follows.
      if (m[3] === ',') entry.hasDefault = true;
    }
  }
  return used;
}

const files = walk(SRC);
const used = collectUsedKeys(files);

const vi = flatten(JSON.parse(fs.readFileSync(path.join(I18N, 'vi.json'), 'utf8')));
const en = flatten(JSON.parse(fs.readFileSync(path.join(I18N, 'en.json'), 'utf8')));

const missingVi = [];
const missingEn = [];
for (const [key, entry] of used) {
  const where = [...entry.files];
  if (!(key in vi)) missingVi.push([key, where, entry.hasDefault]);
  if (!(key in en)) missingEn.push([key, where, entry.hasDefault]);
}

const onlyVi = Object.keys(vi).filter((k) => !(k in en));
const onlyEn = Object.keys(en).filter((k) => !(k in vi));
const unused = [...used.keys()].filter((k) => !(k in vi) && !(k in en));

const outFlag = process.argv.find((a) => a.startsWith('--out='));
if (outFlag) {
  const payload = {
    scannedFiles: files.length,
    usedKeys: used.size,
    missingVi: missingVi.map(([k, w, hd]) => ({ key: k, usedIn: w, hasDefault: hd })),
    missingEn: missingEn.map(([k, w, hd]) => ({ key: k, usedIn: w, hasDefault: hd })),
    onlyVi,
    onlyEn,
    unused,
  };
  fs.writeFileSync(outFlag.slice('--out='.length), JSON.stringify(payload, null, 2), 'utf8');
  console.log(
    `wrote ${outFlag.slice('--out='.length)} — ` +
    `missingVi=${missingVi.length} missingEn=${missingEn.length} ` +
    `onlyVi=${onlyVi.length} onlyEn=${onlyEn.length} unused=${unused.length}`,
  );
  process.exit(0);
}

console.log(`scanned ${files.length} source files, ${used.size} distinct keys used\n`);

console.log(`MISSING IN vi.json (${missingVi.length}):`);
for (const [key, where, hasDefault] of missingVi) {
  console.log(`  ${key}${hasDefault ? '  [has default]' : '  [BROKEN]'}`);
  console.log(`      used in: ${where.join(', ')}`);
}

console.log(`\nMISSING IN en.json (${missingEn.length}):`);
for (const [key, where, hasDefault] of missingEn) {
  console.log(`  ${key}${hasDefault ? '  [has default]' : '  [BROKEN]'}`);
  console.log(`      used in: ${where.join(', ')}`);
}

console.log(`\nIN vi.json BUT NOT en.json (${onlyVi.length}):`);
onlyVi.forEach((k) => console.log(`  ${k}`));

console.log(`\nIN en.json BUT NOT vi.json (${onlyEn.length}):`);
onlyEn.forEach((k) => console.log(`  ${k}`));

console.log(`\nUSED IN CODE BUT IN NEITHER FILE (${unused.length}):`);
unused.forEach((k) => console.log(`  ${k}`));
