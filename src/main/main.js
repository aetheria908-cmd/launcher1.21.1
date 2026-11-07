'use strict';
const { app, BrowserWindow, ipcMain, shell, dialog, Menu } = require('electron');
const path = require('path');
const fs = require('fs');
const paths = require('./core/paths');
const log = require('./core/logger');
const settings = require('./core/settings');
const store = require('./core/store');
const remote = require('./core/remote');
const packs = require('./core/packs');
const media = require('./core/media');
const util = require('./core/util');
const { pingServer } = require('./core/serverping');
const game = require('./game');
const accounts = require('./auth/accounts');
const ms = require('./auth/microsoft');
const discord = require('./auth/discord');

if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }

let win = null;
let msAbort = null;
let discordAbort = null;
const emit = (ch, p) => { if (win && !win.isDestroyed()) win.webContents.send(ch, p); };

function openExternal(url) {
  let u; try { u = new URL(url); } catch { throw new Error('URL no válida'); }
  if (u.protocol !== 'https:') throw new Error('Solo se pueden abrir enlaces https');
  return shell.openExternal(u.toString());
}

function friendly(e) {
  const m = (e && e.message) || String(e);
  if (['ENOTFOUND', 'ECONNREFUSED', 'EAI_AGAIN', 'ECONNRESET', 'ETIMEDOUT'].includes(e && e.code)) return 'No hay conexión a Internet o el servidor no responde.';
  if (e && e.code === 'ENOSPC') return 'No hay espacio suficiente en el disco.';
  if (e && (e.code === 'EACCES' || e.code === 'EPERM')) return 'Sin permisos para escribir en la carpeta: ' + m;
  return m;
}
function handle(ch, fn) {
  ipcMain.handle(ch, async (e, ...a) => {
    if (!win || e.sender !== win.webContents) return { ok: false, error: 'No autorizado' };
    try { return { ok: true, data: await fn(...a) }; } catch (err) {
      if (err.code !== 'ABORTED') log.error(err);
      return { ok: false, error: err.code === 'ABORTED' ? 'Operación cancelada' : friendly(err), code: err.code };
    }
  });
}

function createWindow() {
  win = new BrowserWindow({
    width: 1180, height: 720, minWidth: 1000, minHeight: 660, frame: false, backgroundColor: '#0c0a10', show: false, title: remote.brand().name,
    icon: path.join(paths.appDir(), 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true, autoplayPolicy: 'no-user-gesture-required' },
  });
  win.once('ready-to-show', () => win.show());
  // Diagnóstico: errores de la interfaz al registro; F12 o Ctrl+Mayús+I abren las herramientas de desarrollo.
  win.webContents.on('console-message', (e, level, message, line, source) => { if ((level >= 2 || (e && e.level === 'error')) ) log.warn(`[interfaz] ${message || (e && e.message)} (${source || (e && e.sourceId)}:${line || (e && e.lineNumber)})`); });
  win.webContents.on('preload-error', (_e, p, err) => log.error('Error en preload ' + p + ': ' + (err && err.stack)));
  win.webContents.on('render-process-gone', (_e, d) => log.error('La interfaz se cerró: ' + d.reason));
  win.webContents.on('before-input-event', (ev, input) => { if (input.type === 'keyDown' && (input.key === 'F12' || (input.control && input.shift && String(input.key).toLowerCase() === 'i'))) win.webContents.toggleDevTools(); });
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { try { openExternal(url); } catch { /* ignorar */ } return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) e.preventDefault(); });
  win.on('closed', () => { win = null; });
}

