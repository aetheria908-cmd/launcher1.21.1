'use strict';
// Gestor central de Texture Packs: sincroniza con el manifiesto, verifica hashes, descifra los privados y activa en options.txt.
const fs = require('fs');
const path = require('path');
const { safeJoin, hashFile } = require('./util');
const { ensureFiles } = require('./files');
const { inspectPack } = require('./packcheck');
const pcrypto = require('./packcrypto');
const { getPackKey } = require('./secrets');
const { herr } = require('./http');
const opt = require('./options');
const log = require('./logger');

const stateFile = (paths) => path.join(paths.state(), 'packs.json');
function loadState(paths) { try { return JSON.parse(fs.readFileSync(stateFile(paths), 'utf8')); } catch { return { installed: {} }; } }
function saveState(paths, s) { fs.writeFileSync(stateFile(paths), JSON.stringify(s, null, 2)); }
const privDir = (paths) => { const d = path.join(paths.cache(), 'private'); fs.mkdirSync(d, { recursive: true }); return d; };

// ctx = { optifine: bool }: los packs que requieren OptiFine solo se instalan y activan si se juega con OptiFine.
const prio = (p, s) => (s.packPriority && s.packPriority[p.id] !== undefined ? s.packPriority[p.id] : p.priority || 0);
const applicable = (p, ctx) => !p.requiresOptifine || !!(ctx && ctx.optifine);
const wanted = (p, s, ctx) => applicable(p, ctx) && (p.category === 'required' || !!(s.optionalPacks[p.id] && s.optionalPacks[p.id].installed));
const enabled = (p, s, ctx) => applicable(p, ctx) && (p.category === 'required' || !!(s.optionalPacks[p.id] && s.optionalPacks[p.id].installed && s.optionalPacks[p.id].enabled !== false));
const gameFile = (p) => p.packFile || p.file;                 // nombre dentro de resourcepacks
const storePath = (paths, p) => (p.encrypted ? safeJoin(privDir(paths), p.file) : safeJoin(paths.packs(), p.file));
const fingerprint = (p) => (p.encrypted ? p.plainSha256 : p.sha256);

async function decryptPack(paths, p) {
  const dest = safeJoin(paths.packs(), gameFile(p));
  try { if (fs.existsSync(dest) && (await hashFile(dest, 'sha256')) === p.plainSha256) return dest; } catch { /* se vuelve a descifrar */ }
  const key = getPackKey();
  if (!key) throw herr('NO_PACK_KEY', 'Este launcher no incluye la clave para descifrar los packs privados. Instala la última versión del launcher.');
  await pcrypto.decryptFile(storePath(paths, p), dest, key, pcrypto.aad(p.id, p.plainSha256));
  if ((await hashFile(dest, 'sha256')) !== p.plainSha256) { fs.rmSync(dest, { force: true }); throw herr('HASH_MISMATCH', 'El pack descifrado no coincide con el publicado'); }
  return dest;
}

// Descarga/actualiza SOLO lo necesario, descifra los privados, borra lo retirado y valida cada pack.
async function sync({ manifest, settings, paths, progress, signal, ctx = {} }) {
  const st = loadState(paths);
  const list = manifest.resourcepacks.filter((p) => wanted(p, settings, ctx));
  progress.setPhase('packs', 'Sincronizando Texture Packs…');
  const changed = list.filter((p) => !st.installed[p.id] || st.installed[p.id].fingerprint !== fingerprint(p)).map((p) => p.id);
  await ensureFiles(list.map((p) => ({ url: p.url, dest: storePath(paths, p), sha256: p.sha256, size: p.size })), { progress, signal, full: true, concurrency: 3, strict: true });
  const issues = [];
  for (const p of list) {
    const file = gameFile(p), old = st.installed[p.id];
    const rec = { version: p.version, sha256: p.sha256, fingerprint: fingerprint(p), file, store: p.encrypted ? p.file : null, encrypted: !!p.encrypted, category: p.category,
      installedAt: (old && old.fingerprint === fingerprint(p) && old.installedAt) || new Date().toISOString(), valid: false };
    try {
      const dest = p.encrypted ? await decryptPack(paths, p) : safeJoin(paths.packs(), file);
      const r = inspectPack(dest);
      if (!r.ok) { issues.push({ id: p.id, errors: r.errors }); log.warn(`Pack ${p.id} inválido: ${r.errors.join(' | ')}`); } else { rec.valid = true; if (r.warnings.length) log.warn(`Pack ${p.id}: ${r.warnings.join(' | ')}`); }
    } catch (e) {
      if (e.code === 'NO_PACK_KEY') throw e;
      issues.push({ id: p.id, errors: [e.message] }); log.warn(`Pack ${p.id}: ${e.message}`);
    }
    st.installed[p.id] = rec;
  }
  for (const [id, rec] of Object.entries(st.installed)) { // retirar lo que ya no se necesita
    const p = manifest.resourcepacks.find((x) => x.id === id);
    if (!p || !wanted(p, settings, ctx)) {
      try { fs.rmSync(safeJoin(paths.packs(), rec.file), { force: true }); if (rec.store) fs.rmSync(safeJoin(privDir(paths), rec.store), { force: true }); } catch { /* ignorar */ }
      delete st.installed[id];
    }
  }
  saveState(paths, st);
  return { changed, issues };
}

