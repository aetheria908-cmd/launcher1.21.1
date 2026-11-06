'use strict';
// Uso: node tools/validate-pack.js <pack.zip> [--json]
const fs = require('fs');
const crypto = require('crypto');
const { inspectPack, TARGET_FORMAT } = require('../src/main/core/packcheck');

const file = process.argv[2];
if (!file || !fs.existsSync(file)) { console.error('Uso: node tools/validate-pack.js <pack.zip> [--json]'); process.exit(2); }
const r = inspectPack(file);
const buf = fs.readFileSync(file);
r.info.size = buf.length;
r.info.sha256 = crypto.createHash('sha256').update(buf).digest('hex');
if (process.argv.includes('--json')) console.log(JSON.stringify(r, null, 2));
else {
  console.log(`Archivo:        ${file}\nTamaño:         ${(buf.length / 1048576).toFixed(2)} MB\nSHA-256:        ${r.info.sha256}`);
  console.log(`pack_format:    ${r.info.packFormat ?? '?'}  (Minecraft ${r.info.minecraft || '?'}; objetivo 1.21.1 = ${TARGET_FORMAT})`);
  console.log(`Descripción:    ${r.info.description || '-'}\nNamespaces:     ${(r.info.namespaces || []).join(', ') || '-'}\nArchivos:       ${r.info.fileCount}  (${((r.info.uncompressedSize || 0) / 1048576).toFixed(1)} MB descomprimido)`);
  for (const e of r.errors) console.log('ERROR :', e);
  for (const w of r.warnings) console.log('AVISO :', w);
  console.log(r.ok ? '\n✔ Pack válido' : '\n✘ Pack NO válido');
}
process.exit(r.ok ? 0 : 1);
