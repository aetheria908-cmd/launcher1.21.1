'use strict';
// Gestor central de Texture Packs: sincroniza con el manifiesto, verifica hashes y activa en options.txt.
const fs = require('fs');
const path = require('path');
const { safeJoin, hashFile } = require('./util');
const { ensureFiles } = require('./files');
const { inspectPack } = require('./packcheck');
const opt = require('./options');
const log = require('./logger');

const stateFile = (paths) => path.join(paths.state(), 'packs.json');
function loadState(paths) { try { return JSON.parse(fs.readFileSync(stateFile(paths), 'utf8')); } catch { return { installed: {} }; } }
function saveState(paths, s) { fs.writeFileSync(stateFile(paths), JSON.stringify(s, null, 2)); }

const prio = (p, s) => (s.packPriority && s.packPriority[p.id] !== undefined ? s.packPriority[p.id] : p.priority || 0);
const wanted = (p, s) => p.category === 'required' || !!(s.optionalPacks[p.id] && s.optionalPacks[p.id].installed);
const enabled = (p, s) => p.category === 'required' || !!(s.optionalPacks[p.id] && s.optionalPacks[p.id].installed && s.optionalPacks[p.id].enabled !== false);
const asFile = (paths, p) => ({ url: p.url, dest: safeJoin(paths.packs(), p.file), sha256: p.sha256, size: p.size });

// Descarga/actualiza SOLO lo necesario, borra lo retirado y valida cada pack.
async function sync({ manifest, settings, paths, progress, signal }) {
  const st = loadState(paths);
  const list = manifest.resourcepacks.filter((p) => wanted(p, settings));
  progress.setPhase('packs', 'Sincronizando Texture Packs…');
  const changed = [];
  for (const p of list) {
    const rec = st.installed[p.id];
    if (!rec || rec.sha256 !== p.sha256) changed.push(p.id);
  }
  await ensureFiles(list.map((p) => asFile(paths, p)), { progress, signal, full: true, concurrency: 3, strict: true });
  const issues = [];
  for (const p of list) {
    const dest = safeJoin(paths.packs(), p.file);
    const r = inspectPack(dest);
    if (!r.ok) { issues.push({ id: p.id, errors: r.errors }); log.warn(`Pack ${p.id} inválido: ${r.errors.join(' | ')}`); }
    else if (r.warnings.length) log.warn(`Pack ${p.id}: ${r.warnings.join(' | ')}`);
    st.installed[p.id] = { version: p.version, sha256: p.sha256, file: p.file, category: p.category, installedAt: (st.installed[p.id] && st.installed[p.id].sha256 === p.sha256 && st.installed[p.id].installedAt) || new Date().toISOString(), valid: r.ok };
  }
  // Retirar packs que ya no están en el manifiesto o que el jugador desinstaló
  for (const [id, rec] of Object.entries(st.installed)) {
    const p = manifest.resourcepacks.find((x) => x.id === id);
    if (!p || !wanted(p, settings)) {
      try { fs.rmSync(safeJoin(paths.packs(), rec.file), { force: true }); } catch { /* ignorar */ }
      delete st.installed[id];
    }
  }
  saveState(paths, st);
  return { changed, issues };
}

function activeFiles(manifest, settings, st) {
  return manifest.resourcepacks
    .filter((p) => enabled(p, settings) && st.installed[p.id] && st.installed[p.id].valid !== false)
    .sort((a, b) => prio(a, settings) - prio(b, settings) || a.id.localeCompare(b.id))
    .map((p) => p.file);
}

// Activa los packs sin destruir las preferencias del jugador (se conserva el resto de options.txt).
function applyToOptions({ manifest, settings, paths }) {
  const st = loadState(paths);
  const file = path.join(paths.game(), 'options.txt');
  let text = '';
  try { text = fs.readFileSync(file, 'utf8'); fs.copyFileSync(file, file + '.launcher-backup'); } catch { /* aún no existe */ }
  const lines = opt.parse(text);
  const all = [...new Set([...manifest.resourcepacks.map((p) => p.file), ...Object.values(st.installed).map((r) => r.file)])];
  opt.applyPacks(lines, activeFiles(manifest, settings, st), all);
  if (settings.graphics && settings.graphics.apply) opt.applyGraphics(lines, settings.graphics, settings.lang);
  else if (!text) opt.set(lines, 'lang', settings.lang);
  fs.writeFileSync(file, opt.serialize(lines));
}

// Estado para la interfaz. deep=true recalcula SHA-256 (detección de archivos corruptos).
async function list({ manifest, settings, paths, deep = false }) {
  const st = loadState(paths);
  const out = [];
  for (const p of manifest.resourcepacks) {
    const rec = st.installed[p.id];
    let corrupt = false, present = false;
    try {
      const f = safeJoin(paths.packs(), p.file);
      const stt = fs.statSync(f); present = true;
      corrupt = stt.size !== p.size || (deep && (await hashFile(f, 'sha256')) !== p.sha256);
    } catch { /* no instalado */ }
    out.push({ id: p.id, name: p.name, description: p.description, category: p.category, version: p.version, size: p.size, updatedAt: p.updatedAt,
      priority: prio(p, settings), installed: present, outdated: !!(rec && rec.sha256 !== p.sha256), corrupt: present && corrupt,
      enabled: enabled(p, settings) && present, history: p.history || [], packFormat: p.packFormat });
  }
  return out;
}

module.exports = { sync, applyToOptions, list, activeFiles, loadState };
