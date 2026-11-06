'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
let bad = 0, n = 0;
const walk = (d) => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
    const f = path.join(d, e.name);
    if (e.isDirectory()) walk(f);
    else if (f.endsWith('.js')) { n++; const r = spawnSync(process.execPath, ['--check', f], { encoding: 'utf8' }); if (r.status) { bad++; console.error(r.stderr); } }
  }
};
for (const d of ['src', 'tools', 'test']) walk(path.join(__dirname, '..', d));
for (const j of ['package.json', 'config/launcher.config.json', 'manifest/manifest.base.json', 'manifest/manifest.json', 'news/news.json']) {
  try { JSON.parse(fs.readFileSync(path.join(__dirname, '..', j), 'utf8')); n++; } catch (e) { bad++; console.error('JSON inválido:', j, e.message); }
}
console.log(`${n} archivos revisados, ${bad} con errores`);
process.exit(bad ? 1 : 0);