function registerHandlers() {
  const R = remote;
  handle('app:init', () => ({
    brand: R.brand(), server: R.server(), urls: R.urls(), settings: settings.get(), version: app.getVersion(), install: game.installState(),
    accounts: accounts.list(), discord: accounts.discord(), manifest: { status: R.getStatus(), maintenance: R.getManifest().maintenance, needsLauncherUpdate: R.getManifest().needsLauncherUpdate, launcherMessage: R.getManifest().launcher.message },
    auth: { microsoft: !!R.authConfig().microsoft.clientId, discord: !!R.authConfig().discord.clientId },
    paths: { root: paths.root(), game: paths.game() },
  }));
  handle('win:minimize', () => win.minimize());
  handle('win:maximize', () => (win.isMaximized() ? win.unmaximize() : win.maximize()));
  handle('win:close', () => win.close());
  handle('shell:open', (url) => openExternal(url));

  handle('manifest:refresh', async () => {
    const man = await game.refreshRemote();
    emit('remote:updated', { server: R.server(), urls: R.urls(), maintenance: man.maintenance, needsLauncherUpdate: man.needsLauncherUpdate, status: R.getStatus() });
    return R.getStatus();
  });
  handle('news:get', () => R.news(paths.cache()));
  handle('server:status', () => { const s = R.server(); return pingServer(s.address, s.port).then((r) => ({ ...r, address: s.address, port: s.port, required: s.minecraftVersion })); });
  handle('media:get', () => ({ ...media.current({ manifest: R.getManifest(), settings: settings.get(), paths }), audio: settings.get().audio }));

  handle('accounts:list', () => ({ accounts: accounts.list(), discord: accounts.discord() }));
  handle('accounts:select', (id) => { accounts.select(id); return accounts.list(); });
  handle('accounts:remove', (id) => { accounts.remove(id); return accounts.list(); });
  handle('auth:offline', (name) => { accounts.addOffline(String(name || '').trim()); return accounts.list(); });
  handle('auth:ms:start', async () => {
    if (msAbort) msAbort.abort();
    msAbort = new AbortController();
    const sig = msAbort.signal; const cid = R.authConfig().microsoft.clientId;
    try {
      const dc = await ms.startDeviceCode(cid, sig);
      emit('ms:code', { userCode: dc.user_code, uri: dc.verification_uri, expiresIn: dc.expires_in });
      openExternal(dc.verification_uri);
      const tok = await ms.pollToken(cid, dc, sig);
      accounts.addMicrosoft(await ms.loginWithTokens(tok, sig));
      return accounts.list();
    } finally { msAbort = null; }
  });
  handle('auth:ms:cancel', () => { if (msAbort) msAbort.abort(); });
  handle('auth:discord:login', async () => {
    if (discordAbort) discordAbort.abort();
    discordAbort = new AbortController();
    try { const p = await discord.login(R.authConfig().discord, openExternal, discordAbort.signal); accounts.setDiscord(p); return accounts.discord(); }
    finally { discordAbort = null; }
  });
  handle('auth:discord:logout', () => { if (discordAbort) discordAbort.abort(); accounts.clearDiscord(); return null; });

  handle('settings:get', () => settings.get());
  handle('settings:set', (patch) => {
    const s = settings.patch(patch);
    if (patch && patch.installDir !== undefined) paths.setRoot(s.installDir);
    return s;
  });
  handle('settings:reset', () => settings.reset());
  handle('system:memory', () => { const t = Math.floor(require('os').totalmem() / 1048576); return { totalMb: t, ...util.memoryLimits(t), auto: util.autoRam(t) }; });
  handle('dialog:folder', async () => { const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'] }); return r.canceled ? null : r.filePaths[0]; });
  handle('dialog:file', async (kind) => {
    const filters = kind === 'java' ? [{ name: 'Java', extensions: ['exe'] }] : [{ name: 'OptiFine', extensions: ['jar'] }];
    const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters });
    return r.canceled ? null : r.filePaths[0];
  });

  handle('game:play', (opts) => game.play(emit, opts));
  handle('game:update', () => game.update(emit, false));
  handle('game:repair', () => game.update(emit, true));
  handle('task:cancel', () => game.cancel());
  handle('game:clean', () => game.cleanTemp());
  handle('game:open-folder', () => shell.openPath(paths.game()));
  handle('game:open-logs', () => shell.showItemInFolder(log.file()));
  handle('game:open-screenshots', () => { const d = path.join(paths.game(), 'screenshots'); fs.mkdirSync(d, { recursive: true }); return shell.openPath(d); });

  const plist = (deep) => packs.list({ manifest: R.getManifest(), settings: settings.get(), paths, deep, ctx: game.optiCtx() });
  handle('packs:list', () => plist(false));
  handle('packs:install', async (id) => { await game.packInstall(emit, id, true); return plist(false); });
  handle('packs:uninstall', async (id) => { await game.packInstall(emit, id, false); return plist(false); });
  handle('packs:toggle', (id, on) => { game.packToggle(id, on); return plist(false); });
  handle('packs:priority', (id, d) => { game.packPriority(id, d); return plist(false); });
  handle('packs:active', () => packs.activeInOptions(paths, R.getManifest()));
  handle('packs:verify', async () => {
    const before = await plist(true);
    if (before.some((p) => p.corrupt || p.outdated)) await game.packRepair(emit);
    return { before, after: await plist(true) };
  });

  handle('optifine:info', () => game.optifineInfo());
  handle('optifine:open', () => openExternal(require('./core/optifine').PAGE));
  handle('optifine:install', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Selecciona el OptiFine .jar descargado', properties: ['openFile'], filters: [{ name: 'OptiFine', extensions: ['jar'] }] });
    if (r.canceled) return null;
    return game.optifineInstall(emit, r.filePaths[0]);
  });
}

