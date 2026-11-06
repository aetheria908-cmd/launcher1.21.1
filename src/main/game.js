'use strict';
// Orquestador: instalar → actualizar → autenticar → preparar packs → lanzar Minecraft.
const fs = require('fs');
const os = require('os');
const path = require('path');
const paths = require('./core/paths');
const settings = require('./core/settings');
const remote = require('./core/remote');
const Progress = require('./core/progress');
const minecraft = require('./core/minecraft');
const javaMod = require('./core/java');
const launch = require('./core/launch');
const packs = require('./core/packs');
const media = require('./core/media');
const optifine = require('./core/optifine');
const util = require('./core/util');
const log = require('./core/logger');
const accounts = require('./auth/accounts');
const { herr } = require('./core/http');

let current = null; // operación en curso
let child = null;   // proceso de Minecraft
let appVersion = '0.0.0';
const setVersion = (v) => { appVersion = v; };

async function withTask(kind, emit, fn) {
  if (current) throw herr('BUSY', 'Ya hay una operación en curso. Espera o cancélala.');
  if (child) throw herr('RUNNING', 'Minecraft ya está en ejecución.');
  const controller = new AbortController();
  current = { controller, kind };
  const progress = new Progress((p) => emit('task:progress', p));
  emit('task:state', { busy: true, kind });
  try { return await fn(controller.signal, progress); }
  finally {
    current = null;
    emit('task:state', { busy: false, kind });
    emit('task:progress', { phase: '', label: '', percent: null, done: 0, total: 0, speed: 0, eta: null });
  }
}
const cancel = () => { if (current) current.controller.abort(); };

function ramFor(s) {
  const total = Math.floor(os.totalmem() / 1048576);
  return s.ram.auto ? util.autoRam(total) : util.clampRam(s.ram, total);
}

async function refreshRemote(signal) {
  const man = await remote.refresh({ cacheDir: paths.cache(), appVersion, signal });
  return man;
}

// Instala/actualiza Minecraft, Java, packs y multimedia. Solo descarga lo necesario.
async function prepare(signal, progress, full = false) {
  const s = settings.get();
  const man = remote.getManifest();
  const ver = remote.server().minecraftVersion;
  const { version } = await minecraft.installGame({ versionId: ver, gameDir: paths.game(), progress, signal, full });
  const javaExe = await javaMod.resolveJava({ settings: s, javaVersion: version.javaVersion, runtimeDir: paths.runtime(), cacheDir: paths.cache(), progress, signal, full });
  const packRes = await packs.sync({ manifest: man, settings: s, paths, progress, signal });
  try { await media.sync({ manifest: man, settings: s, paths, progress, signal }); } catch (e) { if (e.code === 'ABORTED') throw e; log.warn('Multimedia: ' + e.message); }
  return { javaExe, packRes, version };
}

function gate(man) {
  if (man.needsLauncherUpdate) throw herr('LAUNCHER_OUTDATED', man.launcher.message || 'Hay una actualización obligatoria del launcher. Se está descargando; reinicia cuando termine.');
  if (man.maintenance.enabled) throw herr('MAINTENANCE', man.maintenance.message || 'El servidor está en mantenimiento.');
}

function play(emit) {
  return withTask('play', emit, async (signal, progress) => {
    emit('game:state', { state: 'preparing' });
    try {
      progress.setPhase('manifest', 'Comprobando actualizaciones…');
      const man = await refreshRemote(signal);
      gate(man);
      progress.setPhase('auth', 'Comprobando tu cuenta…');
      const auth = await accounts.resolveForLaunch(remote.authConfig().microsoft.clientId, signal);
      const { javaExe, packRes, version } = await prepare(signal, progress);
      progress.setPhase('launch', 'Preparando la instancia…');
      const s = settings.get();
      packs.applyToOptions({ manifest: man, settings: s, paths });
      let versionId = version.id;
      if (s.profile === 'optifine') {
        const list = optifine.installed(paths.game(), version.id);
        if (!list.length) throw herr('NO_OPTIFINE', 'OptiFine no está instalado. Instálalo en Ajustes → OptiFine o cambia a Minecraft normal.');
        versionId = list.includes(s.optifineVersion) ? s.optifineVersion : list[list.length - 1];
      }
      const srv = remote.server();
      const cmd = launch.buildCommand({ versionId, gameDir: paths.game(), javaExe, auth, settings: s, ramMb: ramFor(s), server: s.joinServer ? { host: srv.address, port: srv.port } : null, launcherVersion: appVersion });
      if (cmd.rejectedJvm.length) emit('toast', { kind: 'warn', text: 'Argumentos JVM ignorados por seguridad: ' + cmd.rejectedJvm.join(' ') });
      child = launch.run(cmd, {
        onExit: (code, tail) => {
          child = null;
          emit('game:state', { state: 'idle' });
          log.info('Minecraft terminó con código ' + code);
          if (code !== 0) emit('game:crash', { code, tail: tail.slice(-25) });
        },
      });
      emit('game:state', { state: 'running' });
      return { pid: child.pid, auth: auth.type };
    } catch (e) { emit('game:state', { state: 'idle' }); throw e; }
  });
}

