'use strict';
const fs = require('fs');
const { hashFile } = require('./util');
const { downloadRetry, pool } = require('./http');

async function valid(f, full) {
  try {
    const st = fs.statSync(f.dest);
    if (f.size != null && st.size !== f.size) return false;
    if (full && (f.sha1 || f.sha256)) return (await hashFile(f.dest, f.sha256 ? 'sha256' : 'sha1')) === (f.sha256 || f.sha1).toLowerCase();
    return true;
  } catch { return false; }
}

// Descarga solo lo que falta o está dañado. full=true comprueba el hash de TODO lo existente.
async function ensureFiles(list, { progress, signal, full = false, concurrency = 8, strict = false } = {}) {
  const seen = new Set();
  const missing = [];
  for (const f of list) {
    if (seen.has(f.dest)) continue;
    seen.add(f.dest);
    if (!(await valid(f, full))) missing.push(f);
  }
  if (!missing.length) return 0;
  progress.addTotal(missing.reduce((a, f) => a + (f.size || 0), 0));
  await pool(missing, concurrency, (f) => downloadRetry(f.url, f.dest, {
    sha1: f.sha1, sha256: f.sha256, size: f.size, signal, strict, onBytes: (b) => progress.add(b),
  }), signal);
  progress.push(true); // emite siempre el último valor (100 %) aunque el límite de frecuencia lo hubiera omitido
  return missing.length;
}
module.exports = { ensureFiles, valid };
