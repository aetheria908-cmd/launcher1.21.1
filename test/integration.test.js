'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs'), os = require('os'), path = require('path'), http = require('http'), crypto = require('crypto');
const { makeZip } = require('./helpers');
const sha = (b, a = 'sha256') => crypto.createHash(a).update(b).digest('hex');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'aeth-int-'));
const H = require('../src/main/core/http');
const Progress = require('../src/main/core/progress');
const packs = require('../src/main/core/packs');
const opt = require('../src/main/core/options');
const launch = require('../src/main/core/launch');
const { ensureFiles } = require('../src/main/core/files');
const mc = require('../src/main/core/minecraft');

// Servidor HTTP local con soporte de Range y archivos mutables.
const files = {};
let hits = 0;
const srv = http.createServer((req, res) => {
  const f = files[req.url.split('?')[0]];
  if (!f) { res.writeHead(404).end(); return; }
  hits++;
  const m = /bytes=(\d+)-/.exec(req.headers.range || '');
  if (m) { const s = +m[1]; res.writeHead(206, { 'Content-Length': f.length - s }); res.end(f.subarray(s)); }
  else { res.writeHead(200, { 'Content-Length': f.length }); res.end(f); }
});
let base;
test.before(async () => { await new Promise((r) => srv.listen(0, '127.0.0.1', r)); base = `http://127.0.0.1:${srv.address().port}`; });
test.after(() => srv.close());

test('descarga: verifica SHA y tamaño; reanuda desde .part', async () => {
  const data = crypto.randomBytes(300000);
  files['/a.bin'] = data;
  const dest = path.join(tmp, 'dl', 'a.bin');
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest + '.part', data.subarray(0, 100000)); // descarga interrumpida previa
  let counted = 0;
  await H.download(base + '/a.bin', dest, { sha256: sha(data), size: data.length, onBytes: (b) => { counted += b; } });
  assert.deepStrictEqual(fs.readFileSync(dest), data);
  assert.strictEqual(counted, data.length); // los 100000 ya descargados cuentan una sola vez
  assert.ok(!fs.existsSync(dest + '.part'));
});
test('descarga: archivo corrupto se rechaza y se borra', async () => {
  const data = crypto.randomBytes(5000);
  files['/c.bin'] = data;
  const dest = path.join(tmp, 'dl', 'c.bin');
  await assert.rejects(H.download(base + '/c.bin', dest, { sha256: 'f'.repeat(64), size: data.length }), /SHA/);
  assert.ok(!fs.existsSync(dest) && !fs.existsSync(dest + '.part'));
  await assert.rejects(H.download(base + '/nada.bin', dest), /404/);
});
test('descarga: cancelación', async () => {
  files['/big.bin'] = crypto.randomBytes(1000);
  const ac = new AbortController(); ac.abort();
  await assert.rejects(H.download(base + '/big.bin', path.join(tmp, 'dl', 'big.bin'), { signal: ac.signal }), /cancelada/);
});
test('seguridad HTTP: rechaza http remoto y hosts no permitidos', async () => {
  await assert.rejects(H.getJson('http://example.com/x'), /HTTPS/);
  H.setAllowedHosts(['github.com']);
  await assert.rejects(H.getJson('https://evil.example.org/x', { strict: true }), /no permitido/);
  H.setAllowedHosts([]);
});
test('ensureFiles: solo descarga lo que falta o está dañado', async () => {
  const d1 = crypto.randomBytes(2000), d2 = crypto.randomBytes(3000);
  files['/f1'] = d1; files['/f2'] = d2;
  const list = [{ url: base + '/f1', dest: path.join(tmp, 'ef', 'f1'), sha1: sha(d1, 'sha1'), size: d1.length }, { url: base + '/f2', dest: path.join(tmp, 'ef', 'f2'), sha1: sha(d2, 'sha1'), size: d2.length }];
  const pr = new Progress(() => {});
  assert.strictEqual(await ensureFiles(list, { progress: pr }), 2);
  assert.strictEqual(await ensureFiles(list, { progress: pr }), 0);
  fs.writeFileSync(list[1].dest, crypto.randomBytes(3000)); // mismo tamaño, contenido corrupto
  assert.strictEqual(await ensureFiles(list, { progress: pr }), 0, 'sin full no se hashea');
  assert.strictEqual(await ensureFiles(list, { progress: pr, full: true }), 1, 'con full se repara');
  assert.deepStrictEqual(fs.readFileSync(list[1].dest), d2);
});

