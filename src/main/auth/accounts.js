'use strict';
// Discord, Microsoft y perfil offline son sistemas INDEPENDIENTES.
const store = require('../core/store');
const settings = require('../core/settings');
const ms = require('./microsoft');
const offline = require('./offline');
const { herr } = require('../core/http');

const pub = (a, sel) => ({ id: a.id, type: a.type, name: a.name, uuid: a.uuid, selected: a.id === sel });

function list() {
  const sel = settings.get().selectedAccount;
  return store.data().accounts.map((a) => pub(a, sel));
}
function select(id) {
  if (!store.data().accounts.some((a) => a.id === id)) throw new Error('Cuenta desconocida');
  settings.patch({ selectedAccount: id });
}
function upsert(acc) {
  const d = store.data();
  const i = d.accounts.findIndex((a) => a.id === acc.id);
  if (i >= 0) d.accounts[i] = { ...d.accounts[i], ...acc }; else d.accounts.push(acc);
  store.persist();
  select(acc.id);
}
function remove(id) {
  const d = store.data();
  d.accounts = d.accounts.filter((a) => a.id !== id);
  store.persist();
  if (settings.get().selectedAccount === id) settings.patch({ selectedAccount: d.accounts[0] ? d.accounts[0].id : '' });
}
function addOffline(name) { upsert(offline.create(name)); }
function addMicrosoft(profile) {
  upsert({ id: 'ms:' + profile.uuid, type: 'microsoft', name: profile.name, uuid: profile.uuid, refreshToken: profile.refreshToken, mcToken: profile.mcToken, mcExpires: profile.mcExpires });
}

// Devuelve los datos que necesita el juego, renovando el token si hace falta.
async function resolveForLaunch(clientId, signal) {
  const sel = settings.get().selectedAccount;
  const a = store.data().accounts.find((x) => x.id === sel);
  if (!a) throw herr('NO_ACCOUNT', 'Selecciona una cuenta (Microsoft o perfil offline) antes de jugar.');
  if (a.type === 'offline') return { name: a.name, uuid: a.uuid, token: '0', userType: 'legacy', type: 'offline' };
  if (!a.mcToken || Date.now() >= a.mcExpires) {
    const t = await ms.refreshToken(clientId, a.refreshToken, signal);
    const p = await ms.loginWithTokens(t, signal);
    Object.assign(a, { name: p.name, uuid: p.uuid, mcToken: p.mcToken, mcExpires: p.mcExpires, refreshToken: p.refreshToken });
    store.persist();
  }
  return { name: a.name, uuid: a.uuid, token: a.mcToken, userType: 'msa', type: 'microsoft' };
}

function discord() { const d = store.data().discord; return d ? { id: d.id, username: d.username, avatar: d.avatar, member: d.member } : null; }
function setDiscord(p) { store.data().discord = p; store.persist(); }
function clearDiscord() { store.data().discord = null; store.persist(); }

module.exports = { list, select, remove, addOffline, addMicrosoft, resolveForLaunch, discord, setDiscord, clearDiscord };
