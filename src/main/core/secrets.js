'use strict';
// La clave de los packs privados NO está en el repositorio: se inyecta al compilar (config/secrets.json, ignorado por git)
// desde el secreto PACK_KEY de GitHub Actions. Para pruebas/herramientas también se acepta AETHERIA_PACK_KEY.
const fs = require('fs');
const path = require('path');
const { keyFromBase64 } = require('./packcrypto');
let cached;

function getPackKey() {
  if (process.env.AETHERIA_PACK_KEY) return keyFromBase64(process.env.AETHERIA_PACK_KEY);
  if (cached !== undefined) return cached;
  try {
    const dir = require('./paths').appDir();
    cached = keyFromBase64(JSON.parse(fs.readFileSync(path.join(dir, 'config', 'secrets.json'), 'utf8')).packKey);
  } catch { cached = null; }
  return cached;
}
module.exports = { getPackKey };
