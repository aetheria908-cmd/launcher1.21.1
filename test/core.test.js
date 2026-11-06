'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs'), os = require('os'), path = require('path'), net = require('net');
const u = require('../src/main/core/util');
const opt = require('../src/main/core/options');
const { inspectPack } = require('../src/main/core/packcheck');
const { validateManifest } = require('../src/main/core/manifest');
const sp = require('../src/main/core/serverping');
const { makeZip } = require('./helpers');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'aeth-'));

test('UUID offline = UUID v3 y estable', () => {
  const a = u.offlineUuid('Steve');
  assert.match(a, /^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.strictEqual(a, u.offlineUuid('Steve'));
  assert.notStrictEqual(a, u.offlineUuid('Alex'));
});
test('validación de nombre offline', () => {
  assert.ok(u.validateOfflineName('Steve_01'));
  for (const n of ['ab', 'a'.repeat(17), 'con espacio', 'ñandú', '', null]) assert.ok(!u.validateOfflineName(n), String(n));
});
test('safeJoin bloquea traversal', () => {
  const base = path.join(tmp, 'b');
  assert.ok(u.safeJoin(base, 'a', 'b.txt').startsWith(path.resolve(base)));
  assert.throws(() => u.safeJoin(base, '..', 'x'));
  assert.throws(() => u.safeJoin(base, '/etc/passwd'.replace('/etc', path.resolve('/') + 'etc')));
});
test('reglas de Mojang', () => {
  assert.ok(u.evalRules(undefined));
  assert.ok(u.evalRules([{ action: 'allow', os: { name: 'windows' } }], {}, { os: 'windows' }));
  assert.ok(!u.evalRules([{ action: 'allow', os: { name: 'osx' } }], {}, { os: 'windows' }));
  assert.ok(!u.evalRules([{ action: 'allow' }, { action: 'disallow', os: { name: 'windows' } }], {}, { os: 'windows' }));
  assert.ok(!u.evalRules([{ action: 'allow', os: { name: 'windows', arch: 'x86' } }], {}, { os: 'windows', arch: 'x64' }));
  assert.ok(u.evalRules([{ action: 'allow', features: { has_custom_resolution: true } }], { has_custom_resolution: true }));
  assert.ok(!u.evalRules([{ action: 'allow', features: { is_demo_user: true } }], {}));
});
test('ruta maven', () => {
  assert.strictEqual(u.mavenPath('org.lwjgl:lwjgl:3.3.3:natives-windows'), 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar');
});
test('argumentos JVM: bloquea agentes y comandos', () => {
  const r = u.parseJvmArgs('-Xss1M -javaagent:evil.jar -XX:OnError=calc -Dfoo=bar "-Xmx1G" ; -cp x -XX:+UseZGC');
  assert.deepStrictEqual(r.args, ['-Xss1M', '-Dfoo=bar', '-XX:+UseZGC']);
  assert.ok(r.rejected.length >= 5);
});
test('límites de RAM', () => {
  const r = u.clampRam({ min: 99999, max: 99999 }, 8192);
  assert.ok(r.max <= 6144 && r.min <= r.max);
  assert.ok(u.autoRam(4096).max <= 3072);
  assert.ok(u.autoRam(32768).max <= 6144);
});
test('options.txt: conserva packs del jugador y añade los gestionados', () => {
  const l = opt.parse('lang:en_us\nresourcePacks:["vanilla","file/mio.zip","file/viejo.zip"]\nfov:0.5\n');
  opt.applyPacks(l, ['nuevo.zip'], ['viejo.zip', 'nuevo.zip']);
  assert.deepStrictEqual(opt.packList(opt.get(l, 'resourcePacks')), ['vanilla', 'file/mio.zip', 'file/nuevo.zip']);
  assert.strictEqual(opt.get(l, 'fov'), '0.5');
  const vacio = opt.applyPacks(opt.parse(''), ['a.zip']);
  assert.deepStrictEqual(opt.packList(opt.get(vacio, 'resourcePacks')), ['vanilla', 'file/a.zip']);
});
test('options.txt: gráficos y ambientOcclusion según formato existente', () => {
  const g = { renderDistance: 16, simulationDistance: 8, vsync: false, maxFps: 300, quality: 'fabulous', particles: 'minimal', smoothLighting: false, clouds: 'fast', mipmaps: 2 };
  let l = opt.applyGraphics(opt.parse('ambientOcclusion:true'), g, 'es_es');
  assert.strictEqual(opt.get(l, 'ambientOcclusion'), 'false');
  assert.strictEqual(opt.get(l, 'maxFps'), '260');
  assert.strictEqual(opt.get(l, 'graphicsMode'), '2');
  assert.strictEqual(opt.get(l, 'renderClouds'), '"fast"');
  l = opt.applyGraphics(opt.parse('ambientOcclusion:2'), { ...g, smoothLighting: true });
  assert.strictEqual(opt.get(l, 'ambientOcclusion'), '2');
  assert.strictEqual(opt.get(opt.applyGraphics(opt.parse(''), g), 'ambientOcclusion'), undefined);
});
test('packcheck: pack válido', () => {
  const f = path.join(tmp, 'ok.zip');
  makeZip(f, { 'pack.mcmeta': '\uFEFF{"pack":{"pack_format":34,"description":"Hola"}}', 'pack.png': 'x', 'assets/minecraft/textures/block/stone.png': 'png' });
  const r = inspectPack(f);
  assert.ok(r.ok, JSON.stringify(r));
  assert.strictEqual(r.info.packFormat, 34);
  assert.deepStrictEqual(r.info.namespaces, ['minecraft']);
  assert.strictEqual(r.warnings.length, 0);
});
test('packcheck: formato viejo = aviso, supported_formats lo acepta', () => {
  const f = path.join(tmp, 'old.zip');
  makeZip(f, { 'pack.mcmeta': '{"pack":{"pack_format":15,"description":"x"}}', 'assets/minecraft/a.txt': 'a' }, false);
  const r = inspectPack(f);
  assert.ok(r.ok); assert.strictEqual(r.warnings.length, 1); assert.strictEqual(r.info.minecraft, '1.20–1.20.1');
  makeZip(f, { 'pack.mcmeta': '{"pack":{"pack_format":15,"supported_formats":[15,40],"description":"x"}}', 'assets/minecraft/a.txt': 'a' });
  assert.strictEqual(inspectPack(f).warnings.length, 0);
});
test('packcheck: errores estructurales', () => {
  const f = path.join(tmp, 'bad.zip');
  makeZip(f, { 'MiPack/pack.mcmeta': '{"pack":{"pack_format":34}}', 'MiPack/assets/minecraft/a.txt': 'a' });
  let r = inspectPack(f); assert.ok(!r.ok); assert.match(r.errors.join(' '), /dentro de la carpeta "MiPack/);
  makeZip(f, { 'pack.mcmeta': '{roto', 'assets/minecraft/a.txt': 'a' });
  assert.match(inspectPack(f).errors.join(' '), /JSON/);
  makeZip(f, { 'pack.mcmeta': '{"pack":{"pack_format":34}}' });
  assert.match(inspectPack(f).errors.join(' '), /assets/);
  fs.writeFileSync(f, 'esto no es un zip, ni de lejos, de verdad que no');
  assert.ok(!inspectPack(f).ok);
});
test('packcheck: ZIP Slip detectado', () => {
  const f = path.join(tmp, 'slip.zip');
  makeZip(f, { 'pack.mcmeta': '{"pack":{"pack_format":34,"description":"x"}}', 'assets/minecraft/a.txt': 'a', '../../evil.dll': 'x', 'C:/win.dll': 'x', '/abs': 'x' });
  const r = inspectPack(f);
  assert.ok(!r.ok); assert.strictEqual(r.errors.filter((e) => /peligrosa/.test(e)).length, 3);
});
test('manifiesto: acepta válido y rechaza peligroso', () => {
  const good = { schemaVersion: 1, server: { address: 'play.ejemplo.com', port: 25565, minecraftVersion: '1.21.1' }, urls: { discord: 'https://discord.gg/x' },
    resourcepacks: [{ id: 'base', category: 'required', version: '1.0', file: 'Base.zip', url: 'https://github.com/x/y/Base.zip', sha256: 'a'.repeat(64), size: 10 }] };
  const r = validateManifest(good, '1.0.0');
  assert.ok(r.ok, r.errors.join()); assert.strictEqual(r.manifest.resourcepacks[0].category, 'required');
  const bad = JSON.parse(JSON.stringify(good));
  bad.resourcepacks[0].file = '../evil.zip'; bad.resourcepacks[0].url = 'http://x/y.zip'; bad.resourcepacks[0].sha256 = 'zz'; bad.server.address = 'a b; rm -rf';
  const rb = validateManifest(bad);
  assert.ok(!rb.ok); assert.ok(rb.errors.length >= 4);
  assert.ok(validateManifest({ schemaVersion: 99 }).tooNew);
  assert.ok(!validateManifest(null).ok);
  const ex = validateManifest({ schemaVersion: 1, evil: 'require("child_process")', launcher: { minVersion: '9.0.0' } }, '1.0.0');
  assert.ok(ex.ok); assert.strictEqual(ex.manifest.evil, undefined); assert.ok(ex.manifest.needsLauncherUpdate);
});
test('ping a un servidor Minecraft falso', async () => {
  const status = { version: { name: '1.21.1', protocol: 767 }, players: { online: 3, max: 50 }, description: { text: '§6Hola', extra: [{ text: ' mundo' }] } };
  const server = net.createServer((s) => {
    let buf = Buffer.alloc(0), n = 0;
    s.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      for (;;) {
        const pk = sp.readPacket(buf); if (!pk) return; buf = buf.subarray(pk.consumed);
        if (pk.id === 0 && pk.payload.length === 0) s.write(sp.packet(0, sp.mcString(JSON.stringify(status))));
        else if (pk.id === 1) s.write(sp.packet(1, pk.payload));
        n++;
      }
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const res = await sp.pingServer('127.0.0.1', server.address().port, 3000);
  server.close();
  assert.ok(res.online); assert.strictEqual(res.players.online, 3); assert.strictEqual(res.motd, 'Hola mundo'); assert.ok(res.latency >= 0);
  const off = await sp.pingServer('127.0.0.1', 1, 1500);
  assert.strictEqual(off.online, false);
});
