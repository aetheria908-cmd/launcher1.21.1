'use strict';
const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  installDir: '',
  ram: { auto: true, min: 1024, max: 4096 },
  jvmArgs: '',
  javaMode: 'managed', // managed = runtime oficial de Mojang | custom = ruta propia
  javaPath: '',
  profile: 'vanilla', // vanilla | optifine
  optifineVersion: '',
  width: 0, height: 0, fullscreen: false,
  joinServer: true, closeOnPlay: false,
  lang: 'es_es',
  graphics: { apply: false, renderDistance: 12, simulationDistance: 10, vsync: true, maxFps: 120, quality: 'fancy',
    particles: 'all', smoothLighting: true, clouds: 'true', mipmaps: 4 },
  audio: { music: true, volume: 0.4, muted: false },
  media: { video: true, effects: true },
  selectedAccount: '',
  optionalPacks: {}, // id -> { installed, enabled }
  packPriority: {},  // id -> número (mayor = se aplica encima)
};

function merge(base, over) {
  const out = Array.isArray(base) ? [...base] : { ...base };
  for (const k of Object.keys(over || {})) {
    const o = over[k];
    if (o && typeof o === 'object' && !Array.isArray(o) && base[k] && typeof base[k] === 'object' && !Array.isArray(base[k])) out[k] = merge(base[k], o);
    else out[k] = o;
  }
  return out;
}

let file = '';
let data = merge(DEFAULTS, {});

function load(dir) {
  file = path.join(dir, 'settings.json');
  for (const f of [file, file + '.bak']) {
    try { data = merge(DEFAULTS, JSON.parse(fs.readFileSync(f, 'utf8'))); return data; } catch { /* probar copia */ }
  }
  data = merge(DEFAULTS, {});
  return data;
}
function save() {
  try { if (fs.existsSync(file)) fs.copyFileSync(file, file + '.bak'); } catch { /* ignorar */ }
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, file);
}
function patch(p) {
  const clean = {};
  for (const k of Object.keys(p || {})) if (k in DEFAULTS) clean[k] = p[k];
  if (clean.installDir !== undefined && clean.installDir && !path.isAbsolute(clean.installDir)) throw new Error('La carpeta de instalación debe ser una ruta absoluta');
  data = merge(data, clean);
  save();
  return data;
}
function reset() { data = merge(DEFAULTS, { installDir: data.installDir, selectedAccount: data.selectedAccount }); save(); return data; }

module.exports = { load, get: () => data, patch, reset, DEFAULTS };
