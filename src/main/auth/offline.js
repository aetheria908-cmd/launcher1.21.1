'use strict';
const { validateOfflineName, offlineUuid } = require('../core/util');

// Perfil local SIN autenticación oficial. No es una cuenta premium ni se hace pasar por una.
function create(name) {
  if (!validateOfflineName(name)) throw new Error('El nombre debe tener de 3 a 16 caracteres: letras, números o guion bajo (_).');
  return { id: 'offline:' + name.toLowerCase(), type: 'offline', name, uuid: offlineUuid(name) };
}
module.exports = { create };
