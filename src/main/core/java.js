'use strict';
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { getJson, download } = require('./http');
const { safeJoin } = require('./util');
const { ensureFiles } = require('./files');
const log = require('./logger');

// Índice oficial de runtimes de Java que publica Mojang.
const RUNTIME_INDEX = 'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json';
const platform = () => (process.arch === 'ia32' ? 'windows-x86' : process.arch === 'arm64' ? 'windows-arm64' : 'windows-x64');

async function ensureManaged({ component, runtimeDir, cacheDir, progress, signal, full }) {
  progress.setPhase('java', 'Preparando Java…');
  const dir = path.join(runtimeDir, component);
  const exe = path.join(dir, 'bin', 'java.exe');
  let entry;
  try {
    const all = await getJson(RUNTIME_INDEX, { signal });
    entry = all[platform()] && all[platform()][component] && all[platform()][component][0];
    if (!entry) throw new Error(`Mojang no ofrece el runtime ${component} para ${platform()}`);
  } catch (e) {
    if (e.code !== 'ABORTED' && fs.existsSync(exe)) { log.warn('Sin conexión; se usa el Java ya instalado'); return exe; }
    throw e;
  }
  const mf = path.join(cacheDir, `runtime-${component}.json`);
  await download(entry.manifest.url, mf, { sha1: entry.manifest.sha1, signal });
  const man = JSON.parse(fs.readFileSync(mf, 'utf8'));
  const files = [];
  for (const [rel, f] of Object.entries(man.files)) {
    if (f.type === 'directory') fs.mkdirSync(safeJoin(dir, rel), { recursive: true });
    else if (f.type === 'file') files.push({ url: f.downloads.raw.url, dest: safeJoin(dir, rel), sha1: f.downloads.raw.sha1, size: f.downloads.raw.size });
  }
  progress.setPhase('java-download', 'Descargando Java…');
  await ensureFiles(files, { progress, signal, full, concurrency: 8 });
  if (!fs.existsSync(exe)) throw new Error('La instalación de Java está incompleta (falta java.exe). Usa Ajustes → Reparar instalación.');
  return exe;
}

function javaMajor(exe) {
  return new Promise((resolve) => {
    let out = '';
    const p = spawn(exe, ['-version'], { windowsHide: true });
    p.stderr.on('data', (d) => { out += d; });
    p.on('error', () => resolve(0));
    p.on('close', () => { const m = /version "(\d+)(?:\.(\d+))?/.exec(out); resolve(m ? (m[1] === '1' ? +m[2] : +m[1]) : 0); });
  });
}

async function resolveJava(opts) {
  const { settings, javaVersion } = opts;
  const major = (javaVersion && javaVersion.majorVersion) || 21;
  if (settings.javaMode === 'custom' && settings.javaPath) {
    if (!fs.existsSync(settings.javaPath)) throw new Error('La ruta de Java seleccionada no existe: ' + settings.javaPath);
    const m = await javaMajor(settings.javaPath);
    if (m < major) throw new Error(`Esa instalación de Java es la versión ${m || 'desconocida'}; Minecraft 1.21.1 necesita Java ${major} o superior.`);
    return settings.javaPath;
  }
  return ensureManaged({ ...opts, component: (javaVersion && javaVersion.component) || 'java-runtime-delta' });
}

module.exports = { resolveJava, javaMajor };
