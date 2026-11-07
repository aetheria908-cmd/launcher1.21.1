'use strict';
// Cifra un Texture Pack PRIVADO (AES-256-GCM) y crea su ficha (.aep.json). El .zip original NO debe subirse al repositorio.
//
//  node tools/encrypt-pack.js MiPack.zip --id mi-pack --category required [--name "Mi Pack"] [--description "..."]
//       [--priority 10] [--optifine | --no-optifine] [--release-url https://github.com/.../MiPack.aep]
//  node tools/encrypt-pack.js --gen-key        → genera una clave nueva (guárdala como secreto PACK_KEY)
//
// Clave: variable PACK_KEY (base64, 32 bytes) o config/secrets.json {"packKey": "..."}.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { inspectPack } = require('../src/main/core/packcheck');
const pc = require('../src/main/core/packcrypto');
const { isSafeFileName } = require('../src/main/core/util');

const argv = process.argv.slice(2);
if (argv.includes('--gen-key')) { console.log(crypto.randomBytes(32).toString('base64')); process.exit(0); }
const flag = (n) => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined; };
const src = argv.find((a) => !a.startsWith('--') && /\.zip$/i.test(a));
const die = (m) => { console.error('✘ ' + m); process.exit(1); };
if (!src || !fs.existsSync(src)) die('Uso: node tools/encrypt-pack.js <pack.zip> --id <id> --category required|optional [opciones]');

let keyB64 = process.env.PACK_KEY;
if (!keyB64) { try { keyB64 = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'config', 'secrets.json'), 'utf8')).packKey; } catch { /* sin archivo */ } }
const key = pc.keyFromBase64(keyB64);
if (!key) die('Falta la clave: define PACK_KEY (base64 de 32 bytes) o config/secrets.json. Genera una con --gen-key.');

const category = flag('category') || 'required';
if (!['required', 'optional'].includes(category)) die('--category debe ser required u optional');
const id = flag('id') || path.basename(src, '.zip').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
const packFile = path.basename(src);
if (!isSafeFileName(packFile)) die(`Nombre de archivo no permitido: "${packFile}" (letras, números, espacios, . _ - ( ) +)`);

const insp = inspectPack(src);
for (const w of insp.warnings) console.warn('AVISO:', w);
if (!insp.ok) die('Pack inválido: ' + insp.errors.join(' | '));

const plain = fs.readFileSync(src);
const plainSha256 = crypto.createHash('sha256').update(plain).digest('hex');
const release = flag('release-url');
const base = packFile.replace(/\.zip$/i, '');
const resDir = path.join(__dirname, '..', 'resources', 'resourcepacks', category);
const blobDir = release ? path.join(__dirname, '..', 'release-assets') : resDir;
fs.mkdirSync(resDir, { recursive: true }); fs.mkdirSync(blobDir, { recursive: true });
const blob = path.join(blobDir, base + '.aep');

(async () => {
  await pc.encryptFile(src, blob, key, pc.aad(id, plainSha256));
  const enc = fs.readFileSync(blob);
  const optifine = argv.includes('--optifine') ? true : argv.includes('--no-optifine') ? false : insp.info.usesOptifine;
  const meta = { id, name: flag('name') || base.replace(/[_]+/g, ' '), description: flag('description') || '', encrypted: true, packFile, plainSha256, plainSize: plain.length,
    packFormat: insp.info.packFormat, requiresOptifine: optifine };
  if (flag('priority') !== undefined) meta.priority = Number(flag('priority'));
  if (release) Object.assign(meta, { external: true, url: release, sha256: crypto.createHash('sha256').update(enc).digest('hex'), size: enc.length });
  fs.writeFileSync(path.join(resDir, base + '.aep.json'), JSON.stringify(meta, null, 2) + '\n');
  // Comprobación: se descifra y se compara
  const chk = blob + '.check'; await pc.decryptFile(blob, chk, key, pc.aad(id, plainSha256));
  const ok = crypto.createHash('sha256').update(fs.readFileSync(chk)).digest('hex') === plainSha256; fs.rmSync(chk, { force: true });
  if (!ok) die('La verificación de descifrado falló');
  console.log(`✔ ${base}.aep  (${(enc.length / 1048576).toFixed(2)} MB)  →  ${path.relative(process.cwd(), blob)}`);
  console.log(`  ficha: ${path.relative(process.cwd(), path.join(resDir, base + '.aep.json'))}   id=${id}  categoría=${category}  OptiFine=${optifine ? 'sí' : 'no'}${release ? '  (alojado en Release)' : ''}`);
})().catch((e) => die(e.message));