function initUpdater() {
  if (!app.isPackaged) return;
  try {
    const { autoUpdater } = require('electron-updater');
    autoUpdater.logger = null;
    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.on('checking-for-update', () => emit('update:state', { state: 'checking' }));
    autoUpdater.on('update-available', (i) => emit('update:state', { state: 'downloading', version: i.version, percent: 0 }));
    autoUpdater.on('download-progress', (p) => emit('update:state', { state: 'downloading', percent: Math.round(p.percent) }));
    autoUpdater.on('update-not-available', () => emit('update:state', { state: 'none' }));
    autoUpdater.on('update-downloaded', (i) => emit('update:state', { state: 'ready', version: i.version }));
    autoUpdater.on('error', (e) => { log.warn('Actualizador: ' + e.message); emit('update:state', { state: 'error' }); });
    handle('update:install', () => autoUpdater.quitAndInstall());
    autoUpdater.checkForUpdates().catch((e) => log.warn('Actualizador: ' + e.message));
  } catch (e) { log.warn('No se pudo iniciar el actualizador: ' + e.message); }
}

async function backgroundBoot() {
  try {
    const man = await game.refreshRemote();
    emit('remote:updated', { server: remote.server(), urls: remote.urls(), maintenance: man.maintenance, needsLauncherUpdate: man.needsLauncherUpdate, status: remote.getStatus() });
    // Primera vez y siguientes: con una cuenta elegida se prepara/actualiza todo automáticamente.
    if (settings.get().selectedAccount && !man.maintenance.enabled) await game.update(emit, false);
    emit('media:updated', null);
  } catch (e) { if (e.code !== 'ABORTED') { log.error(e); emit('toast', { kind: 'error', text: friendly(e) }); } }
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.on('web-contents-created', (_e, c) => c.on('will-attach-webview', (ev) => ev.preventDefault()));
app.on('window-all-closed', () => app.quit());

app.whenReady().then(() => {
  log.init(paths.logs());
  settings.load(paths.userData());
  paths.setRoot(settings.get().installDir);
  store.init(paths.userData());
  remote.loadBundled(paths.appDir());
  game.setVersion(app.getVersion());
  game.cleanup(); // por si el launcher se cerró mal y quedó algún pack privado descifrado
  Menu.setApplicationMenu(null);
  registerHandlers();
  createWindow();
  win.webContents.once('did-finish-load', () => { initUpdater(); backgroundBoot(); });
  log.info('Launcher ' + app.getVersion() + ' iniciado');
}).catch((e) => { log.error(e); dialog.showErrorBox('Error al iniciar el launcher', String(e && e.message)); app.quit(); });
