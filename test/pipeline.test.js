'use strict';
// Flujo completo de JUGAR con un "Mojang" y un "GitHub" falsos y un electron simulado.
const test = require('node:test');
const { beforeEach } = require('node:test');
const assert = require('node:assert');
const fs = require('fs'), os = require('os'), path = require('path'), http = require('http'), crypto = require('crypto'), Module = require('module');
const { makeZip } = require('./helpers');
const sha = (b, a = 'sha256') => crypto.createHash(a).update(b).digest('hex');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'aeth-pipe-'));
const dirs = { appData: path.join(tmp, 'appData'), userData: path.join(tmp, 'userData'), appDir: path.join(tmp, 'app') };
for (const d of Object.values(dirs)) fs.mkdirSync(d, { recursive: true });
const electronMock = {
  app: { getPath: (n) => dirs[n] || tmp, getAppPath: () => dirs.appDir },
  safeStorage: { isEncryptionAvailable: () => true, encryptString: (s) => Buffer.from('ENC:' + s), decryptString: (b) => b.toString().slice(4) },
};
const origLoad = Module._load;
Module._load = function (req, ...rest) { return req === 'electron' ? electronMock : origLoad.call(this, req, ...rest); };

const json = (o) => Buffer.from(JSON.stringify(o));
const routes = {}; const hits = [];
let base;
const srv = http.createServer((req, res) => {
  const f = routes[req.url.split('?')[0]];
  hits.push(req.url);
  if (!f) { res.writeHead(404).end(); return; }
  res.writeHead(200, { 'Content-Length': f.length }); res.end(f);
});

let game, remote, accounts, settings, paths, events;
const emit = (ch, p) => events.push([ch, p]);
const argsFile = path.join(tmp, 'java-args.txt');

