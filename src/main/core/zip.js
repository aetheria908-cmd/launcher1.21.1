'use strict';
// Lector ZIP mínimo (sin dependencias) para validar Texture Packs. No extrae nada a disco.
const fs = require('fs');
const zlib = require('zlib');

const MAX_ENTRY = 200 * 1024 * 1024;

function zerr(code, msg) { const e = new Error(msg); e.code = code; return e; }

function listEntries(file) {
  const fd = fs.openSync(file, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    if (size < 22) throw zerr('ZIP_INVALID', 'El archivo es demasiado pequeño para ser un ZIP');
    const tail = Buffer.alloc(Math.min(size, 65557));
    fs.readSync(fd, tail, 0, tail.length, size - tail.length);
    let eocd = -1;
    for (let i = tail.length - 22; i >= 0; i--) if (tail.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    if (eocd < 0) throw zerr('ZIP_INVALID', 'No es un ZIP válido (falta el directorio central)');
    const total = tail.readUInt16LE(eocd + 10);
    const cdSize = tail.readUInt32LE(eocd + 12);
    const cdOff = tail.readUInt32LE(eocd + 16);
    if (total === 0xffff || cdOff === 0xffffffff) throw zerr('ZIP64', 'ZIP64 no soportado');
    if (cdOff + cdSize > size) throw zerr('ZIP_INVALID', 'Directorio central fuera de rango (archivo truncado)');
    const cd = Buffer.alloc(cdSize);
    fs.readSync(fd, cd, 0, cdSize, cdOff);
    const entries = [];
    let p = 0;
    for (let i = 0; i < total; i++) {
      if (p + 46 > cd.length || cd.readUInt32LE(p) !== 0x02014b50) throw zerr('ZIP_INVALID', 'Directorio central dañado');
      const flags = cd.readUInt16LE(p + 8);
      const nlen = cd.readUInt16LE(p + 28), elen = cd.readUInt16LE(p + 30), clen = cd.readUInt16LE(p + 32);
      entries.push({
        name: cd.toString('utf8', p + 46, p + 46 + nlen),
        method: cd.readUInt16LE(p + 10),
        csize: cd.readUInt32LE(p + 20),
        usize: cd.readUInt32LE(p + 24),
        offset: cd.readUInt32LE(p + 42),
        encrypted: !!(flags & 1),
      });
      p += 46 + nlen + elen + clen;
    }
    return entries;
  } finally { fs.closeSync(fd); }
}

function readEntry(file, entry, maxBytes = 5 * 1024 * 1024) {
  if (entry.encrypted) throw zerr('ZIP_ENCRYPTED', 'Entrada cifrada no soportada');
  if (entry.usize > maxBytes) throw zerr('ZIP_TOO_BIG', 'Entrada demasiado grande para leerla');
  const fd = fs.openSync(file, 'r');
  try {
    const h = Buffer.alloc(30);
    fs.readSync(fd, h, 0, 30, entry.offset);
    if (h.readUInt32LE(0) !== 0x04034b50) throw zerr('ZIP_INVALID', 'Cabecera local dañada');
    const start = entry.offset + 30 + h.readUInt16LE(26) + h.readUInt16LE(28);
    const raw = Buffer.alloc(entry.csize);
    fs.readSync(fd, raw, 0, entry.csize, start);
    if (entry.method === 0) return raw;
    if (entry.method === 8) return zlib.inflateRawSync(raw, { maxOutputLength: maxBytes });
    throw zerr('ZIP_METHOD', 'Método de compresión no soportado: ' + entry.method);
  } finally { fs.closeSync(fd); }
}

// ZIP Slip, rutas absolutas, bombas de descompresión.
function checkEntryNames(entries) {
  const problems = [];
  let total = 0;
  for (const e of entries) {
    const n = e.name;
    if (n.includes('\0') || n.includes('\\') || n.startsWith('/') || /^[A-Za-z]:/.test(n) || n.split('/').includes('..'))
      problems.push('Ruta peligrosa en el ZIP: ' + JSON.stringify(n));
    total += e.usize;
    if (e.usize > 1024 * 1024 * 1024) problems.push('Entrada gigante: ' + n);
    if (e.csize > 0 && e.usize / e.csize > 1000 && e.usize > 50 * 1024 * 1024) problems.push('Posible bomba de descompresión: ' + n);
  }
  if (total > 4 * 1024 * 1024 * 1024) problems.push('Tamaño descomprimido total excesivo');
  return { problems, totalUncompressed: total };
}

module.exports = { listEntries, readEntry, checkEntryNames, MAX_ENTRY };