// Los packs privados solo existen descifrados mientras se juega: se borran al cerrar Minecraft.
function cleanupPrivate(manifest, paths) {
  const names = new Set(manifest.resourcepacks.filter((p) => p.encrypted).map(gameFile));
  for (const rec of Object.values(loadState(paths).installed)) if (rec.encrypted) names.add(rec.file);
  for (const n of names) { try { fs.rmSync(safeJoin(paths.packs(), n), { force: true }); } catch { /* en uso */ } }
}

function activeFiles(manifest, settings, st, ctx) {
  return manifest.resourcepacks
    .filter((p) => enabled(p, settings, ctx) && st.installed[p.id] && st.installed[p.id].valid !== false)
    .sort((a, b) => prio(a, settings) - prio(b, settings) || a.id.localeCompare(b.id))
    .map(gameFile);
}

// Activa los packs sin destruir las preferencias del jugador (se conserva el resto de options.txt).
function applyToOptions({ manifest, settings, paths, ctx = {} }) {
  const st = loadState(paths);
  const file = path.join(paths.game(), 'options.txt');
  let text = '';
  try { text = fs.readFileSync(file, 'utf8'); fs.copyFileSync(file, file + '.launcher-backup'); } catch { /* aún no existe */ }
  const lines = opt.parse(text);
  const all = [...new Set([...manifest.resourcepacks.map(gameFile), ...Object.values(st.installed).map((r) => r.file)])];
  opt.applyPacks(lines, activeFiles(manifest, settings, st, ctx), all);
  if (settings.graphics && settings.graphics.apply) opt.applyGraphics(lines, settings.graphics, settings.lang);
  else if (!text) opt.set(lines, 'lang', settings.lang);
  fs.writeFileSync(file, opt.serialize(lines));
}

// Estado para la interfaz. deep=true recalcula SHA-256 (detección de archivos corruptos).
async function list({ manifest, settings, paths, deep = false, ctx = {} }) {
  const st = loadState(paths);
  const out = [];
  for (const p of manifest.resourcepacks) {
    const rec = st.installed[p.id], blocked = !applicable(p, ctx);
    let corrupt = false, present = false;
    if (!blocked) {
      try {
        const f = storePath(paths, p), stt = fs.statSync(f); present = true;
        corrupt = stt.size !== p.size || (deep && (await hashFile(f, 'sha256')) !== p.sha256);
      } catch { /* no instalado */ }
    }
    out.push({ id: p.id, name: p.name, description: p.description, category: p.category, version: p.version, size: p.size, updatedAt: p.updatedAt,
      priority: prio(p, settings), installed: present, outdated: !!(rec && rec.fingerprint !== fingerprint(p)), corrupt: present && corrupt, blocked,
      encrypted: !!p.encrypted, requiresOptifine: !!p.requiresOptifine, enabled: enabled(p, settings, ctx) && present, history: p.history || [], packFormat: p.packFormat });
  }
  return out;
}

// Qué packs hay activados en options.txt y si su archivo existe (los privados solo existen mientras se juega).
function activeInOptions(paths, manifest) {
  let text = '';
  try { text = fs.readFileSync(path.join(paths.game(), 'options.txt'), 'utf8'); } catch { return { exists: false, entries: [] }; }
  const priv = new Set(((manifest && manifest.resourcepacks) || []).filter((p) => p.encrypted).map(gameFile));
  const entries = opt.packList(opt.get(opt.parse(text), 'resourcePacks')).map((e) => {
    const file = e.startsWith('file/') ? e.slice(5) : null;
    let present = true;
    if (file) { try { present = fs.existsSync(safeJoin(paths.packs(), file)); } catch { present = false; } }
    return { entry: e, builtin: !file, present, private: !!(file && priv.has(file)) };
  });
  return { exists: true, entries };
}

module.exports = { sync, applyToOptions, list, activeFiles, loadState, activeInOptions, cleanupPrivate };
