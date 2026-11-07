'use strict';
// Genera manifest/manifest.json a partir de manifest/manifest.base.json + los archivos de /resources.
// Calcula SHA-256 y tamaño, valida cada Texture Pack y conserva el historial de versiones.
// Uso: node tools/build-manifest.js [--check]
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { inspectPack } = require('../src/main/core/packcheck');
const { validateManifest } = require('../src/main/core/manifest');
const { isSafeFileName } = require('../src/main/core/util');

const ROOT = path.join(__dirname, '..');
const BASE = path.join(ROOT, 'manifest', 'manifest.base.json');
const OUT = path.join(ROOT, 'manifest', 'manifest.json');
const check = process.argv.includes('--check');
const errors = [];

const base = JSON.parse(fs.readFileSync(BASE, 'utf8'));
const repo = base.repo || { owner: 'aetheria908-cmd', name: 'launcher1.21.1', branch: 'main' };
let prev = { resourcepacks: [], media: {} };
try { prev = JSON.parse(fs.readFileSync(OUT, 'utf8')); } catch { /* primera vez */ }

const sha256 = (f) => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const slug = (s) => s.toLowerCase().replace(/\.[^.]+$/, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'recurso';
const rawUrl = (rel) => `https://raw.githubusercontent.com/${repo.owner}/${repo.name}/${repo.branch || 'main'}/${rel.split('/').map(encodeURIComponent).join('/')}`;
const meta = (f) => { try { return JSON.parse(fs.readFileSync(f + '.json', 'utf8')); } catch { return {}; } };
const list = (dir, exts) => { try { return fs.readdirSync(dir).filter((n) => exts.includes(path.extname(n).toLowerCase())).sort(); } catch { return []; } };
const bump = (v) => { const p = String(v).split('.'); p[p.length - 1] = String((parseInt(p[p.length - 1], 10) || 0) + 1); return p.join('.'); };

function entry(abs, rel, extra = {}) {
  const file = path.basename(abs), m = meta(abs), st = fs.statSync(abs);
  if (!isSafeFileName(file)) { errors.push(`Nombre de archivo no permitido: "${file}" (usa letras, números, espacios, . _ - ( ) +)`); return null; }
  let url = m.url;
  if (!url) {
    if (st.size > 95 * 1024 * 1024) { errors.push(`${file} pesa más de 95 MB: súbelo a una GitHub Release y pon su URL en "${file}.json" → {"url": "..."}`); return null; }
    url = rawUrl(rel);
  }
  return { id: m.id || slug(file), name: m.name || file.replace(/\.[^.]+$/, ''), description: m.description || '', file, url, sha256: sha256(abs), size: st.size, ...extra, _meta: m };
}

const packs = [];
const today = new Date().toISOString().slice(0, 10);
for (const cat of ['required', 'optional']) {
  const dir = path.join(ROOT, 'resources', 'resourcepacks', cat);
  const files = list(dir, ['.zip', '.aep']);
  // Fichas de packs privados alojados fuera del repositorio (GitHub Release): NombrePack.aep.json sin el .aep al lado
  for (const n of list(dir, ['.json'])) if (/\.aep\.json$/i.test(n) && !files.includes(n.replace(/\.json$/i, ''))) { if (meta(path.join(dir, n.replace(/\.json$/i, ''))).external) files.push(n.replace(/\.json$/i, '')); }
  for (const name of files.sort()) {
    const abs = path.join(dir, name), m = meta(abs), isAep = /\.aep$/i.test(name);
    let e;
    if (isAep) {
      if (!m.encrypted || !m.packFile || !m.plainSha256) { errors.push(`${name}: falta su ficha ${name}.json (créala con tools/encrypt-pack.js)`); continue; }
      if (m.external) {
        if (!/^https:\/\//.test(m.url || '') || !m.sha256 || !m.size) { errors.push(`${name}.json: un pack externo necesita url (https), sha256 y size`); continue; }
        e = { id: m.id || slug(name), name: m.name || name.replace(/\.[^.]+$/, ''), description: m.description || '', file: name, url: m.url, sha256: m.sha256, size: m.size };
      } else { e = entry(abs, `resources/resourcepacks/${cat}/${name}`); if (!e) continue; }
      Object.assign(e, { category: cat, encrypted: true, packFile: m.packFile, plainSha256: m.plainSha256, requiresOptifine: !!m.requiresOptifine, packFormat: m.packFormat, _meta: m });
    } else {
      const insp = inspectPack(abs);
      for (const w of insp.warnings) console.warn(`AVISO ${name}: ${w}`);
      if (!insp.ok) { errors.push(`${name}: pack inválido → ${insp.errors.join(' | ')}`); continue; }
      if (insp.info.usesOptifine && m.requiresOptifine === undefined) console.warn(`AVISO ${name}: usa funciones de OptiFine. Si solo debe cargarse con OptiFine, añade {"requiresOptifine": true} en ${name}.json`);
      e = entry(abs, `resources/resourcepacks/${cat}/${name}`, { category: cat });
      if (!e) continue;
      Object.assign(e, { requiresOptifine: !!m.requiresOptifine, packFormat: insp.info.packFormat });
    }
    const old = (prev.resourcepacks || []).find((p) => p.id === e.id);
    const fp = (x) => (x.plainSha256 || x.sha256);
    const changed = !old || fp(old) !== fp(e);
    e.version = e._meta.version || (!old ? '1.0.0' : changed ? bump(old.version) : old.version);
    e.updatedAt = changed ? today : old.updatedAt;
    e.priority = Number.isFinite(e._meta.priority) ? e._meta.priority : (old && old.priority) || 0;
    e.history = [...((old && old.history) || [])];
    if (changed) e.history.push({ version: e.version, sha256: fp(e), date: today });
    delete e._meta; packs.push(e);
  }
}
const media = { music: [], videos: [], images: [] };
const kinds = { music: ['.mp3', '.ogg', '.wav'], videos: ['.mp4', '.webm'], images: ['.png', '.jpg', '.jpeg', '.webp'] };
for (const [kind, exts] of Object.entries(kinds)) {
  for (const name of list(path.join(ROOT, 'resources', kind), exts)) {
    const e = entry(path.join(ROOT, 'resources', kind, name), `resources/${kind}/${name}`);
    if (!e) continue;
    const old = ((prev.media || {})[kind] || []).find((p) => p.id === e.id);
    e.version = e._meta.version || '1'; e.updatedAt = old && old.sha256 === e.sha256 ? old.updatedAt : new Date().toISOString().slice(0, 10);
    delete e._meta; media[kind].push(e);
  }
}

const out = { schemaVersion: 1, generatedAt: '', server: base.server, urls: base.urls, newsUrl: base.newsUrl, auth: base.auth || {}, maintenance: base.maintenance, launcher: base.launcher, settings: base.settings, resourcepacks: packs, media };
const v = validateManifest(out, '999.0.0');
if (!v.ok) errors.push(...v.errors);
const ids = new Set();
for (const p of packs) { if (ids.has(p.id)) errors.push('ID de pack duplicado: ' + p.id); ids.add(p.id); }

if (errors.length) { console.error('\n✘ No se pudo generar el manifiesto:\n - ' + errors.join('\n - ')); process.exit(1); }
const same = (a, b) => JSON.stringify({ ...a, generatedAt: '' }) === JSON.stringify({ ...b, generatedAt: '' });
if (same(out, prev) && fs.existsSync(OUT)) { console.log('Manifiesto sin cambios.'); process.exit(0); }
out.generatedAt = new Date().toISOString();
if (check) { console.log('Manifiesto válido (modo --check, no se escribió).'); process.exit(0); }
fs.writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n');
console.log(`✔ manifest.json generado: ${packs.length} packs, ${media.music.length} músicas, ${media.videos.length} vídeos, ${media.images.length} imágenes`);
