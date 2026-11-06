'use strict';
// OptiFine: SOLO fuentes oficiales. No se inventa compatibilidad: se consulta optifine.net.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { getText } = require('./http');
const { listEntries } = require('./zip');

const PAGE = 'https://optifine.net/downloads';

async function check(mcVersion) {
  try {
    const html = await getText(PAGE);
    const re = new RegExp(`f=((?:preview_)?OptiFine_${mcVersion.replace(/\./g, '\\.')}_[A-Za-z0-9_.\\-]+\\.jar)`, 'g');
    const found = [...new Set([...html.matchAll(re)].map((m) => m[1]))];
    if (!found.length) return { available: false, page: PAGE, reason: `OptiFine no publica (todavía) una versión para Minecraft ${mcVersion} en su web oficial.` };
    const stable = found.filter((f) => !f.startsWith('preview_'));
    return { available: true, stable: stable.length > 0, versions: found, page: PAGE,
      reason: stable.length ? '' : 'Solo hay versiones preliminares (preview), que pueden ser inestables.' };
  } catch (e) {
    return { available: null, page: PAGE, reason: 'No se pudo consultar optifine.net: ' + e.message };
  }
}

function validateJar(file, mcVersion) {
  const base = path.basename(file);
  if (!new RegExp(`^(preview_)?OptiFine_${mcVersion.replace(/\./g, '\\.')}_`).test(base)) throw new Error(`Ese archivo no es OptiFine para Minecraft ${mcVersion}.`);
  const names = listEntries(file).map((e) => e.name);
  if (!names.includes('optifine/Installer.class')) throw new Error('El archivo no contiene el instalador oficial de OptiFine.');
}

function installed(gameDir, mcVersion) {
  try {
    return fs.readdirSync(path.join(gameDir, 'versions')).filter((d) => d.startsWith(mcVersion + '-OptiFine') && fs.existsSync(path.join(gameDir, 'versions', d, d + '.json')));
  } catch { return []; }
}

// Ejecuta el instalador OFICIAL de OptiFine apuntando a nuestra carpeta (APPDATA=<root> → <root>/.minecraft).
function runInstaller({ javaExe, jar, root }) {
  return new Promise((resolve, reject) => {
    const p = spawn(javaExe, ['-cp', jar, 'optifine.Installer'], { env: { ...process.env, APPDATA: root }, windowsHide: false });
    p.on('error', reject);
    p.on('close', (c) => resolve(c));
  });
}
module.exports = { check, validateJar, installed, runInstaller, PAGE };
