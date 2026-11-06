'use strict';
// Almacén cifrado con DPAPI de Windows (Electron safeStorage). Nunca se guardan contraseñas.
const { safeStorage } = require('electron');
const fs = require('fs');
const path = require('path');
const log = require('./logger');

let file = '';
let mem = { accounts: [], discord: null };

function init(dir) {
  file = path.join(dir, 'accounts.bin');
  try {
    if (fs.existsSync(file) && safeStorage.isEncryptionAvailable()) mem = { accounts: [], discord: null, ...JSON.parse(safeStorage.decryptString(fs.readFileSync(file))) };
  } catch (e) { log.warn('No se pudo leer el almacén de cuentas: ' + e.message); }
}
function persist() {
  if (!safeStorage.isEncryptionAvailable()) { log.warn('Cifrado del sistema no disponible: las sesiones no se recordarán.'); return false; }
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, safeStorage.encryptString(JSON.stringify(mem)));
  fs.renameSync(tmp, file);
  return true;
}
module.exports = { init, persist, data: () => mem };
