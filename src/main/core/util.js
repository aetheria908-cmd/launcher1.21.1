'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function hashFile(file, alg = 'sha256') {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash(alg);
    const s = fs.createReadStream(file);
    s.on('error', reject);
    s.on('data', (d) => h.update(d));
    s.on('end', () => resolve(h.digest('hex')));
  });
}

// Une rutas y bloquea cualquier intento de salir de la carpeta base (path traversal / ZIP Slip).
function safeJoin(base, ...parts) {
  const b = path.resolve(base);
  const p = path.resolve(b, ...parts);
  if (p !== b && !p.startsWith(b + path.sep)) throw new Error('Ruta insegura bloqueada: ' + parts.join('/'));
  return p;
}

const SAFE_NAME = /^[A-Za-z0-9][A-Za-z0-9._ ()+\-]{0,119}$/;
function isSafeFileName(n) {
  return typeof n === 'string' && SAFE_NAME.test(n) && !n.includes('..');
}

function validateOfflineName(n) {
  return typeof n === 'string' && /^[A-Za-z0-9_]{3,16}$/.test(n);
}

// Mismo algoritmo que Java UUID.nameUUIDFromBytes("OfflinePlayer:<nombre>")
function offlineUuid(name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name, 'utf8').digest();
  h[6] = (h[6] & 0x0f) | 0x30;
  h[8] = (h[8] & 0x3f) | 0x80;
  const x = h.toString('hex');
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}

function archName() {
  return process.arch === 'ia32' ? 'x86' : process.arch;
}

// Evalúa las "rules" de los JSON de versión de Mojang.
function evalRules(rules, features = {}, env = {}) {
  if (!rules || !rules.length) return true;
  const osName = env.os || 'windows';
  const arch = env.arch || archName();
  const osVer = env.osVersion || os.release();
  let allowed = false;
  for (const r of rules) {
    let m = true;
    if (r.os) {
      if (r.os.name && r.os.name !== osName) m = false;
      if (r.os.arch && r.os.arch !== arch) m = false;
      if (r.os.version && !new RegExp(r.os.version).test(osVer)) m = false;
    }
    if (r.features) {
      for (const [k, v] of Object.entries(r.features)) if (!!features[k] !== v) m = false;
    }
    if (m) allowed = r.action === 'allow';
  }
  return allowed;
}

function mavenPath(name) {
  const [g, a, v, c] = String(name).split(':');
  return `${g.replace(/\./g, '/')}/${a}/${v}/${a}-${v}${c ? '-' + c : ''}.jar`;
}

const JVM_DENY = ['-javaagent', '-agentlib', '-agentpath', '-xbootclasspath', '-xrun', '-xx:onerror',
  '-xx:onoutofmemoryerror', '-xx:flight', '-cp', '-classpath', '--class-path', '-jar',
  '-djava.library.path', '-djava.home', '-xx:+flightrecorder', '-xdebug'];

// Valida argumentos JVM del usuario: sin comillas, sin shell, sin agentes ni ejecución de comandos.
function parseJvmArgs(str) {
  const args = [];
  const rejected = [];
  for (const t of String(str || '').split(/\s+/).filter(Boolean)) {
    const bad = !/^-{1,2}[A-Za-z0-9_.:=+\-/\\,@]+$/.test(t) || JVM_DENY.some((d) => t.toLowerCase().startsWith(d));
    (bad ? rejected : args).push(t);
  }
  return { args, rejected };
}

function memoryLimits(totalMb) {
  const maxAllowed = Math.max(1024, Math.min(Math.floor(totalMb * 0.75), totalMb - 1024));
  return { maxAllowed: Math.floor(maxAllowed / 256) * 256 };
}
function clampRam(ram, totalMb) {
  const { maxAllowed } = memoryLimits(totalMb);
  const max = Math.min(Math.max(Number(ram.max) || 2048, 1024), maxAllowed);
  const min = Math.min(Math.max(Number(ram.min) || 1024, 512), max);
  return { min, max, maxAllowed };
}
function autoRam(totalMb) {
  const { maxAllowed } = memoryLimits(totalMb);
  const guess = Math.min(Math.max(Math.floor(totalMb / 4 / 512) * 512, 2048), 6144);
  return { min: 1024, max: Math.min(guess, maxAllowed), maxAllowed };
}

function compareVersions(a, b) {
  const pa = String(a).split('.').map((n) => parseInt(n, 10) || 0);
  const pb = String(b).split('.').map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d < 0 ? -1 : 1;
  }
  return 0;
}

function formatBytes(n) {
  if (!Number.isFinite(n)) return '-';
  const u = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return n.toFixed(i ? 1 : 0) + ' ' + u[i];
}

module.exports = { sleep, hashFile, safeJoin, isSafeFileName, validateOfflineName, offlineUuid, evalRules,
  mavenPath, parseJvmArgs, clampRam, autoRam, memoryLimits, compareVersions, formatBytes, archName };
