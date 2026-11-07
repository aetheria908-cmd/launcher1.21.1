'use strict';
// Cifrado de Texture Packs privados: AES-256-GCM (confidencialidad + autenticidad). Formato .aep:
//   "AEP1" (4 bytes) | IV (12) | datos cifrados | etiqueta GCM (16)
// El AAD liga el archivo a su id y al SHA-256 del pack original: no se puede intercambiar ni manipular el manifiesto.
const crypto = require('crypto');
const fs = require('fs');
const { pipeline } = require('stream/promises');

const MAGIC = Buffer.from('AEP1');
const aad = (id, plainSha256) => Buffer.from(`aep1|${id}|${String(plainSha256).toLowerCase()}`);

function keyFromBase64(b64) {
  const k = Buffer.from(String(b64 || ''), 'base64');
  return k.length === 32 ? k : null;
}

function encryptFile(src, dest, key, aadBuf) {
  return new Promise((resolve, reject) => {
    const iv = crypto.randomBytes(12);
    const c = crypto.createCipheriv('aes-256-gcm', key, iv);
    c.setAAD(aadBuf);
    const inp = fs.createReadStream(src), out = fs.createWriteStream(dest);
    const fail = (e) => { inp.destroy(); out.destroy(); fs.rmSync(dest, { force: true }); reject(e); };
    inp.on('error', fail); c.on('error', fail); out.on('error', fail);
    out.write(Buffer.concat([MAGIC, iv]));
    c.on('data', (d) => { if (!out.write(d)) { c.pause(); out.once('drain', () => c.resume()); } });
    c.on('end', () => out.end(c.getAuthTag()));
    out.on('finish', resolve);
    inp.pipe(c);
  });
}

async function decryptFile(src, dest, key, aadBuf) {
  const size = fs.statSync(src).size;
  if (size < 4 + 12 + 16 + 1) throw new Error('Archivo cifrado demasiado pequeño o dañado');
  const fd = fs.openSync(src, 'r');
  const head = Buffer.alloc(16), tag = Buffer.alloc(16);
  try { fs.readSync(fd, head, 0, 16, 0); fs.readSync(fd, tag, 0, 16, size - 16); } finally { fs.closeSync(fd); }
  if (!head.subarray(0, 4).equals(MAGIC)) throw new Error('No es un pack cifrado válido (cabecera incorrecta)');
  const d = crypto.createDecipheriv('aes-256-gcm', key, head.subarray(4, 16));
  d.setAAD(aadBuf); d.setAuthTag(tag);
  const tmp = dest + '.dec';
  try {
    await pipeline(fs.createReadStream(src, { start: 16, end: size - 17 }), d, fs.createWriteStream(tmp));
  } catch (e) {
    fs.rmSync(tmp, { force: true });
    throw new Error(/authenticate/i.test(e.message) ? 'No se pudo descifrar el pack: clave incorrecta o archivo manipulado' : e.message);
  }
  fs.renameSync(tmp, dest);
}

module.exports = { encryptFile, decryptFile, aad, keyFromBase64 };