// Instala y actualiza sin lanzar (primer arranque, botón "Comprobar actualizaciones").
function update(emit, full = false) {
  return withTask(full ? 'repair' : 'update', emit, async (signal, progress) => {
    progress.setPhase('manifest', 'Comprobando actualizaciones…');
    gate0(await refreshRemote(signal));
    const r = await prepare(signal, progress, full);
    return { changedPacks: r.packRes.changed, issues: r.packRes.issues };
  });
}
function gate0(man) { if (man.needsLauncherUpdate) log.warn('Actualización obligatoria del launcher pendiente'); }

function installState() {
  const ver = remote.server().minecraftVersion;
  const g = paths.game();
  return { version: ver, installed: fs.existsSync(path.join(g, 'versions', ver, ver + '.jar')) && fs.existsSync(path.join(g, 'versions', ver, ver + '.json')), running: !!child, busy: !!current };
}

function packInstall(emit, id, on) {
  const s = settings.get();
  const op = { ...(s.optionalPacks[id] || {}), installed: on, enabled: on ? true : false };
  settings.patch({ optionalPacks: { [id]: op } });
  return withTask('packs', emit, async (signal, progress) => { await packs.sync({ manifest: remote.getManifest(), settings: settings.get(), paths, progress, signal }); });
}
function packRepair(emit) {
  return withTask('packs', emit, async (signal, progress) => packs.sync({ manifest: remote.getManifest(), settings: settings.get(), paths, progress, signal }));
}
function packToggle(id, enabled) { const s = settings.get(); settings.patch({ optionalPacks: { [id]: { ...(s.optionalPacks[id] || { installed: true }), enabled: !!enabled } } }); }
function packPriority(id, delta) {
  const man = remote.getManifest(); const s = settings.get();
  const p = man.resourcepacks.find((x) => x.id === id); if (!p) throw new Error('Pack desconocido');
  const cur = s.packPriority[id] !== undefined ? s.packPriority[id] : p.priority || 0;
  settings.patch({ packPriority: { [id]: cur + (delta > 0 ? 1 : -1) } });
}

function cleanTemp() {
  let freed = 0;
  const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const f = path.join(d, e.name); if (e.isDirectory()) walk(f); else if (e.name.endsWith('.part') || e.name.endsWith('.tmp')) { freed += fs.statSync(f).size; fs.rmSync(f, { force: true }); } } };
  try { walk(paths.root()); } catch { /* ignorar */ }
  try { fs.rmSync(path.join(paths.userData(), 'logs', 'launcher.log.old'), { force: true }); } catch { /* ignorar */ }
  return freed;
}

async function optifineInfo() {
  const ver = remote.server().minecraftVersion;
  return { check: await optifine.check(ver), installed: optifine.installed(paths.game(), ver), mcVersion: ver };
}
function optifineInstall(emit, jar) {
  return withTask('optifine', emit, async (signal, progress) => {
    const ver = remote.server().minecraftVersion;
    optifine.validateJar(jar, ver);
    const { javaExe } = await prepare(signal, progress);
    progress.setPhase('optifine', 'Ejecutando el instalador oficial de OptiFine…');
    const code = await optifine.runInstaller({ javaExe, jar, root: paths.root() });
    const list = optifine.installed(paths.game(), ver);
    if (!list.length) throw new Error('El instalador de OptiFine terminó (código ' + code + ') pero no se encontró la instalación. Asegúrate de pulsar "Install" en su ventana.');
    settings.patch({ profile: 'optifine', optifineVersion: list[list.length - 1] });
    return list;
  });
}

module.exports = { play, update, cancel, installState, packInstall, packRepair, packToggle, packPriority, cleanTemp, optifineInfo, optifineInstall, setVersion, refreshRemote, ramFor, isRunning: () => !!child, isBusy: () => !!current, withTask };
