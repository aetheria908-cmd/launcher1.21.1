'use strict';
const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { hashFile } = require('./util');

const UA = 'AetheriaRPG-Launcher/1.0';
let allowedHosts = [];

function herr(code, msg) { const e = new Error(msg); e.code = code; return e; }
const abortErr = () => herr('ABORTED', 'Operación cancelada');
function setAllowedHosts(list) { allowedHosts = (list || []).map((h) => h.toLowerCase()); }

function checkUrl(u, strict) {
  const url = new URL(u);
  const local = url.hostname === 'localhost' || url.hostname === '127.0.0.1';
  if (url.protocol !== 'https:' && !(local && url.protocol === 'http:')) throw herr('URL_NOT_HTTPS', 'Solo se permiten conexiones HTTPS: ' + url.host);
  if (strict && allowedHosts.length && !allowedHosts.some((h) => url.hostname === h || url.hostname.endsWith('.' + h)))
    throw herr('HOST_NOT_ALLOWED', 'Host no permitido por la configuración del launcher: ' + url.hostname);
  return url;
}

function open(u, { headers = {}, signal, strict = false, redirects = 5, method = 'GET', body = null, timeout = 25000 } = {}) {
  return new Promise((resolve, reject) => {
    let url;
    try { url = checkUrl(u, strict); } catch (e) { return reject(e); }
    const lib = url.protocol === 'https:' ? https : http;
    const req = lib.request(url, { method, headers: { 'User-Agent': UA, ...headers }, timeout }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
        res.resume();
        if (redirects <= 0) return reject(herr('TOO_MANY_REDIRECTS', 'Demasiadas redirecciones'));
        const next = new URL(res.headers.location, url).toString();
        return resolve(open(next, { headers, signal, strict, redirects: redirects - 1, method: res.statusCode === 303 ? 'GET' : method, body: res.statusCode === 303 ? null : body, timeout }));
      }
      resolve(res);
    });
    req.on('timeout', () => req.destroy(herr('TIMEOUT', 'Tiempo de espera agotado')));
    req.on('error', reject);
    if (signal) {
      if (signal.aborted) { req.destroy(); return reject(abortErr()); }
      signal.addEventListener('abort', () => req.destroy(abortErr()), { once: true });
    }
    if (body) req.write(body);
    req.end();
  });
}

function readBody(res, max = 20 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []; let n = 0;
    res.on('data', (c) => { n += c.length; if (n > max) { res.destroy(herr('TOO_BIG', 'Respuesta demasiado grande')); return; } chunks.push(c); });
    res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    res.on('error', reject);
  });
}

// Petición con cuerpo JSON o formulario; devuelve { status, json, text }.
async function fetchJson(url, { method = 'GET', headers = {}, json, form, signal, strict = false } = {}) {
  let body = null;
  const h = { Accept: 'application/json', ...headers };
  if (json !== undefined) { body = JSON.stringify(json); h['Content-Type'] = 'application/json'; }
  else if (form) { body = new URLSearchParams(form).toString(); h['Content-Type'] = 'application/x-www-form-urlencoded'; }
  if (body) h['Content-Length'] = Buffer.byteLength(body);
  const res = await open(url, { method, headers: h, body, signal, strict });
  const text = await readBody(res);
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { /* no es JSON */ }
  return { status: res.statusCode, json: parsed, text };
}
async function getJson(url, opts = {}) {
  const r = await fetchJson(url, opts);
  if (r.status !== 200) throw herr('HTTP_' + r.status, `Error HTTP ${r.status} en ${new URL(url).host}`);
  if (r.json === null) throw herr('BAD_JSON', 'Respuesta no válida (no es JSON)');
  return r.json;
}
async function getText(url, opts = {}) {
  const res = await open(url, opts);
  if (res.statusCode !== 200) { res.resume(); throw herr('HTTP_' + res.statusCode, `Error HTTP ${res.statusCode}`); }
  return readBody(res);
}

// Descarga reanudable (.part + Range), verificación de tamaño y SHA, progreso por bytes.
async function download(url, dest, { sha256, sha1, size, onBytes, signal, strict = false } = {}) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const part = dest + '.part';
  let start = 0;
  try { start = fs.statSync(part).size; } catch { /* sin parcial */ }
  if (size && start > size) { fs.rmSync(part, { force: true }); start = 0; }
  if (!(size && start === size)) {
    const res = await open(url, { headers: start ? { Range: `bytes=${start}-` } : {}, signal, strict });
    if (res.statusCode === 416) { res.resume(); fs.rmSync(part, { force: true }); return download(url, dest, { sha256, sha1, size, onBytes, signal, strict }); }
    if (res.statusCode !== 200 && res.statusCode !== 206) { res.resume(); throw herr('HTTP_' + res.statusCode, `Error HTTP ${res.statusCode} al descargar ${path.basename(dest)}`); }
    let flags = 'a';
    if (res.statusCode === 200) { start = 0; flags = 'w'; } else if (onBytes && start) onBytes(start);
    await new Promise((resolve, reject) => {
      const out = fs.createWriteStream(part, { flags });
      res.on('data', (c) => onBytes && onBytes(c.length));
      res.on('error', (e) => { out.destroy(); reject(e); });
      res.on('close', () => { if (!res.complete) reject(herr('INTERRUPTED', 'Descarga interrumpida')); });
      out.on('error', reject);
      out.on('finish', resolve);
      res.pipe(out);
    });
  }
  const got = fs.statSync(part).size;
  if (size && got > size) { fs.rmSync(part, { force: true }); throw herr('SIZE_MISMATCH', `Tamaño incorrecto en ${path.basename(dest)}`); }
  if (size && got < size) throw herr('INCOMPLETE', `Descarga incompleta de ${path.basename(dest)}`);
  const want = (sha256 || sha1 || '').toLowerCase();
  if (want) {
    const h = await hashFile(part, sha256 ? 'sha256' : 'sha1');
    if (h !== want) { fs.rmSync(part, { force: true }); throw herr('HASH_MISMATCH', `Verificación SHA fallida en ${path.basename(dest)}`); }
  }
  fs.renameSync(part, dest);
}

async function downloadRetry(url, dest, opts = {}, tries = 3) {
  const outer = opts.onBytes;
  for (let i = 1; ; i++) {
    let counted = 0;
    try {
      return await download(url, dest, { ...opts, onBytes: outer ? (b) => { counted += b; outer(b); } : undefined });
    } catch (e) {
      if (e.code === 'ABORTED' || opts.signal?.aborted) throw abortErr();
      if (outer && counted) outer(-counted);
      if (i >= tries || ['URL_NOT_HTTPS', 'HOST_NOT_ALLOWED', 'HTTP_404', 'HTTP_403'].includes(e.code)) throw e;
      await new Promise((r) => setTimeout(r, 800 * i));
    }
  }
}

async function pool(items, n, fn, signal) {
  let i = 0, failure = null;
  const worker = async () => {
    while (i < items.length && !failure) {
      if (signal && signal.aborted) throw abortErr();
      const it = items[i++];
      try { await fn(it); } catch (e) { failure = failure || e; }
    }
  };
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, worker));
  if (failure) throw failure;
}

module.exports = { open, fetchJson, getJson, getText, download, downloadRetry, pool, setAllowedHosts, abortErr, herr, checkUrl };