test.before(async () => {
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${srv.address().port}`;
  process.env.AETHERIA_TEST_VERSION_MANIFEST = base + '/mc/version_manifest_v2.json';
  process.env.AETHERIA_TEST_ASSET_HOST = base + '/assets';
  process.env.AETHERIA_TEST_RUNTIME_INDEX = base + '/runtime/all.json';
  // --- "Mojang"
  const clientJar = Buffer.from('CLIENTJAR'), libJar = Buffer.from('LIBJAR'), assetObj = Buffer.from('ASSET-DATA');
  const assetIdx = json({ objects: { 'icons/a.png': { hash: sha(assetObj, 'sha1'), size: assetObj.length } } });
  routes['/mc/client.jar'] = clientJar; routes['/mc/lib.jar'] = libJar; routes['/mc/idx.json'] = assetIdx;
  routes[`/assets/${sha(assetObj, 'sha1').slice(0, 2)}/${sha(assetObj, 'sha1')}`] = assetObj;
  const vjson = json({ id: '1.21.1', type: 'release', mainClass: 'net.minecraft.client.main.Main', javaVersion: { component: 'java-runtime-delta', majorVersion: 21 },
    assetIndex: { id: '17', url: base + '/mc/idx.json', sha1: sha(assetIdx, 'sha1'), size: assetIdx.length },
    downloads: { client: { url: base + '/mc/client.jar', sha1: sha(clientJar, 'sha1'), size: clientJar.length } },
    libraries: [{ name: 'a:b:1', downloads: { artifact: { path: 'a/b/1/b-1.jar', url: base + '/mc/lib.jar', sha1: sha(libJar, 'sha1'), size: libJar.length } } }],
    arguments: { jvm: ['-Djava.library.path=${natives_directory}', '-cp', '${classpath}'],
      game: ['--username', '${auth_player_name}', '--version', '${version_name}', '--gameDir', '${game_directory}', '--assetsDir', '${assets_root}', '--assetIndex', '${assets_index_name}', '--uuid', '${auth_uuid}', '--accessToken', '${auth_access_token}', '--userType', '${user_type}'] } });
  routes['/mc/1.21.1.json'] = vjson;
  routes['/mc/version_manifest_v2.json'] = json({ versions: [{ id: '1.21.1', url: base + '/mc/1.21.1.json', sha1: sha(vjson, 'sha1') }] });
  const javaScript = Buffer.from('#!/bin/sh\nif [ "$1" = "-version" ]; then echo \'openjdk version "21.0.4" 2024-07-16\' >&2; exit 0; fi\nfor a in "$@"; do echo "$a"; done > "$FAKE_JAVA_OUT"\nGD=""; P=""; for a in "$@"; do if [ "$P" = "--gameDir" ]; then GD="$a"; fi; P="$a"; done\nls "$GD/resourcepacks" > "$FAKE_JAVA_OUT.ls"\nsleep 0.4\nexit 0\n');
  const rtManifest = json({ files: { bin: { type: 'directory' }, 'bin/java.exe': { type: 'file', downloads: { raw: { url: base + '/rt/java.exe', sha1: sha(javaScript, 'sha1'), size: javaScript.length } } } } });
  routes['/rt/java.exe'] = javaScript; routes['/rt/manifest.json'] = rtManifest;
  routes['/runtime/all.json'] = json({ 'windows-x64': { 'java-runtime-delta': [{ manifest: { url: base + '/rt/manifest.json', sha1: sha(rtManifest, 'sha1'), size: rtManifest.length } }] }, 'windows-x86': {}, 'windows-arm64': {} });
  // --- "GitHub": packs y manifiesto
  const pk = path.join(tmp, 'Base.zip'); makeZip(pk, { 'pack.mcmeta': '{"pack":{"pack_format":34,"description":"Base"}}', 'assets/minecraft/a.txt': 'a' });
  const zip = fs.readFileSync(pk); routes['/res/Base.zip'] = zip;
  routes['/manifest.json'] = json({ schemaVersion: 1, server: { address: '127.0.0.1', port: 25599, minecraftVersion: '1.21.1' }, urls: { discord: 'https://discord.gg/x' }, auth: { discordClientId: '123456789012345678' },
    resourcepacks: [{ id: 'base', name: 'Base', category: 'required', version: '1.0.0', file: 'Base.zip', url: base + '/res/Base.zip', sha256: sha(zip), size: zip.length }], media: {} });
  routes['/news.json'] = json({ items: [{ id: 'n1', type: 'anuncio', title: 'Hola', summary: 'x', image: 'http://insegura/x.png' }] });
  // --- app
  fs.mkdirSync(path.join(dirs.appDir, 'config'), { recursive: true });
  fs.writeFileSync(path.join(dirs.appDir, 'config', 'launcher.config.json'), JSON.stringify({ brand: { name: 'T', tagline: 't' }, server: { name: 'T', address: 'localhost', port: 25565, minecraftVersion: '1.21.1' }, urls: { discord: 'https://d' },
    remote: { manifestUrl: base + '/manifest.json', newsUrl: base + '/news.json', allowedHosts: ['127.0.0.1'] }, auth: { microsoft: { clientId: '' }, discord: { clientId: '', redirectPort: 53682, guildId: '', tokenExchangeUrl: '' } } }));
  paths = require('../src/main/core/paths'); settings = require('../src/main/core/settings'); remote = require('../src/main/core/remote');
  accounts = require('../src/main/auth/accounts'); game = require('../src/main/game');
  const log = require('../src/main/core/logger'); const store = require('../src/main/core/store');
  log.init(paths.logs()); settings.load(paths.userData()); store.init(paths.userData()); remote.loadBundled(paths.appDir()); game.setVersion('1.0.0');
  const javaPath = path.join(tmp, 'fakejava.sh'); fs.writeFileSync(javaPath, javaScript, { mode: 0o755 });
  process.env.FAKE_JAVA_OUT = argsFile;
  settings.patch({ javaMode: 'custom', javaPath });
});
test.after(() => srv.close());
beforeEach(async () => { while (game && game.isRunning()) await new Promise((r) => setTimeout(r, 30)); }); // un Minecraft a la vez

test('manifiesto remoto: config efectiva y Client ID de Discord desde el manifiesto', async () => {
  await game.refreshRemote();
  assert.strictEqual(remote.getStatus().source, 'remote', JSON.stringify(remote.getStatus().errors));
  assert.deepStrictEqual([remote.server().address, remote.server().port], ['127.0.0.1', 25599]);
  assert.strictEqual(remote.authConfig().discord.clientId, '123456789012345678');
  assert.strictEqual(remote.authConfig().microsoft.clientId, '');
  const n = await remote.news(paths.cache());
  assert.strictEqual(n.items[0].image, '', 'imagen http se descarta');
});
test('JUGAR sin cuenta → error claro', async () => {
  events = [];
  await assert.rejects(game.play(emit), /cuenta/i);
  assert.ok(!game.isBusy() && !game.isRunning());
  assert.strictEqual(events.at(-1)[0], 'task:progress');
});
test('JUGAR completo: instala, sincroniza packs, activa en options.txt y lanza con auto-conexión', async () => {
  accounts.addOffline('Tester_01');
  assert.strictEqual(accounts.list()[0].selected, true);
  events = [];
  const res = await game.play(emit);
  assert.strictEqual(res.auth, 'offline');
  await new Promise((r) => { const iv = setInterval(() => { if (events.some((e) => e[0] === 'game:state' && e[1].state === 'idle' && events.some((x) => x[1] && x[1].state === 'running'))) { clearInterval(iv); r(); } }, 50); });
  const g = paths.game();
  for (const f of ['versions/1.21.1/1.21.1.jar', 'versions/1.21.1/1.21.1.json', 'libraries/a/b/1/b-1.jar', 'resourcepacks/Base.zip', 'launcher_profiles.json']) assert.ok(fs.existsSync(path.join(g, f)), 'falta ' + f);
  assert.ok(fs.readdirSync(path.join(g, 'assets', 'objects')).length === 1, 'asset descargado');
  const opts = fs.readFileSync(path.join(g, 'options.txt'), 'utf8');
  assert.match(opts, /resourcePacks:\["vanilla","file\/Base\.zip"\]/);
  const args = fs.readFileSync(argsFile, 'utf8').split('\n');
  assert.ok(args.includes('Tester_01') && args.includes('legacy'));
  assert.deepStrictEqual(args.slice(args.indexOf('--quickPlayMultiplayer'), args.indexOf('--quickPlayMultiplayer') + 2), ['--quickPlayMultiplayer', '127.0.0.1:25599']);
  assert.ok(args.includes('net.minecraft.client.main.Main'));
  assert.ok(events.some((e) => e[0] === 'task:progress' && e[1].percent === 100), 'la barra llega al 100 %');
});
test('segunda vez: no vuelve a descargar nada (solo manifiesto/índices)', async () => {
  hits.length = 0; events = [];
  await game.play(emit);
  await new Promise((r) => setTimeout(r, 400));
  assert.ok(!hits.some((u) => /client\.jar|lib\.jar|\/assets\/|Base\.zip|java\.exe/.test(u)), 'descargas innecesarias: ' + hits.join(' '));
});
test('modo prueba: abre Minecraft SIN auto-conexión', async () => {
  fs.rmSync(argsFile, { force: true });
  await game.play(emit, { test: true });
  await new Promise((r) => setTimeout(r, 500));
  const args = fs.readFileSync(argsFile, 'utf8').split('\n');
  assert.ok(!args.includes('--quickPlayMultiplayer') && args.includes('Tester_01'));
});
test('un pack modificado en el repositorio se actualiza solo, y el opcional no se baja', async () => {
  const pk = path.join(tmp, 'Base2.zip'); makeZip(pk, { 'pack.mcmeta': '{"pack":{"pack_format":34,"description":"Base v2"}}', 'assets/minecraft/a.txt': 'cambiado' });
  const zip = fs.readFileSync(pk); routes['/res/Base.zip'] = zip;
  const m = JSON.parse(routes['/manifest.json']); m.resourcepacks[0].sha256 = sha(zip); m.resourcepacks[0].size = zip.length; m.resourcepacks[0].version = '1.0.1';
  routes['/manifest.json'] = json(m);
  hits.length = 0;
  await game.update(emit, false);
  assert.strictEqual(hits.filter((u) => u.includes('Base.zip')).length, 1);
  assert.strictEqual(sha(fs.readFileSync(path.join(paths.packs(), 'Base.zip'))), sha(zip));
});
test('mantenimiento y actualización obligatoria bloquean JUGAR', async () => {
  const m = JSON.parse(routes['/manifest.json']); m.maintenance = { enabled: true, message: 'Volvemos a las 20:00' }; routes['/manifest.json'] = json(m);
  await assert.rejects(game.play(emit), /20:00/);
  m.maintenance = { enabled: false }; m.launcher = { minVersion: '9.0.0', message: 'Actualiza' }; routes['/manifest.json'] = json(m);
  await assert.rejects(game.play(emit), /Actualiza/);
});
test('Java administrado: descarga el runtime y verifica hash', async () => {
  const javaMod = require('../src/main/core/java'); const Progress = require('../src/main/core/progress');
  const exe = await javaMod.resolveJava({ settings: { javaMode: 'managed' }, javaVersion: { component: 'java-runtime-delta', majorVersion: 21 }, runtimeDir: paths.runtime(), cacheDir: paths.cache(), progress: new Progress(() => {}), signal: undefined, full: false });
  assert.ok(exe.endsWith(path.join('java-runtime-delta', 'bin', 'java.exe')) && fs.existsSync(exe));
});
test('cancelar una descarga en curso', async () => {
  const slow = crypto.randomBytes(4000); routes['/slow.bin'] = slow;
  const orig = routes['/mc/client.jar']; // fuerza una reinstalación
  fs.rmSync(path.join(paths.game(), 'versions', '1.21.1', '1.21.1.jar'), { force: true });
  const m = JSON.parse(routes['/manifest.json']); m.launcher = { minVersion: '0.0.1' }; routes['/manifest.json'] = json(m);
  const p = game.update(emit, false); game.cancel();
  await assert.rejects(p, /cancelada/i);
  assert.ok(!game.isBusy());
  routes['/mc/client.jar'] = orig;
  await game.update(emit, false); // se recupera sin problemas
  assert.ok(fs.existsSync(path.join(paths.game(), 'versions', '1.21.1', '1.21.1.jar')));
});

test('pack privado en el flujo real: descifrado mientras Minecraft corre y borrado al cerrarlo', async () => {
  const pcrypto = require('../src/main/core/packcrypto');
  const KEY = crypto.randomBytes(32); process.env.AETHERIA_PACK_KEY = KEY.toString('base64');
  const z = path.join(tmp, 'Privado.zip'); makeZip(z, { 'pack.mcmeta': '{"pack":{"pack_format":34,"description":"priv"}}', 'assets/rpg/a.txt': 'secreto' });
  const plain = fs.readFileSync(z), ps = sha(plain), blob = path.join(tmp, 'priv.aep');
  await pcrypto.encryptFile(z, blob, KEY, pcrypto.aad('privado', ps));
  const enc = fs.readFileSync(blob); routes['/res/Privado.aep'] = enc;
  const z2 = path.join(tmp, 'Opti.zip'); makeZip(z2, { 'pack.mcmeta': '{"pack":{"pack_format":34,"description":"o"}}', 'assets/minecraft/optifine/cit/a.properties': 'x' });
  const optiBuf = fs.readFileSync(z2); routes['/res/Opti.zip'] = optiBuf;
  const m = JSON.parse(routes['/manifest.json']); m.maintenance = { enabled: false }; m.launcher = { minVersion: '0.0.1' };
  m.resourcepacks.push({ id: 'privado', name: 'Privado', category: 'required', version: '1', file: 'Privado.aep', url: base + '/res/Privado.aep', sha256: sha(enc), size: enc.length, encrypted: true, packFile: 'Privado.zip', plainSha256: ps, priority: 5 },
    { id: 'opti', name: 'Opti', category: 'required', version: '1', file: 'Opti.zip', url: base + '/res/Opti.zip', sha256: sha(optiBuf), size: optiBuf.length, requiresOptifine: true, priority: 6 });
  routes['/manifest.json'] = json(m);
  fs.rmSync(argsFile + '.ls', { force: true });
  await game.play(emit);
  await new Promise((r) => setTimeout(r, 250));
  const listing = fs.readFileSync(argsFile + '.ls', 'utf8').split('\n');
  assert.ok(listing.includes('Privado.zip'), 'mientras corre Minecraft, el pack privado está descifrado: ' + listing);
  assert.ok(!listing.includes('Opti.zip'), 'el pack que requiere OptiFine no se instala en vanilla');
  await new Promise((r) => { const iv = setInterval(() => { if (!game.isRunning()) { clearInterval(iv); r(); } }, 50); });
  assert.ok(!fs.existsSync(path.join(paths.packs(), 'Privado.zip')), 'al cerrar Minecraft se borra');
  assert.ok(fs.existsSync(path.join(paths.cache(), 'private', 'Privado.aep')), 'el cifrado se conserva para la próxima vez');
  assert.ok(fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8').includes('file/Privado.zip'));
  assert.ok(!fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8').includes('Opti.zip'));
});
