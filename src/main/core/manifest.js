'use strict';
// Validación estricta del manifiesto remoto. Solo se aceptan DATOS; nunca se ejecuta nada del manifiesto.
const { isSafeFileName, compareVersions } = require('./util');

const SUPPORTED_SCHEMA = 1;
const ID = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const SHA = /^[a-f0-9]{64}$/i;
const HOST = /^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$/;
const MAX_SIZE = 2 * 1024 * 1024 * 1024;

// Solo https; http únicamente hacia la propia máquina (mismo criterio que core/http.js, útil para pruebas).
function httpsUrl(u) { try { const x = new URL(u); return x.protocol === 'https:' || (x.protocol === 'http:' && (x.hostname === 'localhost' || x.hostname === '127.0.0.1')); } catch { return false; } }
const str = (v, max = 500) => (typeof v === 'string' ? v.slice(0, max) : '');

function resource(r, kinds, errors, where) {
  if (!r || typeof r !== 'object') { errors.push(where + ': entrada inválida'); return null; }
  const out = { id: str(r.id, 64), name: str(r.name || r.title || r.id, 120), version: str(r.version || '1', 40), file: str(r.file, 120),
    url: str(r.url, 1000), sha256: str(r.sha256, 64).toLowerCase(), size: r.size };
  if (!ID.test(out.id)) errors.push(`${where}: id inválido "${out.id}"`);
  if (!isSafeFileName(out.file)) errors.push(`${where}: nombre de archivo inválido "${out.file}"`);
  if (!httpsUrl(out.url)) errors.push(`${where}: la URL debe ser https`);
  if (!SHA.test(out.sha256)) errors.push(`${where}: sha256 inválido`);
  if (!Number.isInteger(out.size) || out.size <= 0 || out.size > MAX_SIZE) errors.push(`${where}: tamaño inválido`);
  out.description = str(r.description, 400);
  out.updatedAt = str(r.updatedAt, 40);
  if (kinds === 'pack') {
    out.category = r.category === 'optional' ? 'optional' : 'required';
    out.priority = Number.isFinite(r.priority) ? r.priority : 0;
    out.packFormat = Number.isInteger(r.packFormat) ? r.packFormat : null;
    out.history = Array.isArray(r.history) ? r.history.slice(0, 50).map((h) => ({ version: str(h.version, 40), sha256: str(h.sha256, 64), date: str(h.date, 40) })) : [];
    out.requiresOptifine = !!r.requiresOptifine;
    out.encrypted = !!r.encrypted;
    if (out.encrypted) {
      out.packFile = str(r.packFile, 120);
      out.plainSha256 = str(r.plainSha256, 64).toLowerCase();
      if (!isSafeFileName(out.packFile) || !/\.zip$/i.test(out.packFile)) errors.push(`${where}: packFile inválido`);
      if (!SHA.test(out.plainSha256)) errors.push(`${where}: plainSha256 inválido`);
      if (!/\.aep$/i.test(out.file)) errors.push(`${where}: un pack cifrado debe ser .aep`);
    } else if (!/\.zip$/i.test(out.file)) errors.push(`${where}: los packs deben ser .zip`);
  }
  return out;
}

function validateManifest(m, appVersion = '0.0.0') {
  const errors = [];
  if (!m || typeof m !== 'object') return { ok: false, errors: ['Manifiesto vacío o ilegible'] };
  if (!Number.isInteger(m.schemaVersion)) errors.push('Falta schemaVersion');
  else if (m.schemaVersion > SUPPORTED_SCHEMA) return { ok: false, errors: [`El manifiesto usa el esquema ${m.schemaVersion}; este launcher solo entiende hasta ${SUPPORTED_SCHEMA}. Actualiza el launcher.`], tooNew: true };
  const out = { schemaVersion: m.schemaVersion, generatedAt: str(m.generatedAt, 40) };

  const s = m.server || {};
  out.server = {};
  if (s.address !== undefined) {
    if (!HOST.test(String(s.address))) errors.push('server.address inválida'); else out.server.address = String(s.address);
  }
  if (s.port !== undefined) {
    if (!Number.isInteger(s.port) || s.port < 1 || s.port > 65535) errors.push('server.port inválido'); else out.server.port = s.port;
  }
  if (s.minecraftVersion !== undefined) {
    if (!/^\d+\.\d+(\.\d+)?$/.test(String(s.minecraftVersion))) errors.push('server.minecraftVersion inválida'); else out.server.minecraftVersion = String(s.minecraftVersion);
  }
  if (s.name) out.server.name = str(s.name, 60);

  out.urls = {};
  for (const [k, v] of Object.entries(m.urls || {})) {
    if (/^[a-z]{2,20}$/.test(k) && httpsUrl(v)) out.urls[k] = v; else errors.push(`urls.${k} inválida (debe ser https)`);
  }
  out.auth = {};
  const au = m.auth || {};
  const authRules = [['microsoftClientId', /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i], ['discordClientId', /^\d{15,25}$/], ['discordGuildId', /^\d{15,25}$/]];
  for (const [k, re] of authRules) {
    if (au[k] === undefined || au[k] === '') continue;
    if (re.test(String(au[k]))) out.auth[k] = String(au[k]); else errors.push(`auth.${k} no tiene un formato válido`);
  }
  if (au.discordTokenExchangeUrl) { if (httpsUrl(au.discordTokenExchangeUrl)) out.auth.discordTokenExchangeUrl = au.discordTokenExchangeUrl; else errors.push('auth.discordTokenExchangeUrl debe ser https'); }
  out.newsUrl = m.newsUrl && httpsUrl(m.newsUrl) ? m.newsUrl : '';
  out.maintenance = { enabled: !!(m.maintenance && m.maintenance.enabled), message: str(m.maintenance && m.maintenance.message, 400) };
  out.launcher = { minVersion: str(m.launcher && m.launcher.minVersion, 20) || '0.0.0', message: str(m.launcher && m.launcher.message, 300) };
  out.settings = { defaultBackground: str(m.settings && m.settings.defaultBackground, 64), defaultVideo: str(m.settings && m.settings.defaultVideo, 64), defaultMusic: str(m.settings && m.settings.defaultMusic, 64) };

  out.resourcepacks = [];
  const seen = new Set();
  (Array.isArray(m.resourcepacks) ? m.resourcepacks : []).forEach((r, i) => {
    const p = resource(r, 'pack', errors, `resourcepacks[${i}]`);
    if (p) {
      if (seen.has(p.id)) errors.push('id duplicado: ' + p.id);
      seen.add(p.id);
      const gf = (p.packFile || p.file).toLowerCase();
      if (seen.has('f:' + gf)) errors.push('nombre de archivo duplicado en resourcepacks: ' + gf);
      seen.add('f:' + gf);
      out.resourcepacks.push(p);
    }
  });
  out.media = { music: [], videos: [], images: [] };
  for (const k of Object.keys(out.media)) {
    (Array.isArray(m.media && m.media[k]) ? m.media[k] : []).forEach((r, i) => {
      const p = resource(r, 'media', errors, `media.${k}[${i}]`);
      if (p) out.media[k].push(p);
    });
  }
  out.needsLauncherUpdate = compareVersions(appVersion, out.launcher.minVersion) < 0;
  return { ok: errors.length === 0, errors, manifest: out };
}

module.exports = { validateManifest, SUPPORTED_SCHEMA };
