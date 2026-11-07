'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs'), os = require('os'), path = require('path'), http = require('http'), crypto = require('crypto');
const { makeZip } = require('./helpers');
const pc = require('../src/main/core/packcrypto');
const packs = require('../src/main/core/packs');
const opt = require('../src/main/core/options');
const Progress = require('../src/main/core/progress');
const { validateManifest } = require('../src/main/core/manifest');
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'aeth-priv-'));
const KEY = crypto.randomBytes(32), KEY_B64 = KEY.toString('base64');

test('cifrado AES-256-GCM: ida y vuelta con un archivo grande', async () => {
  const plain = crypto.randomBytes(3 * 1024 * 1024 + 17), src = path.join(tmp, 'p.zip'), enc = path.join(tmp, 'p.aep'), out = path.join(tmp, 'p.out');
  fs.writeFileSync(src, plain);
  const a = pc.aad('mi-pack', sha(plain));
  await pc.encryptFile(src, enc, KEY, a);
  const e = fs.readFileSync(enc);
  assert.strictEqual(e.length, plain.length + 4 + 12 + 16);
  assert.ok(!e.includes(plain.subarray(1000, 1064)), 'el contenido no aparece en claro');
  assert.strictEqual(e.subarray(0, 4).toString(), 'AEP1');
  await pc.decryptFile(enc, out, KEY, a);
  assert.deepStrictEqual(fs.readFileSync(out), plain);
});
test('cifrado: detecta manipulación, clave errónea, intercambio de packs y truncado', async () => {
  const plain = crypto.randomBytes(100000), src = path.join(tmp, 'q.zip'), enc = path.join(tmp, 'q.aep'), out = path.join(tmp, 'q.out');
  fs.writeFileSync(src, plain);
  const a = pc.aad('q', sha(plain));
  await pc.encryptFile(src, enc, KEY, a);
  const good = fs.readFileSync(enc);
  const bad = Buffer.from(good); bad[5000] ^= 1; fs.writeFileSync(enc, bad);
  await assert.rejects(pc.decryptFile(enc, out, KEY, a), /manipulado|clave/);
  assert.ok(!fs.existsSync(out) && !fs.existsSync(out + '.dec'), 'no deja restos');
  fs.writeFileSync(enc, good);
  await assert.rejects(pc.decryptFile(enc, out, crypto.randomBytes(32), a), /clave/);
  await assert.rejects(pc.decryptFile(enc, out, KEY, pc.aad('otro-id', sha(plain))), /clave|manipulado/);
  fs.writeFileSync(enc, good.subarray(0, good.length - 40)); await assert.rejects(pc.decryptFile(enc, out, KEY, a));
  fs.writeFileSync(enc, Buffer.from('basura que no es un pack cifrado, ni de lejos, de verdad')); await assert.rejects(pc.decryptFile(enc, out, KEY, a), /cabecera/);
  assert.strictEqual(pc.keyFromBase64('abc'), null);
  assert.ok(pc.keyFromBase64(KEY_B64).equals(KEY));
});

// ---- Sincronización de packs privados y reglas de OptiFine
const files = {}; let hits = [];
const srv = http.createServer((req, res) => { const f = files[req.url]; hits.push(req.url); if (!f) { res.writeHead(404).end(); return; } res.writeHead(200, { 'Content-Length': f.length }); res.end(f); });
let base;
test.before(async () => { await new Promise((r) => srv.listen(0, '127.0.0.1', r)); base = 'http://127.0.0.1:' + srv.address().port; });
test.after(() => srv.close());
const fakePaths = (root) => { const e = (p) => { fs.mkdirSync(p, { recursive: true }); return p; }; return { game: () => e(path.join(root, '.minecraft')), packs: () => e(path.join(root, '.minecraft', 'resourcepacks')), state: () => e(path.join(root, 'state')), cache: () => e(path.join(root, 'cache')) }; };
const settings = { optionalPacks: {}, packPriority: {}, graphics: { apply: false }, lang: 'es_es' };
const pr = () => new Progress(() => {});

