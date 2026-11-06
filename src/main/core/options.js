'use strict';
// Edición no destructiva de options.txt de Minecraft.
function parse(text) {
  const lines = String(text || '').split(/\r?\n/);
  if (lines.length && lines[lines.length - 1] === '') lines.pop();
  return lines;
}
function get(lines, key) {
  const p = key + ':';
  const l = lines.find((x) => x.startsWith(p));
  return l === undefined ? undefined : l.slice(p.length);
}
function set(lines, key, val) {
  const line = key + ':' + val;
  const i = lines.findIndex((x) => x.startsWith(key + ':'));
  if (i >= 0) lines[i] = line; else lines.push(line);
}
function packList(v) {
  try { const a = JSON.parse(v); return Array.isArray(a) ? a.filter((x) => typeof x === 'string') : []; } catch { return []; }
}

// managed: archivos que deben quedar activos (orden: menor → mayor prioridad)
// managedAll: todos los archivos que gestiona el launcher (se retiran antes de re-añadir)
function applyPacks(lines, managed, managedAll = managed) {
  const strip = new Set(managedAll.map((f) => 'file/' + f));
  let cur = packList(get(lines, 'resourcePacks'));
  if (!cur.includes('vanilla')) cur.unshift('vanilla');
  cur = cur.filter((x) => !strip.has(x));
  cur.push(...managed.map((f) => 'file/' + f));
  set(lines, 'resourcePacks', JSON.stringify(cur));
  // Quita de "incompatibles" nuestros packs para que no se desactiven solos.
  const inc = packList(get(lines, 'incompatibleResourcePacks')).filter((x) => !strip.has(x));
  if (get(lines, 'incompatibleResourcePacks') !== undefined) set(lines, 'incompatibleResourcePacks', JSON.stringify(inc));
  return lines;
}

const QUALITY = { fast: 0, fancy: 1, fabulous: 2 };
const PARTICLES = { all: 0, decreased: 1, minimal: 2 };

function applyGraphics(lines, g, lang) {
  if (lang) set(lines, 'lang', lang);
  set(lines, 'renderDistance', Math.min(Math.max(+g.renderDistance || 12, 2), 32));
  set(lines, 'simulationDistance', Math.min(Math.max(+g.simulationDistance || 10, 5), 32));
  set(lines, 'enableVsync', g.vsync ? 'true' : 'false');
  set(lines, 'maxFps', g.maxFps >= 260 ? 260 : Math.min(Math.max(+g.maxFps || 120, 10), 260));
  set(lines, 'graphicsMode', QUALITY[g.quality] ?? 1);
  set(lines, 'particles', PARTICLES[g.particles] ?? 0);
  set(lines, 'renderClouds', JSON.stringify(['true', 'fast', 'false'].includes(g.clouds) ? g.clouds : 'true'));
  set(lines, 'mipmapLevels', Math.min(Math.max(+g.mipmaps || 0, 0), 4));
  const ao = get(lines, 'ambientOcclusion');
  if (ao !== undefined) set(lines, 'ambientOcclusion', /^\d+$/.test(ao) ? (g.smoothLighting ? 2 : 0) : (g.smoothLighting ? 'true' : 'false'));
  return lines;
}
function serialize(lines) { return lines.join('\n') + '\n'; }

module.exports = { parse, get, set, applyPacks, applyGraphics, serialize, packList };
