'use strict';
// Discord OAuth2 con PKCE y redirección local (127.0.0.1). El ejecutable NO contiene ningún secreto:
// se usa "Public Client" + PKCE, o un endpoint propio de intercambio (tokenExchangeUrl) que guarde el secreto en el servidor.
const http = require('http');
const crypto = require('crypto');
const { fetchJson, herr, abortErr } = require('../core/http');

const b64u = (b) => b.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function waitForCode(port, state, signal) {
  return new Promise((resolve, reject) => {
    const srv = http.createServer((req, res) => {
      const u = new URL(req.url, `http://127.0.0.1:${port}`);
      if (u.pathname !== '/callback') { res.writeHead(404).end(); return; }
      const ok = u.searchParams.get('state') === state && u.searchParams.get('code');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`<html><body style="background:#14101c;color:#e9e4d8;font-family:Georgia,serif;text-align:center;padding-top:20vh"><h1>${ok ? 'Cuenta vinculada' : 'No se pudo vincular'}</h1><p>Ya puedes cerrar esta ventana y volver al launcher.</p></body></html>`);
      clearTimeout(timer); srv.close();
      if (ok) resolve(u.searchParams.get('code')); else reject(herr('DISCORD_DENIED', u.searchParams.get('error_description') || 'Se canceló la vinculación con Discord.'));
    });
    const timer = setTimeout(() => { srv.close(); reject(herr('DISCORD_TIMEOUT', 'Tiempo agotado esperando a Discord.')); }, 5 * 60 * 1000);
    srv.on('error', (e) => { clearTimeout(timer); reject(herr('DISCORD_PORT', 'No se pudo abrir el puerto local ' + port + ': ' + e.message)); });
    if (signal) signal.addEventListener('abort', () => { clearTimeout(timer); srv.close(); reject(abortErr()); }, { once: true });
    srv.listen(port, '127.0.0.1');
  });
}

async function login(cfg, openBrowser, signal) {
  if (!cfg.clientId) throw herr('DISCORD_CLIENT_ID_MISSING', 'Discord aún no está configurado: falta el Client ID. El administrador debe ponerlo en manifest/manifest.base.json (auth.discordClientId). Guía: docs/AUTENTICACION.md');
  const port = cfg.redirectPort || 53682;
  const redirect = `http://127.0.0.1:${port}/callback`;
  const verifier = b64u(crypto.randomBytes(48));
  const challenge = b64u(crypto.createHash('sha256').update(verifier).digest());
  const state = b64u(crypto.randomBytes(16));
  const scope = cfg.guildId ? 'identify guilds.members.read' : 'identify';
  const url = 'https://discord.com/oauth2/authorize?' + new URLSearchParams({ client_id: cfg.clientId, response_type: 'code', redirect_uri: redirect, scope, state, code_challenge: challenge, code_challenge_method: 'S256' });
  const waiting = waitForCode(port, state, signal);
  await openBrowser(url);
  const code = await waiting;
  const tok = cfg.tokenExchangeUrl
    ? await fetchJson(cfg.tokenExchangeUrl, { method: 'POST', json: { code, code_verifier: verifier, redirect_uri: redirect }, signal, strict: true })
    : await fetchJson('https://discord.com/api/oauth2/token', { method: 'POST', signal, form: { client_id: cfg.clientId, grant_type: 'authorization_code', code, redirect_uri: redirect, code_verifier: verifier } });
  if (tok.status !== 200 || !tok.json || !tok.json.access_token) throw herr('DISCORD_TOKEN', 'Discord rechazó el intercambio de código: ' + ((tok.json && (tok.json.error_description || tok.json.error)) || tok.status) + '. Comprueba que la app de Discord tiene activado "Public Client" (docs/AUTENTICACION.md).');
  return profile(cfg, tok.json, signal);
}

async function profile(cfg, tok, signal) {
  const h = { Authorization: 'Bearer ' + tok.access_token };
  const me = await fetchJson('https://discord.com/api/v10/users/@me', { headers: h, signal });
  if (me.status !== 200) throw herr('DISCORD_ME', 'No se pudo leer el perfil de Discord (' + me.status + ').');
  let member = null;
  if (cfg.guildId) {
    const m = await fetchJson(`https://discord.com/api/v10/users/@me/guilds/${encodeURIComponent(cfg.guildId)}/member`, { headers: h, signal });
    member = m.status === 200;
  }
  const u = me.json;
  return {
    id: u.id, username: u.global_name || u.username,
    avatar: u.avatar ? `https://cdn.discordapp.com/avatars/${u.id}/${u.avatar}.png?size=128` : '',
    member, refreshToken: tok.refresh_token || '', linkedAt: new Date().toISOString(),
  };
}
module.exports = { login };
