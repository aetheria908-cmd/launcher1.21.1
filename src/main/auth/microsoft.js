'use strict';
// Inicio de sesión oficial: Microsoft (flujo de código de dispositivo) → Xbox Live → XSTS → Minecraft Services.
// No se pide ni se guarda ninguna contraseña. Solo se guarda el refresh token, cifrado con DPAPI.
const { fetchJson, herr, abortErr } = require('../core/http');
const { sleep } = require('../core/util');

const TENANT = 'https://login.microsoftonline.com/consumers/oauth2/v2.0';
const SCOPE = 'XboxLive.signin offline_access';

const XERR = {
  2148916233: 'Esta cuenta Microsoft no tiene perfil de Xbox. Crea uno en xbox.com y vuelve a intentarlo.',
  2148916235: 'Xbox Live no está disponible en tu país o región.',
  2148916236: 'La cuenta necesita verificación de adultos (Xbox).',
  2148916237: 'La cuenta necesita verificación de adultos (Xbox).',
  2148916238: 'Esta es una cuenta de menor de edad: debe añadirse a una Familia de Microsoft por un adulto.',
};

async function startDeviceCode(clientId, signal) {
  if (!clientId) throw herr('MS_CLIENT_ID_MISSING', 'El inicio de sesión de Microsoft aún no está configurado: falta el Client ID. El administrador debe ponerlo en manifest/manifest.base.json (auth.microsoftClientId). Guía: docs/AUTENTICACION.md');
  const r = await fetchJson(`${TENANT}/devicecode`, { method: 'POST', form: { client_id: clientId, scope: SCOPE }, signal });
  if (r.status !== 200 || !r.json) throw herr('MS_DEVICECODE', 'Microsoft rechazó el inicio de sesión: ' + ((r.json && r.json.error_description) || r.status));
  return r.json; // { user_code, device_code, verification_uri, expires_in, interval }
}

async function pollToken(clientId, dc, signal) {
  const deadline = Date.now() + dc.expires_in * 1000;
  let wait = (dc.interval || 5) * 1000;
  while (Date.now() < deadline) {
    await sleep(wait);
    if (signal && signal.aborted) throw abortErr();
    const r = await fetchJson(`${TENANT}/token`, { method: 'POST', signal, form: { grant_type: 'urn:ietf:params:oauth:grant-type:device_code', client_id: clientId, device_code: dc.device_code } });
    if (r.status === 200) return r.json;
    const e = r.json && r.json.error;
    if (e === 'authorization_pending') continue;
    if (e === 'slow_down') { wait += 5000; continue; }
    if (e === 'authorization_declined') throw herr('MS_DECLINED', 'Cancelaste el inicio de sesión.');
    if (e === 'expired_token') break;
    throw herr('MS_TOKEN', 'Error de Microsoft: ' + ((r.json && r.json.error_description) || e || r.status));
  }
  throw herr('MS_TIMEOUT', 'El código caducó. Inténtalo de nuevo.');
}

async function refreshToken(clientId, refresh, signal) {
  const r = await fetchJson(`${TENANT}/token`, { method: 'POST', signal, form: { grant_type: 'refresh_token', client_id: clientId, refresh_token: refresh, scope: SCOPE } });
  if (r.status !== 200) throw herr('MS_REFRESH', 'La sesión de Microsoft caducó. Vuelve a iniciar sesión.');
  return r.json;
}

async function toMinecraft(msAccessToken, signal) {
  const xbl = await fetchJson('https://user.auth.xboxlive.com/user/authenticate', { method: 'POST', signal, json: {
    Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: 'd=' + msAccessToken }, RelyingParty: 'http://auth.xboxlive.com', TokenType: 'JWT' } });
  if (xbl.status !== 200) throw herr('XBL', 'Fallo al autenticar con Xbox Live (' + xbl.status + ').');
  const uhs = xbl.json.DisplayClaims.xui[0].uhs;
  const xsts = await fetchJson('https://xsts.auth.xboxlive.com/xsts/authorize', { method: 'POST', signal, json: {
    Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.json.Token] }, RelyingParty: 'rp://api.minecraftservices.com/', TokenType: 'JWT' } });
  if (xsts.status !== 200) throw herr('XSTS', (xsts.json && XERR[xsts.json.XErr]) || 'Xbox rechazó la cuenta (' + xsts.status + ').');
  const mc = await fetchJson('https://api.minecraftservices.com/authentication/login_with_xbox', { method: 'POST', signal, json: { identityToken: `XBL3.0 x=${uhs};${xsts.json.Token}` } });
  if (mc.status !== 200) throw herr('MC_LOGIN', 'Minecraft Services rechazó el inicio de sesión (' + mc.status + '). Si eres el administrador: tu app de Azure debe estar aprobada por Mojang (ver docs/AUTENTICACION.md).');
  return { token: mc.json.access_token, expiresAt: Date.now() + (mc.json.expires_in - 60) * 1000 };
}

// Comprueba que la cuenta posee Minecraft Java y obtiene UUID y nombre.
async function getProfile(mcToken, signal) {
  const r = await fetchJson('https://api.minecraftservices.com/minecraft/profile', { headers: { Authorization: 'Bearer ' + mcToken }, signal });
  if (r.status === 404) throw herr('NO_GAME', 'Esta cuenta Microsoft no tiene Minecraft: Java Edition. No se puede jugar en modo premium con ella.');
  if (r.status !== 200) throw herr('MC_PROFILE', 'No se pudo obtener el perfil de Minecraft (' + r.status + ').');
  return { uuid: r.json.id.replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, '$1-$2-$3-$4-$5'), name: r.json.name };
}

async function loginWithTokens(msTokens, signal) {
  const mc = await toMinecraft(msTokens.access_token, signal);
  const profile = await getProfile(mc.token, signal);
  return { ...profile, mcToken: mc.token, mcExpires: mc.expiresAt, refreshToken: msTokens.refresh_token };
}

module.exports = { startDeviceCode, pollToken, refreshToken, loginWithTokens, toMinecraft, getProfile };
