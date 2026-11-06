'use strict';
// Server List Ping (protocolo oficial de Minecraft). No inventa datos: si no responde, devuelve online:false.
const net = require('net');
const dns = require('dns');

function varInt(n) { const o = []; do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; o.push(b); } while (n); return Buffer.from(o); }
function readVarInt(buf, off = 0) {
  let v = 0, s = 0;
  for (let i = 0; i < 5; i++) {
    if (off + i >= buf.length) return null;
    const b = buf[off + i];
    v |= (b & 0x7f) << s;
    if (!(b & 0x80)) return { value: v, size: i + 1 };
    s += 7;
  }
  throw new Error('VarInt demasiado largo');
}
function packet(id, data) { const body = Buffer.concat([varInt(id), data]); return Buffer.concat([varInt(body.length), body]); }
function mcString(s) { const b = Buffer.from(s, 'utf8'); return Buffer.concat([varInt(b.length), b]); }
function flatten(c) {
  if (c == null) return '';
  if (typeof c === 'string') return c;
  let s = c.text || '';
  if (Array.isArray(c.extra)) s += c.extra.map(flatten).join('');
  return s;
}
const clean = (s) => String(s).replace(/§./g, '').trim();

// Lee un paquete completo del buffer: { id, payload, consumed } o null si está incompleto.
function readPacket(buf) {
  const len = readVarInt(buf, 0);
  if (!len || buf.length < len.size + len.value) return null;
  const body = buf.subarray(len.size, len.size + len.value);
  const id = readVarInt(body, 0);
  return { id: id.value, payload: body.subarray(id.size), consumed: len.size + len.value };
}

async function pingServer(host, port, timeout = 4000, protocol = 767) {
  let target = { host, port };
  try {
    if (!net.isIP(host) && host !== 'localhost') {
      const srv = await dns.promises.resolveSrv('_minecraft._tcp.' + host);
      if (srv && srv[0]) target = { host: srv[0].name, port: srv[0].port };
    }
  } catch { /* sin SRV */ }
  return new Promise((resolve) => {
    let buf = Buffer.alloc(0), stage = 0, t0 = 0, status = null, finished = false;
    const sock = net.connect({ host: target.host, port: target.port });
    const done = (r) => { if (finished) return; finished = true; sock.destroy(); resolve(r); };
    const result = (latency) => ({ online: true, motd: clean(flatten(status.description)), version: status.version || null,
      players: status.players ? { online: status.players.online, max: status.players.max } : null, latency });
    sock.setTimeout(timeout, () => done(status ? result(null) : { online: false, reason: 'timeout' }));
    sock.on('error', (e) => done({ online: false, reason: e.code || e.message }));
    sock.on('connect', () => {
      const p = Buffer.alloc(2); p.writeUInt16BE(port);
      sock.write(packet(0, Buffer.concat([varInt(protocol), mcString(host), p, varInt(1)])));
      sock.write(packet(0, Buffer.alloc(0)));
    });
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      try {
        for (;;) {
          const pk = readPacket(buf);
          if (!pk) return;
          buf = buf.subarray(pk.consumed);
          if (stage === 0 && pk.id === 0) {
            const l = readVarInt(pk.payload, 0);
            status = JSON.parse(pk.payload.toString('utf8', l.size, l.size + l.value));
            stage = 1; t0 = Date.now();
            const payload = Buffer.alloc(8); payload.writeBigInt64BE(BigInt(t0));
            sock.write(packet(1, payload));
          } else if (stage === 1 && pk.id === 1) return done(result(Date.now() - t0));
        }
      } catch (e) { done(status ? result(null) : { online: false, reason: 'respuesta inválida' }); }
    });
  });
}

module.exports = { pingServer, varInt, readVarInt, packet, mcString, readPacket, flatten };