async function makePrivate(id, packFile, fmt = 34) {
  const z = path.join(tmp, packFile); makeZip(z, { 'pack.mcmeta': `{"pack":{"pack_format":${fmt},"description":"${id}"}}`, 'assets/minecraft/a.txt': id + crypto.randomBytes(8).toString('hex') });
  const plain = fs.readFileSync(z), ps = sha(plain), blob = path.join(tmp, id + '.aep');
  await pc.encryptFile(z, blob, KEY, pc.aad(id, ps));
  const enc = fs.readFileSync(blob); files['/' + id + '.aep'] = enc;
  return { id, name: id, category: 'required', version: '1', file: id + '.aep', url: base + '/' + id + '.aep', sha256: sha(enc), size: enc.length, encrypted: true, packFile, plainSha256: ps, priority: 0, history: [] };
}

test('packs privados: se descargan cifrados, se descifran, se activan y se borran al salir', async () => {
  process.env.AETHERIA_PACK_KEY = KEY_B64;
  const paths = fakePaths(path.join(tmp, 'g1'));
  const p = await makePrivate('razas', 'Razas.zip');
  const manifest = { resourcepacks: [p] };
  const r = await packs.sync({ manifest, settings, paths, progress: pr() });
  assert.deepStrictEqual([r.changed, r.issues], [['razas'], []]);
  const blob = path.join(paths.cache(), 'private', 'razas.aep'), plainZip = path.join(paths.packs(), 'Razas.zip');
  assert.ok(fs.existsSync(blob) && fs.existsSync(plainZip));
  assert.strictEqual(sha(fs.readFileSync(plainZip)), p.plainSha256);
  assert.ok(!fs.readFileSync(blob).includes(Buffer.from('pack_format')), 'en caché solo hay datos cifrados');
  packs.applyToOptions({ manifest, settings, paths });
  assert.deepStrictEqual(opt.packList(opt.get(opt.parse(fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8')), 'resourcePacks')), ['vanilla', 'file/Razas.zip']);
  packs.cleanupPrivate(manifest, paths); // al cerrar Minecraft
  assert.ok(!fs.existsSync(plainZip) && fs.existsSync(blob), 'el zip descifrado desaparece; el cifrado se conserva');
  const lst = (await packs.list({ manifest, settings, paths }))[0];
  assert.ok(lst.encrypted && lst.installed && !lst.corrupt);
  assert.ok(packs.activeInOptions(paths, manifest).entries.find((e) => e.entry === 'file/Razas.zip').private);
  hits = [];
  await packs.sync({ manifest, settings, paths, progress: pr() }); // siguiente arranque: sin descargas, vuelve a descifrar
  assert.strictEqual(hits.length, 0); assert.ok(fs.existsSync(plainZip));
  // el administrador publica una versión nueva
  const p2 = await makePrivate('razas', 'Razas.zip'); 
  const r2 = await packs.sync({ manifest: { resourcepacks: [p2] }, settings, paths, progress: pr() });
  assert.deepStrictEqual(r2.changed, ['razas']); assert.strictEqual(sha(fs.readFileSync(plainZip)), p2.plainSha256);
});
test('packs privados: sin clave → error claro; archivo manipulado → no se activa', async () => {
  const paths = fakePaths(path.join(tmp, 'g2'));
  const p = await makePrivate('secreto', 'Secreto.zip');
  delete process.env.AETHERIA_PACK_KEY;
  await assert.rejects(packs.sync({ manifest: { resourcepacks: [p] }, settings, paths, progress: pr() }), /clave/);
  process.env.AETHERIA_PACK_KEY = KEY_B64;
  const t = Buffer.from(files['/secreto.aep']); t[200] ^= 0xff; files['/secreto.aep'] = t; // alguien cambia el archivo y también el manifiesto
  const pt = { ...p, sha256: sha(t) };
  const paths2 = fakePaths(path.join(tmp, 'g3'));
  const r = await packs.sync({ manifest: { resourcepacks: [pt] }, settings, paths: paths2, progress: pr() });
  assert.strictEqual(r.issues.length, 1); assert.match(r.issues[0].errors[0], /manipulado|clave/);
  packs.applyToOptions({ manifest: { resourcepacks: [pt] }, settings, paths: paths2 });
  assert.deepStrictEqual(opt.packList(opt.get(opt.parse(fs.readFileSync(path.join(paths2.game(), 'options.txt'), 'utf8')), 'resourcePacks')), ['vanilla']);
  assert.ok(!fs.existsSync(path.join(paths2.packs(), 'Secreto.zip')));
});
test('packs que requieren OptiFine: solo se instalan y activan con OptiFine', async () => {
  const paths = fakePaths(path.join(tmp, 'g4'));
  const z = path.join(tmp, 'Opti.zip'); makeZip(z, { 'pack.mcmeta': '{"pack":{"pack_format":34,"description":"o"}}', 'assets/minecraft/optifine/cit/a.properties': 'x' });
  const buf = fs.readFileSync(z); files['/Opti.zip'] = buf;
  const m = { resourcepacks: [{ id: 'opti', name: 'o', category: 'required', version: '1', file: 'Opti.zip', url: base + '/Opti.zip', sha256: sha(buf), size: buf.length, requiresOptifine: true, priority: 0, history: [] }] };
  hits = [];
  await packs.sync({ manifest: m, settings, paths, progress: pr(), ctx: { optifine: false } });
  assert.strictEqual(hits.length, 0, 'sin OptiFine no se descarga'); assert.ok(!fs.existsSync(path.join(paths.packs(), 'Opti.zip')));
  assert.ok((await packs.list({ manifest: m, settings, paths, ctx: { optifine: false } }))[0].blocked);
  packs.applyToOptions({ manifest: m, settings, paths, ctx: { optifine: false } });
  assert.ok(!fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8').includes('Opti.zip'));
  await packs.sync({ manifest: m, settings, paths, progress: pr(), ctx: { optifine: true } });
  packs.applyToOptions({ manifest: m, settings, paths, ctx: { optifine: true } });
  assert.ok(fs.existsSync(path.join(paths.packs(), 'Opti.zip')) && fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8').includes('file/Opti.zip'));
  await packs.sync({ manifest: m, settings, paths, progress: pr(), ctx: { optifine: false } }); // vuelve a vanilla
  assert.ok(!fs.existsSync(path.join(paths.packs(), 'Opti.zip')), 'se retira al jugar sin OptiFine');
});
test('manifiesto: validación de packs cifrados', () => {
  const ok = { id: 'a', category: 'required', version: '1', file: 'A.aep', url: 'https://github.com/x/A.aep', sha256: 'a'.repeat(64), size: 5, encrypted: true, packFile: 'A.zip', plainSha256: 'b'.repeat(64), requiresOptifine: true };
  const r = validateManifest({ schemaVersion: 1, resourcepacks: [ok] }); assert.ok(r.ok, r.errors.join()); assert.ok(r.manifest.resourcepacks[0].encrypted && r.manifest.resourcepacks[0].requiresOptifine);
  assert.ok(!validateManifest({ schemaVersion: 1, resourcepacks: [{ ...ok, packFile: '../x.zip' }] }).ok);
  assert.ok(!validateManifest({ schemaVersion: 1, resourcepacks: [{ ...ok, file: 'A.zip' }] }).ok);
  assert.ok(!validateManifest({ schemaVersion: 1, resourcepacks: [ok, { ...ok, id: 'b', file: 'B.aep' }] }).ok, 'dos packs no pueden usar el mismo nombre en resourcepacks');
});