function fakePaths(root) {
  const e = (p) => { fs.mkdirSync(p, { recursive: true }); return p; };
  return { game: () => e(path.join(root, '.minecraft')), packs: () => e(path.join(root, '.minecraft', 'resourcepacks')), state: () => e(path.join(root, 'state')) };
}
test('packs: descarga, actualización de uno modificado, opcionales, activación en options.txt', async () => {
  const paths = fakePaths(path.join(tmp, 'g1'));
  const mk = (name, fmt) => { const f = path.join(tmp, name); makeZip(f, { 'pack.mcmeta': `{"pack":{"pack_format":${fmt},"description":"${name}"}}`, 'assets/minecraft/x.txt': name + Math.random() }); return fs.readFileSync(f); };
  const reqA = mk('Base.zip', 34), opt1 = mk('Extra.zip', 34);
  files['/Base.zip'] = reqA; files['/Extra.zip'] = opt1;
  const entry = (id, file, buf, cat, priority) => ({ id, name: id, file, url: `${base}/${file}`, sha256: sha(buf), size: buf.length, version: '1', category: cat, priority, description: '', updatedAt: '', history: [] });
  let manifest = { resourcepacks: [entry('base', 'Base.zip', reqA, 'required', 0), entry('extra', 'Extra.zip', opt1, 'optional', 5)] };
  let settings = { optionalPacks: {}, packPriority: {}, graphics: { apply: false }, lang: 'es_es' };
  const pr = new Progress(() => {});
  fs.writeFileSync(path.join(paths.game(), 'options.txt'), 'fov:0.7\nresourcePacks:["vanilla","file/Personal.zip"]\n');

  let r = await packs.sync({ manifest, settings, paths, progress: pr });
  assert.deepStrictEqual(r.changed, ['base']); assert.ok(fs.existsSync(path.join(paths.packs(), 'Base.zip'))); assert.ok(!fs.existsSync(path.join(paths.packs(), 'Extra.zip')), 'el opcional no se baja solo');
  packs.applyToOptions({ manifest, settings, paths });
  let lines = opt.parse(fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8'));
  assert.deepStrictEqual(opt.packList(opt.get(lines, 'resourcePacks')), ['vanilla', 'file/Personal.zip', 'file/Base.zip']);
  assert.strictEqual(opt.get(lines, 'fov'), '0.7', 'no destruye preferencias');

  hits = 0; r = await packs.sync({ manifest, settings, paths, progress: pr });
  assert.deepStrictEqual(r.changed, []); assert.strictEqual(hits, 0, 'sin cambios no descarga nada');

  settings = { ...settings, optionalPacks: { extra: { installed: true, enabled: true } } };
  await packs.sync({ manifest, settings, paths, progress: pr });
  packs.applyToOptions({ manifest, settings, paths });
  lines = opt.parse(fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8'));
  assert.deepStrictEqual(opt.packList(opt.get(lines, 'resourcePacks')).slice(-2), ['file/Base.zip', 'file/Extra.zip'], 'prioridad por orden');

  const reqB = mk('Base.zip', 34); files['/Base.zip'] = reqB; // el admin publica una versión nueva
  manifest = { resourcepacks: [entry('base', 'Base.zip', reqB, 'required', 0), manifest.resourcepacks[1]] };
  hits = 0; r = await packs.sync({ manifest, settings, paths, progress: pr });
  assert.deepStrictEqual(r.changed, ['base']); assert.strictEqual(hits, 1, 'solo se baja el modificado');
  assert.strictEqual(sha(fs.readFileSync(path.join(paths.packs(), 'Base.zip'))), manifest.resourcepacks[0].sha256);

  fs.writeFileSync(path.join(paths.packs(), 'Base.zip'), Buffer.concat([reqB.subarray(0, reqB.length - 5), Buffer.from('XXXXX')])); // corrupción
  const lst = await packs.list({ manifest, settings, paths, deep: true });
  assert.ok(lst.find((p) => p.id === 'base').corrupt);
  await packs.sync({ manifest, settings, paths, progress: pr });
  assert.ok(!(await packs.list({ manifest, settings, paths, deep: true })).find((p) => p.id === 'base').corrupt, 'archivo corrupto reparado');

  manifest = { resourcepacks: [manifest.resourcepacks[0]] }; // el admin retira el opcional
  await packs.sync({ manifest, settings, paths, progress: pr });
  assert.ok(!fs.existsSync(path.join(paths.packs(), 'Extra.zip')), 'pack retirado se elimina');
});
test('packs: un pack inválido publicado no se activa', async () => {
  const paths = fakePaths(path.join(tmp, 'g2'));
  const f = path.join(tmp, 'roto.zip'); makeZip(f, { 'Carpeta/pack.mcmeta': '{"pack":{"pack_format":34}}', 'Carpeta/assets/a': 'a' });
  const buf = fs.readFileSync(f); files['/roto.zip'] = buf;
  const manifest = { resourcepacks: [{ id: 'roto', name: 'roto', file: 'roto.zip', url: base + '/roto.zip', sha256: sha(buf), size: buf.length, version: '1', category: 'required', priority: 0 }] };
  const settings = { optionalPacks: {}, packPriority: {}, graphics: { apply: false }, lang: 'es_es' };
  const r = await packs.sync({ manifest, settings, paths, progress: new Progress(() => {}) });
  assert.strictEqual(r.issues.length, 1);
  packs.applyToOptions({ manifest, settings, paths });
  assert.deepStrictEqual(opt.packList(opt.get(opt.parse(fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8')), 'resourcePacks')), ['vanilla']);
});

// Versión sintética con la misma forma que el JSON oficial de 1.21.1
function writeVersion(gameDir, id, json) { const d = path.join(gameDir, 'versions', id); fs.mkdirSync(d, { recursive: true }); fs.writeFileSync(path.join(d, id + '.json'), JSON.stringify(json)); fs.writeFileSync(path.join(d, id + '.jar'), 'jar'); }
function lib(gameDir, p) { const f = path.join(gameDir, 'libraries', p); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, 'x'); }
test('comando de lanzamiento (estilo 1.21.1) + OptiFine heredando', () => {
  const g = path.join(tmp, 'g3'); fs.mkdirSync(g, { recursive: true });
  const art = (p) => ({ artifact: { path: p, url: 'https://x/' + p, sha1: 'a', size: 1 } });
  writeVersion(g, '1.21.1', { id: '1.21.1', type: 'release', mainClass: 'net.minecraft.client.main.Main', assetIndex: { id: '17' }, downloads: { client: { url: 'u', sha1: 'a', size: 1 } },
    libraries: [{ name: 'a:b:1', downloads: art('a/b/1/b-1.jar') }, { name: 'o:l:1:natives-windows', downloads: art('o/l/1/l-1-natives-windows.jar'), rules: [{ action: 'allow', os: { name: 'windows' } }] },
      { name: 'o:l:1:natives-linux', downloads: art('o/l/1/l-1-natives-linux.jar'), rules: [{ action: 'allow', os: { name: 'linux' } }] }],
    arguments: { jvm: [{ rules: [{ action: 'allow', os: { name: 'windows' } }], value: '-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump' }, '-Djava.library.path=${natives_directory}', '-Dminecraft.launcher.brand=${launcher_name}', '-cp', '${classpath}'],
      game: ['--username', '${auth_player_name}', '--version', '${version_name}', '--gameDir', '${game_directory}', '--assetsDir', '${assets_root}', '--assetIndex', '${assets_index_name}', '--uuid', '${auth_uuid}', '--accessToken', '${auth_access_token}', '--clientId', '${clientid}', '--xuid', '${auth_xuid}', '--userType', '${user_type}', '--versionType', '${version_type}',
        { rules: [{ action: 'allow', features: { has_custom_resolution: true } }], value: ['--width', '${resolution_width}', '--height', '${resolution_height}'] }] } });
  lib(g, 'a/b/1/b-1.jar'); lib(g, 'o/l/1/l-1-natives-windows.jar'); lib(g, 'o/l/1/l-1-natives-linux.jar');
  const base = { versionId: '1.21.1', gameDir: g, javaExe: 'java.exe', auth: { name: 'Steve', uuid: 'u-u', token: '0', userType: 'legacy' }, ramMb: { min: 1024, max: 4096 }, server: { host: 'localhost', port: 25565 }, launcherVersion: '1.0.0' };
  const c = launch.buildCommand({ ...base, settings: { width: 0, height: 0, fullscreen: false, jvmArgs: '-Dfoo=bar -javaagent:x.jar' } });
  const a = c.args;
  assert.strictEqual(c.rejectedJvm[0], '-javaagent:x.jar');
  assert.ok(a.includes('-Xmx4096M') && a.includes('-Dfoo=bar') && !a.includes('-javaagent:x.jar'));
  const cp = a[a.indexOf('-cp') + 1];
  assert.ok(cp.includes('natives-windows') && !cp.includes('natives-linux') && cp.endsWith('1.21.1.jar'));
  assert.ok(!a.includes('--clientId') && !a.includes('--xuid'), 'flags vacíos eliminados');
  assert.ok(!a.includes('--width'));
  assert.deepStrictEqual(a.slice(a.indexOf('--quickPlayMultiplayer')), ['--quickPlayMultiplayer', 'localhost:25565']);
  assert.strictEqual(a[a.indexOf('--username') + 1], 'Steve');
  assert.ok(a.indexOf('net.minecraft.client.main.Main') < a.indexOf('--username'));
  const c2 = launch.buildCommand({ ...base, server: null, settings: { width: 1280, height: 720, fullscreen: true, jvmArgs: '' } });
  assert.ok(c2.args.includes('--width') && c2.args.includes('--fullscreen') && !c2.args.includes('--quickPlayMultiplayer'));
  // OptiFine: versión hija con inheritsFrom
  lib(g, 'optifine/OptiFine/1.21.1_HD_U_J1/OptiFine-1.21.1_HD_U_J1.jar'); lib(g, 'optifine/launchwrapper-of/2.3/launchwrapper-of-2.3.jar');
  writeVersion(g, '1.21.1-OptiFine_HD_U_J1', { id: '1.21.1-OptiFine_HD_U_J1', inheritsFrom: '1.21.1', type: 'release', mainClass: 'net.minecraft.launchwrapper.Launch',
    libraries: [{ name: 'optifine:OptiFine:1.21.1_HD_U_J1' }, { name: 'optifine:launchwrapper-of:2.3' }], arguments: { game: ['--tweakClass', 'optifine.OptiFineTweaker'] } });
  const o = launch.buildCommand({ ...base, versionId: '1.21.1-OptiFine_HD_U_J1', settings: { width: 0, height: 0, jvmArgs: '' } }).args;
  assert.ok(o.includes('net.minecraft.launchwrapper.Launch') && o.includes('optifine.OptiFineTweaker') && o.includes('--username'));
  assert.ok(o[o.indexOf('-cp') + 1].includes('launchwrapper-of'));
  assert.ok(o[o.indexOf('-cp') + 1].endsWith(path.join('versions', '1.21.1', '1.21.1.jar')));
});
test('archivos de bibliotecas: respeta reglas y bloquea rutas maliciosas', () => {
  const g = path.join(tmp, 'g4');
  const ok = mc.libraryFiles({ libraries: [{ downloads: { artifact: { path: 'a/b.jar', url: 'https://x', sha1: 'a', size: 1 } } }, { rules: [{ action: 'allow', os: { name: 'osx' } }], downloads: { artifact: { path: 'mac.jar', url: 'https://x', sha1: 'a', size: 1 } } }] }, g);
  assert.strictEqual(ok.length, 1);
  assert.throws(() => mc.libraryFiles({ libraries: [{ downloads: { artifact: { path: '../../evil.jar', url: 'https://x', sha1: 'a', size: 1 } } }] }, g), /insegura/);
});
