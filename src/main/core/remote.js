'use strict';
// Configuración centralizada + manifiesto remoto (GitHub). El servidor y las URLs se definen SOLO aquí
// (valores por defecto en config/launcher.config.json, sobrescribibles remotamente por el manifiesto).
const fs = require('fs');
const path = require('path');
const { getJson, setAllowedHosts } = require('./http');
const { validateManifest } = require('./manifest');
const log = require('./logger');

let bundled = null, manifest = null, status = { source: 'none', errors: [], fetchedAt: null };

function loadBundled(appDir) {
  bundled = JSON.parse(fs.readFileSync(path.join(appDir, 'config', 'launcher.config.json'), 'utf8'));
  setAllowedHosts(bundled.remote.allowedHosts);
  return bundled;
}
const empty = () => validateManifest({ schemaVersion: 1 }).manifest;

async function refresh({ cacheDir, appVersion, signal }) {
  const cache = path.join(cacheDir, 'manifest.json');
  try {
    const raw = await getJson(bundled.remote.manifestUrl, { signal, strict: true });
    const v = validateManifest(raw, appVersion);
    if (!v.ok) throw new Error('Manifiesto inválido: ' + v.errors.slice(0, 5).join('; '));
    manifest = v.manifest;
    fs.writeFileSync(cache, JSON.stringify(raw));
    status = { source: 'remote', errors: [], fetchedAt: new Date().toISOString() };
  } catch (e) {
    if (e.code === 'ABORTED') throw e;
    log.warn('No se pudo usar el manifiesto remoto: ' + e.message);
    try {
      const v = validateManifest(JSON.parse(fs.readFileSync(cache, 'utf8')), appVersion);
      if (!v.ok) throw new Error('caché inválida');
      manifest = v.manifest;
      status = { source: 'cache', errors: [e.message], fetchedAt: null };
    } catch {
      manifest = manifest || empty();
      status = { source: 'none', errors: [e.message], fetchedAt: null };
    }
  }
  return manifest;
}

const getManifest = () => manifest || empty();
const getStatus = () => status;

// Configuración efectiva: manifiesto remoto > valores por defecto incluidos en el launcher.
function server() {
  const b = bundled.server, r = getManifest().server;
  return { name: r.name || b.name, address: r.address || b.address, port: r.port || b.port, minecraftVersion: r.minecraftVersion || b.minecraftVersion };
}
function urls() { return { ...bundled.urls, ...getManifest().urls }; }
const brand = () => bundled.brand;
const authConfig = () => bundled.auth;

async function news(cacheDir) {
  const cache = path.join(cacheDir, 'news.json');
  const url = getManifest().newsUrl || bundled.remote.newsUrl;
  const clean = (j) => (Array.isArray(j.items) ? j.items : []).slice(0, 50).map((n) => ({
    id: String(n.id || '').slice(0, 64), type: ['anuncio', 'actualizacion', 'evento', 'mantenimiento', 'importante', 'texturepack', 'servidor'].includes(n.type) ? n.type : 'anuncio',
    title: String(n.title || '').slice(0, 140), date: String(n.date || '').slice(0, 20), summary: String(n.summary || '').slice(0, 400), body: String(n.body || '').slice(0, 5000),
    image: /^https:\/\//.test(n.image || '') ? n.image : '', url: /^https:\/\//.test(n.url || '') ? n.url : '', pinned: !!n.pinned,
  })).filter((n) => n.id && n.title);
  try {
    const j = await getJson(url, { strict: true });
    fs.writeFileSync(cache, JSON.stringify(j));
    return { items: clean(j), cached: false };
  } catch (e) {
    try { return { items: clean(JSON.parse(fs.readFileSync(cache, 'utf8'))), cached: true }; } catch { return { items: [], cached: false, error: e.message }; }
  }
}

module.exports = { loadBundled, refresh, getManifest, getStatus, server, urls, brand, authConfig, news };
