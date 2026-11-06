'use strict';
// Instalación de Minecraft Java desde los manifiestos OFICIALES de Mojang. No se redistribuye nada propietario.
const fs = require('fs');
const path = require('path');
const { getJson, download } = require('./http');
const { hashFile, safeJoin, evalRules } = require('./util');
const { ensureFiles } = require('./files');
const log = require('./logger');

const VERSION_MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const ASSET_HOST = 'https://resources.download.minecraft.net';

async function getVersionJson(id, gameDir, signal) {
  const vfile = safeJoin(path.join(gameDir, 'versions'), id, id + '.json');
  try {
    const vm = await getJson(VERSION_MANIFEST, { signal });
    const e = vm.versions.find((v) => v.id === id);
    if (!e) throw new Error(`La versión ${id} no existe en el manifiesto oficial de Mojang`);
    let need = true;
    if (fs.existsSync(vfile)) { try { need = (await hashFile(vfile, 'sha1')) !== e.sha1; } catch { need = true; } }
    if (need) await download(e.url, vfile, { sha1: e.sha1, signal });
  } catch (err) {
    if (err.code === 'ABORTED' || !fs.existsSync(vfile)) throw err;
    log.warn('Sin conexión con Mojang; se usa la versión local: ' + err.message);
  }
  return JSON.parse(fs.readFileSync(vfile, 'utf8'));
}

function libraryFiles(v, gameDir) {
  const out = [];
  const base = path.join(gameDir, 'libraries');
  for (const lib of v.libraries || []) {
    if (!evalRules(lib.rules)) continue;
    const a = lib.downloads && lib.downloads.artifact;
    if (a && a.url) out.push({ url: a.url, dest: safeJoin(base, a.path), sha1: a.sha1, size: a.size });
  }
  return out;
}

// full=true → comprueba el SHA-1 de todos los archivos (reparación).
async function installGame({ versionId, gameDir, progress, signal, full = false }) {
  progress.setPhase('minecraft', `Comprobando Minecraft ${versionId}…`);
  const v = await getVersionJson(versionId, gameDir, signal);
  const files = [];
  files.push({ url: v.downloads.client.url, dest: path.join(gameDir, 'versions', versionId, versionId + '.jar'), sha1: v.downloads.client.sha1, size: v.downloads.client.size });
  files.push(...libraryFiles(v, gameDir));
  const ai = v.assetIndex;
  const idxFile = safeJoin(path.join(gameDir, 'assets', 'indexes'), ai.id + '.json');
  await ensureFiles([{ url: ai.url, dest: idxFile, sha1: ai.sha1, size: ai.size }], { progress, signal, full });
  const idx = JSON.parse(fs.readFileSync(idxFile, 'utf8'));
  for (const o of Object.values(idx.objects)) {
    const h2 = o.hash.slice(0, 2);
    files.push({ url: `${ASSET_HOST}/${h2}/${o.hash}`, dest: path.join(gameDir, 'assets', 'objects', h2, o.hash), sha1: o.hash, size: o.size });
  }
  const lf = v.logging && v.logging.client && v.logging.client.file;
  if (lf) files.push({ url: lf.url, dest: safeJoin(path.join(gameDir, 'assets', 'log_configs'), lf.id), sha1: lf.sha1, size: lf.size });
  progress.setPhase('minecraft-download', 'Descargando archivos de Minecraft…');
  const n = await ensureFiles(files, { progress, signal, full, concurrency: 12 });
  fs.mkdirSync(path.join(gameDir, 'versions', versionId, 'natives'), { recursive: true });
  // launcher_profiles.json mínimo (lo exige el instalador de OptiFine)
  const lp = path.join(gameDir, 'launcher_profiles.json');
  if (!fs.existsSync(lp)) fs.writeFileSync(lp, JSON.stringify({ profiles: {}, settings: {}, version: 3 }));
  log.info(`Minecraft ${versionId}: ${n} archivos descargados/reparados`);
  return { version: v, downloaded: n };
}

module.exports = { installGame, getVersionJson, libraryFiles };
