'use strict';
// Genera build/icon.png (512x512) sin dependencias: escudo morado con torre dorada.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const N = 512;
const crcT = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcT[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => { const t = Buffer.from(type), l = Buffer.alloc(4), c = Buffer.alloc(4); l.writeUInt32BE(data.length); c.writeUInt32BE(crc(Buffer.concat([t, data]))); return Buffer.concat([l, t, data, c]); };
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));

function pixel(x, y) {
  const dx = x - 256, dy = y - 256, r = Math.hypot(dx, dy);
  if (r > 250) return [0, 0, 0, 0];
  if (r > 232) return [...mix([236, 210, 143], [138, 108, 51], (y / N)), 255]; // aro dorado
  if (r > 224) return [20, 14, 30, 255];
  const sky = mix([48, 26, 84], [12, 9, 20], y / N);
  const inRect = (x0, y0, x1, y1) => x >= x0 && x <= x1 && y >= y0 && y <= y1;
  const tooth = inRect(170, 140, 210, 180) || inRect(236, 140, 276, 180) || inRect(302, 140, 342, 180);
  const body = inRect(170, 180, 342, 410);
  const door = inRect(234, 340, 278, 410) || Math.hypot(x - 256, y - 340) < 22;
  const slit = inRect(250, 235, 262, 295);
  if ((tooth || body) && !door && !slit) return [...mix([236, 210, 143], [168, 132, 60], (y - 140) / 270), 255];
  if (door || slit) return [18, 12, 26, 255];
  return [...sky, 255];
}

const raw = Buffer.alloc(N * (N * 4 + 1));
for (let y = 0; y < N; y++) {
  raw[y * (N * 4 + 1)] = 0;
  for (let x = 0; x < N; x++) { const p = pixel(x, y); raw.set(p, y * (N * 4 + 1) + 1 + x * 4); }
}
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(N, 0); ihdr.writeUInt32BE(N, 4); ihdr[8] = 8; ihdr[9] = 6;
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
const out = path.join(__dirname, '..', 'build', 'icon.png');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, png);
console.log('Icono generado:', out, png.length, 'bytes');
