'use strict';
// Interfaz del launcher. Todo el texto remoto (noticias, packs) se inserta con textContent: nunca como HTML.
const api = window.api;
const $ = (s, r = document) => r.querySelector(s);
const S = { init: null, settings: null, task: { busy: false, kind: '' }, game: 'idle', news: [], srv: null, media: null, mem: null, upd: null };

function h(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) { if (kid == null || kid === false) continue; e.append(kid.nodeType ? kid : document.createTextNode(String(kid))); }
  return e;
}
const fmtB = (n) => { const u = ['B', 'KB', 'MB', 'GB']; let i = 0; while (n >= 1024 && i < 3) { n /= 1024; i++; } return n.toFixed(i ? 1 : 0) + ' ' + u[i]; };
const fmtT = (s) => (s == null ? '' : s >= 3600 ? Math.round(s / 3600) + ' h' : s >= 60 ? Math.round(s / 60) + ' min' : Math.ceil(s) + ' s');
const fmtD = (d) => { const t = Date.parse(d); return isNaN(t) ? (d || '') : new Date(t).toLocaleDateString('es-ES', { day: 'numeric', month: 'long', year: 'numeric' }); };

function toast(text, kind = 'info', ms = 6000) {
  const t = h('div', { class: 'toast ' + kind, text });
  $('#toasts').append(t);
  setTimeout(() => t.remove(), ms);
}
async function call(ch, ...a) {
  try { return await api.invoke(ch, ...a); } catch (e) { if (!/cancelada/i.test(e.message)) toast(e.message, 'error', 9000); throw e; }
}
const quiet = (p) => p.catch(() => {});

/* ---------- Modales ---------- */
function modal(title, body, { small = false, onClose, tabs } = {}) {
  closeModal();
  const close = () => { closeModal(); onClose && onClose(); };
  const m = h('div', { class: 'modal' + (small ? ' sm' : '') }, h('div', { class: 'mhead' }, h('h3', { text: title }), h('button', { onclick: close, 'aria-label': 'Cerrar', text: '✕' })), tabs || null, body);
  const o = h('div', { class: 'overlay', onmousedown: (e) => { if (e.target === o) close(); } }, m);
  $('#modalRoot').append(o);
  return { close, el: m };
}
function closeModal() { $('#modalRoot').replaceChildren(); }

/* ---------- Arranque ---------- */
async function boot() {
  wireStatic();
  const i = await call('app:init');
  S.init = i; S.settings = i.settings;
  applyBrand(); applySettingsFx(); renderAccount(); renderServer(i.server); renderBanners(); renderPlay();
  $('#verTag').textContent = 'v' + i.version;
  api.on('task:progress', onProgress);
  api.on('task:state', (s) => { S.task = s; renderPlay(); });
  api.on('game:state', (s) => { S.game = s.state; renderPlay(); if (s.state === 'running' && S.settings.closeOnPlay) api.invoke('win:minimize'); });
  api.on('game:crash', onCrash);
  api.on('toast', (t) => toast(t.text, t.kind));
  api.on('remote:updated', async (r) => { S.init.server = r.server; S.init.urls = r.urls; S.init.manifest = { ...S.init.manifest, maintenance: r.maintenance, needsLauncherUpdate: r.needsLauncherUpdate, status: r.status }; renderServer(r.server); renderBanners(); loadNews(); loadMedia(); });
  api.on('media:updated', loadMedia);
  api.on('update:state', onUpdate);
  loadNews(); loadMedia(); refreshStatus();
  setInterval(refreshStatus, 30000);
}

function wireStatic() {
  for (const b of document.querySelectorAll('[data-open]')) b.addEventListener('click', () => openUrl(S.init && S.init.urls[b.dataset.open]));
  $('#wMin').onclick = () => api.invoke('win:minimize'); $('#wMax').onclick = () => api.invoke('win:maximize'); $('#wClose').onclick = () => api.invoke('win:close');
  for (const id of ['#tbNews', '#navNews', '#allNews']) $(id).onclick = () => showNewsList();
  for (const id of ['#tbCredits', '#navInfo']) $(id).onclick = showInfo;
  $('#navSettings').onclick = () => showSettings();
  $('#accBtn').onclick = showAccounts; $('#dcBtn').onclick = showAccounts;
  $('#playBtn').onclick = onPlay;
  $('#cancelBtn').onclick = () => { quiet(api.invoke('task:cancel')); };
  $('#updChip').onclick = () => { if (S.upd && S.upd.state === 'ready') api.invoke('update:install'); };
  $('#mPlay').onclick = toggleMusic; $('#mMute').onclick = toggleMute;
  $('#mVol').oninput = (e) => { const v = e.target.value / 100; $('#music').volume = v; saveAudio({ volume: v }); };
}
function openUrl(u) { if (u) quiet(api.invoke('shell:open', u)); else toast('Esta dirección aún no está configurada.', 'warn'); }

function applyBrand() {
  const b = S.init.brand;
  $('#brandName').textContent = b.name; $('#brandTag').textContent = b.tagline; document.title = b.name + ' Launcher';
}
function applySettingsFx() {
  document.body.classList.toggle('no-fx', !S.settings.media.effects);
  const v = S.settings.audio.volume; $('#mVol').value = Math.round(v * 100); $('#music').volume = v; $('#music').muted = S.settings.audio.muted;
  $('#mMute').textContent = S.settings.audio.muted ? '🔇' : '🔊';
}
async function saveSettings(patch) { S.settings = await call('settings:set', patch); return S.settings; }
const saveAudio = (a) => quiet(saveSettings({ audio: a }));

/* ---------- Cuentas ---------- */
function renderAccount() {
  const sel = S.init.accounts.find((a) => a.selected);
  $('#accName').textContent = sel ? sel.name : 'Sin cuenta';
  $('#accType').textContent = sel ? (sel.type === 'microsoft' ? 'Microsoft · Premium' : 'Perfil offline') : 'Pulsa para elegir';
  const av = $('#accAvatar');
  if (sel && sel.type === 'microsoft') { av.src = 'https://mc-heads.net/avatar/' + sel.uuid + '/64'; av.onerror = () => { av.removeAttribute('src'); }; }
  else av.removeAttribute('src');
  const d = S.init.discord;
  $('#dcName').textContent = d ? d.username : 'Vincular Discord';
  $('#dcSub').textContent = d ? (d.member === true ? 'Miembro de la comunidad' : d.member === false ? 'Cuenta vinculada · no es miembro' : 'Cuenta vinculada') : 'Opcional';
  const da = $('#dcAvatar'); da.classList.toggle('hidden', !(d && d.avatar)); if (d && d.avatar) da.src = d.avatar;
}

function showAccounts() {
  const body = h('div', { class: 'mbody' });
  const draw = () => {
    body.replaceChildren();
    const accs = S.init.accounts, d = S.init.discord;
    body.append(h('h4', { class: 'grp', text: 'Discord (comunidad)' }));
    if (d) body.append(h('div', { class: 'item' }, d.avatar ? h('img', { class: 'avatar sm', src: d.avatar, alt: '' }) : null, h('div', { class: 'grow' }, h('b', { text: d.username }), h('small', { text: d.member === true ? 'Miembro verificado de la comunidad' : d.member === false ? 'No eres miembro del servidor de Discord' : 'Cuenta vinculada' })), h('button', { class: 'btn', text: 'Cerrar sesión', onclick: async () => { await call('auth:discord:logout'); S.init.discord = null; renderAccount(); draw(); } })));
    else body.append(h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: 'Sin vincular' }), h('small', { text: S.init.auth.discord ? 'Identifica tu cuenta de la comunidad. No sustituye a tu cuenta de Minecraft.' : 'El administrador aún no ha configurado Discord (docs/AUTENTICACION.md).' })),
      h('button', { class: 'btn primary', text: 'Iniciar sesión con Discord', disabled: !S.init.auth.discord, onclick: async (e) => { e.target.disabled = true; try { S.init.discord = await call('auth:discord:login'); renderAccount(); toast('Discord vinculado', 'ok'); } catch { /* ya notificado */ } draw(); } })));

    body.append(h('h4', { class: 'grp', text: 'Minecraft Premium (Microsoft)' }));
    for (const a of accs.filter((x) => x.type === 'microsoft')) body.append(accItem(a, draw));
    body.append(h('div', { class: 'item' }, h('div', { class: 'grow' }, h('small', { text: S.init.auth.microsoft ? 'Inicio de sesión oficial de Microsoft. Nunca ves ni guardamos tu contraseña.' : 'El administrador aún no ha configurado el Client ID de Microsoft (docs/AUTENTICACION.md).' })),
      h('button', { class: 'btn primary', text: 'Iniciar sesión con Microsoft', disabled: !S.init.auth.microsoft, onclick: () => startMicrosoft(draw) })));

    body.append(h('h4', { class: 'grp', text: 'Sin cuenta Premium (perfil offline)' }));
    for (const a of accs.filter((x) => x.type === 'offline')) body.append(accItem(a, draw));
    const inp = h('input', { class: 'in', type: 'text', placeholder: 'Nombre (3–16: letras, números, _)', maxlength: 16 });
    body.append(h('div', { class: 'item' }, inp, h('div', { class: 'grow' }), h('button', { class: 'btn', text: 'Crear perfil', onclick: async () => { try { S.init.accounts = await call('auth:offline', inp.value); renderAccount(); renderPlay(); draw(); afterAccount(); } catch { /* notificado */ } } })));
    body.append(h('div', { class: 'note', text: 'Un perfil offline no es una cuenta premium. Solo funciona en servidores configurados expresamente en modo offline (online-mode=false). Los servidores con autenticación oficial lo rechazarán.' }));
  };
  draw();
  modal('Cuentas', body);
}
function accItem(a, draw) {
  return h('div', { class: 'item' + (a.selected ? ' sel' : '') },
    h('div', { class: 'grow' }, h('b', { text: a.name }), h('small', { text: a.type === 'microsoft' ? 'Premium · ' + a.uuid.slice(0, 8) : 'Offline' })),
    a.selected ? h('span', { class: 'badge ok', text: 'EN USO' }) : h('button', { class: 'btn', text: 'Usar', onclick: async () => { S.init.accounts = await call('accounts:select', a.id); renderAccount(); renderPlay(); draw(); } }),
    h('button', { class: 'btn danger', text: 'Quitar', onclick: async () => { S.init.accounts = await call('accounts:remove', a.id); renderAccount(); renderPlay(); draw(); } }));
}
async function startMicrosoft(draw) {
  const box = h('div', { class: 'mbody' }, h('p', { text: 'Abrimos la página oficial de Microsoft en tu navegador. Introduce este código allí para autorizar:' }), h('div', { class: 'code', text: '…' }), h('p', { class: 'muted', text: 'Esperando a que inicies sesión…' }), h('button', { class: 'btn danger', text: 'Cancelar', onclick: () => { quiet(api.invoke('auth:ms:cancel')); closeModal(); showAccounts(); } }));
  const off = api.on('ms:code', (c) => { $('.code', box).textContent = c.userCode; });
  modal('Microsoft', box, { small: true, onClose: () => quiet(api.invoke('auth:ms:cancel')) });
  try { S.init.accounts = await call('auth:ms:start'); renderAccount(); renderPlay(); toast('Cuenta Microsoft vinculada', 'ok'); afterAccount(); } catch { /* notificado */ }
  off(); closeModal(); showAccounts();
}
function afterAccount() { if (S.init.accounts.length === 1 && !S.task.busy) quiet(api.invoke('game:update')); }

/* ---------- Servidor, banners, jugar ---------- */
function renderServer(s) {
  $('#srvAddr').textContent = s.address + (s.port !== 25565 ? ':' + s.port : '');
  $('#srvVer').textContent = s.minecraftVersion;
  refreshStatus();
}
async function refreshStatus() {
  try {
    const r = await api.invoke('server:status'); S.srv = r;
    $('#srvDot').className = 'dot ' + (r.online ? 'on' : 'off');
    $('#srvState').textContent = r.online ? 'En línea' : 'Sin respuesta';
    $('#srvPing').textContent = r.online && r.latency != null ? r.latency + ' ms' : '';
    $('#srvPlayers').textContent = r.online && r.players ? r.players.online + ' / ' + r.players.max : '—';
  } catch { /* sin datos: no se inventa nada */ }
}
function renderBanners() {
  const b = $('#banners'); b.replaceChildren();
  const m = S.init.manifest;
  if (m.maintenance && m.maintenance.enabled) b.append(h('div', { class: 'banner warn', text: '🛠 Mantenimiento: ' + (m.maintenance.message || 'el servidor no está disponible temporalmente.') }));
  if (m.needsLauncherUpdate) b.append(h('div', { class: 'banner bad', text: '⬆ ' + (m.launcherMessage || 'Hay una actualización obligatoria del launcher.') }));
  if (m.status && m.status.source !== 'remote') b.append(h('div', { class: 'banner info', text: m.status.source === 'cache' ? 'Sin conexión con el repositorio: se usa la última configuración guardada.' : 'No se pudo leer la configuración remota. Comprueba tu conexión.' }));
}
function renderPlay() {
  const p = $('#playBtn'), c = $('#cancelBtn');
  const sel = S.init.accounts.find((a) => a.selected);
  const busy = S.task.busy, run = S.game === 'running';
  p.disabled = busy || run;
  p.textContent = run ? 'EN EJECUCIÓN' : busy ? (S.task.kind === 'play' ? 'PREPARANDO…' : 'ACTUALIZANDO…') : sel ? 'JUGAR / UNIRSE' : 'ELEGIR CUENTA';
  c.classList.toggle('hidden', !busy);
  $('#prog').classList.toggle('hidden', !busy);
}
async function onPlay() {
  if (!S.init.accounts.some((a) => a.selected)) return showAccounts();
  try { await api.invoke('game:play'); } catch (e) { if (!/cancelada/i.test(e.message)) toast(e.message, 'error', 12000); }
}
function onProgress(p) {
  if (!p.label) return;
  $('#progLabel').textContent = p.label;
  const f = $('#progFill'); f.classList.toggle('ind', p.percent == null); if (p.percent != null) f.style.width = p.percent.toFixed(1) + '%';
  $('#progInfo').textContent = p.total ? `${fmtB(p.done)} / ${fmtB(p.total)} · ${p.percent.toFixed(0)}%${p.speed ? ' · ' + fmtB(p.speed) + '/s' : ''}${p.eta ? ' · ' + fmtT(p.eta) : ''}` : '';
}
function onCrash(c) {
  modal('Minecraft se cerró inesperadamente', h('div', { class: 'mbody' }, h('p', { text: 'Código de salida: ' + c.code + '. Últimas líneas del registro:' }), h('pre', { class: 'log', text: c.tail.join('\n') }), h('div', { class: 'note', text: 'Si se repite: Ajustes → General → Reparar instalación, o revisa la memoria asignada.' }), h('div', { class: 'item' }, h('div', { class: 'grow' }), h('button', { class: 'btn', text: 'Abrir registros', onclick: () => api.invoke('game:open-logs') }))));
}
function onUpdate(u) {
  S.upd = u; const c = $('#updChip');
  c.classList.toggle('hidden', !['downloading', 'ready'].includes(u.state));
  c.textContent = u.state === 'ready' ? '⬆ Reiniciar para actualizar el launcher (v' + u.version + ')' : '⬇ Actualizando launcher ' + (u.percent || 0) + '%';
}

/* ---------- Noticias ---------- */
async function loadNews() {
  try {
    const r = await api.invoke('news:get'); S.news = r.items.sort((a, b) => (b.pinned - a.pinned) || String(b.date).localeCompare(a.date));
  } catch { S.news = []; }
  const hero = $('#hero'), grid = $('#newsGrid'); grid.replaceChildren();
  $('#newsEmpty').classList.toggle('hidden', S.news.length > 0); $('#newsEmpty').textContent = 'No hay noticias disponibles todavía.';
  hero.classList.toggle('hidden', !S.news.length); $('#latestHead').classList.toggle('hidden', S.news.length < 2);
  if (!S.news.length) return;
  const f = S.news[0];
  hero.style.backgroundImage = f.image ? `url("${f.image.replace(/"/g, '%22')}")` : '';
  hero.replaceChildren(h('div', { class: 'txt' }, h('span', { class: 'tag', text: f.pinned ? 'Destacado' : f.type }), h('h3', { text: f.title }), h('p', { text: f.summary }), h('button', { class: 'btn', text: 'Leer más' })));
  hero.onclick = () => showNews(f);
  for (const n of S.news.slice(1, 7)) grid.append(h('article', { class: 'ncard', onclick: () => showNews(n) }, h('div', { class: 'img', style: n.image ? `background-image:url("${n.image.replace(/"/g, '%22')}")` : '' }), h('div', { class: 'b' }, h('h4', { text: n.title }), h('p', { text: n.summary }), h('small', { text: fmtD(n.date) }))));
}
function showNews(n) {
  const b = h('div', { class: 'mbody news-body' }, n.image ? h('img', { class: 'news-img', src: n.image, alt: '' }) : null, h('span', { class: 'tag', text: n.type }), h('small', { class: 'muted', text: '  ' + fmtD(n.date) }), h('p', { text: '' }),
    ...(n.body || n.summary).split(/\n{2,}/).map((t) => h('p', { text: t })), n.url ? h('button', { class: 'btn', text: 'Abrir enlace', onclick: () => openUrl(n.url) }) : null);
  modal(n.title, b);
}
function showNewsList() {
  const b = h('div', { class: 'mbody list' }, S.news.length ? S.news.map((n) => h('div', { class: 'item', onclick: () => showNews(n), style: 'cursor:pointer' }, h('div', { class: 'grow' }, h('b', { text: n.title }), h('small', { text: fmtD(n.date) + ' · ' + n.type })), h('span', { text: '›' }))) : h('p', { class: 'muted', text: 'No hay noticias.' }));
  modal('Noticias y novedades', b);
}
function showInfo() {
  const s = S.init.server, u = S.init.urls;
  modal('Información', h('div', { class: 'mbody' },
    h('h4', { class: 'grp', text: 'Servidor' }),
    kv('Nombre', s.name), kv('Dirección', s.address + ':' + s.port), kv('Versión de Minecraft', s.minecraftVersion), kv('Estado', S.srv ? (S.srv.online ? 'En línea' + (S.srv.latency != null ? ' · ' + S.srv.latency + ' ms' : '') : 'Sin respuesta') : '—'),
    S.srv && S.srv.online ? kv('Mensaje', S.srv.motd || '—') : null,
    h('div', { class: 'item' }, h('div', { class: 'grow' }), h('button', { class: 'btn', text: 'Discord', onclick: () => openUrl(u.discord) }), h('button', { class: 'btn', text: 'Sitio web', onclick: () => openUrl(u.website) })),
    h('h4', { class: 'grp', text: 'Créditos' }),
    h('p', { text: 'Launcher de la comunidad ' + S.init.brand.name + ' · v' + S.init.version }),
    h('div', { class: 'note', text: 'No afiliado a Mojang ni a Microsoft. Minecraft es una marca de Mojang Studios. El juego se descarga desde los servidores oficiales de Mojang; este launcher no incluye archivos del juego.' })));
}
const kv = (k, v) => h('div', { class: 'field' }, h('label', { text: k }), h('span', { text: String(v) }));

/* ---------- Multimedia ---------- */
async function loadMedia() {
  try { S.media = await api.invoke('media:get'); } catch { return; }
  const m = S.media;
  $('#bgimg').style.backgroundImage = m.background ? `url("${m.background}")` : '';
  const v = $('#bgvideo');
  if (m.video && S.settings.media.video) { if (v.getAttribute('src') !== m.video) v.src = m.video; v.play().catch(() => {}); v.style.display = ''; }
  else { v.pause(); v.removeAttribute('src'); v.style.display = 'none'; }
  const a = $('#music');
  $('#musicCtl').classList.toggle('hidden', !m.tracks.length);
  if (m.tracks.length && !a.dataset.ready) {
    a.dataset.ready = '1'; let i = 0; a.src = m.tracks[0].url;
    a.onended = () => { i = (i + 1) % S.media.tracks.length; a.src = S.media.tracks[i].url; a.play().catch(() => {}); };
    if (S.settings.audio.music) a.play().catch(() => {});
  }
  syncMusicBtn();
}
function syncMusicBtn() { $('#mPlay').textContent = $('#music').paused ? '▶' : '⏸'; }
function toggleMusic() { const a = $('#music'); if (a.paused) a.play().catch(() => {}); else a.pause(); saveAudio({ music: !a.paused }); setTimeout(syncMusicBtn, 50); }
function toggleMute() { const a = $('#music'); a.muted = !a.muted; $('#mMute').textContent = a.muted ? '🔇' : '🔊'; saveAudio({ muted: a.muted }); }

/* ---------- Ajustes ---------- */
const TABS = [['perf', 'Rendimiento'], ['gfx', 'Gráficos'], ['packs', 'Texture Packs'], ['media', 'Multimedia'], ['opti', 'OptiFine'], ['gen', 'General']];
async function showSettings(tab = 'perf') {
  S.mem = S.mem || await call('system:memory');
  S.settings = await call('settings:get');
  const body = h('div', { class: 'mbody' });
  const tabs = h('div', { class: 'tabs' });
  const go = async (t) => { for (const b of tabs.children) b.classList.toggle('on', b.dataset.t === t); body.replaceChildren(); await TAB[t](body); };
  for (const [id, label] of TABS) tabs.append(h('button', { 'data-t': id, text: label, onclick: () => go(id) }));
  modal('Ajustes', body, { tabs });
  go(tab);
}
// Controles reutilizables (guardan al instante)
function ctl(label, hint, input) { return h('div', { class: 'field' }, h('label', {}, label, hint ? h('small', { text: hint }) : null), input); }
function check(path, get, set) { return h('input', { type: 'checkbox', checked: get() ? true : null, onchange: (e) => quiet(set(e.target.checked)) }); }
function num(get, set, min, max, step = 1) { return h('input', { type: 'number', value: get(), min, max, step, onchange: (e) => { let v = Math.min(max, Math.max(min, +e.target.value || min)); e.target.value = v; quiet(set(v)); } }); }
function sel(get, opts, set) { const s = h('select', { onchange: (e) => quiet(set(e.target.value)) }, opts.map(([v, t]) => h('option', { value: v, text: t }))); s.value = get(); return s; }
function range(get, min, max, step, set, fmt) {
  const val = h('span', { class: 'val', text: fmt(get()) });
  const r = h('input', { type: 'range', min, max, step, value: get(), oninput: (e) => { val.textContent = fmt(+e.target.value); }, onchange: (e) => quiet(set(+e.target.value)) });
  return h('div', { style: 'display:flex;align-items:center;gap:8px' }, r, val);
}
const gset = (k) => (v) => saveSettings({ graphics: { [k]: v } });

const TAB = {
  async perf(b) {
    const s = S.settings, m = S.mem;
    b.append(h('div', { class: 'note', text: `Tu equipo tiene ${(m.totalMb / 1024).toFixed(1)} GB de RAM. Máximo recomendado para Minecraft: ${(m.maxAllowed / 1024).toFixed(1)} GB.` }),
      ctl('Detectar memoria automáticamente', `Usará ${(m.auto.max / 1024).toFixed(1)} GB`, check('', () => s.ram.auto, (v) => { s.ram.auto = v; return saveSettings({ ram: { auto: v } }); })),
      ctl('RAM mínima', null, range(() => s.ram.min, 512, m.maxAllowed, 256, (v) => saveSettings({ ram: { min: v, max: Math.max(v, s.ram.max) } }), (v) => (v / 1024).toFixed(2) + ' GB')),
      ctl('RAM máxima', null, range(() => Math.min(s.ram.max, m.maxAllowed), 1024, m.maxAllowed, 256, (v) => saveSettings({ ram: { max: v, min: Math.min(v, s.ram.min) } }), (v) => (v / 1024).toFixed(2) + ' GB')),
      h('h4', { class: 'grp', text: 'Java y versión' }),
      ctl('Java', 'Recomendado: el runtime oficial de Mojang (se instala solo)', sel(() => s.javaMode, [['managed', 'Automático (oficial de Mojang)'], ['custom', 'Mi propia instalación']], (v) => saveSettings({ javaMode: v }))),
      ctl('Ruta de Java personalizada', 'Solo con "Mi propia instalación" (Java 21+)', h('div', { style: 'display:flex;gap:6px' }, h('input', { type: 'text', class: 'in', value: s.javaPath, readonly: true }), h('button', { class: 'btn', text: 'Elegir…', onclick: async (e) => { const f = await call('dialog:file', 'java'); if (f) { await saveSettings({ javaPath: f }); e.target.previousSibling.value = f; } } }))),
      ctl('Versión del juego', 'Obligatoria del servidor: ' + S.init.server.minecraftVersion, sel(() => s.profile, [['vanilla', 'Minecraft ' + S.init.server.minecraftVersion + ' (normal)'], ['optifine', 'Con OptiFine']], (v) => saveSettings({ profile: v }))),
      ctl('Argumentos JVM adicionales', 'Avanzado. Se bloquean agentes y comandos por seguridad.', h('input', { type: 'text', class: 'in', style: 'min-width:260px', value: s.jvmArgs, placeholder: '-XX:+UseZGC', onchange: (e) => quiet(saveSettings({ jvmArgs: e.target.value })) })),
      h('h4', { class: 'grp', text: 'Ventana y conexión' }),
      ctl('Pantalla completa', null, check('', () => s.fullscreen, (v) => saveSettings({ fullscreen: v }))),
      ctl('Resolución de ventana', '0 = predeterminada', h('div', { style: 'display:flex;gap:6px;align-items:center' }, num(() => s.width, (v) => saveSettings({ width: v }), 0, 7680), '×', num(() => s.height, (v) => saveSettings({ height: v }), 0, 4320))),
      ctl('Conectar al servidor al iniciar', null, check('', () => s.joinServer, (v) => saveSettings({ joinServer: v }))),
      ctl('Minimizar el launcher al jugar', null, check('', () => s.closeOnPlay, (v) => saveSettings({ closeOnPlay: v }))));
  },
  async gfx(b) {
    const s = S.settings, g = s.graphics;
    b.append(h('div', { class: 'note', text: 'Se aplican al options.txt de Minecraft al pulsar JUGAR, solo si activas la primera casilla. Lo que no aparezca aquí se ajusta dentro del juego (Opciones).' }),
      ctl('Aplicar estos ajustes al iniciar', 'Si está desactivado, se respetan tus opciones del juego', check('', () => g.apply, gset('apply'))),
      ctl('Distancia de renderizado', null, range(() => g.renderDistance, 2, 32, 1, gset('renderDistance'), (v) => v + ' chunks')),
      ctl('Distancia de simulación', null, range(() => g.simulationDistance, 5, 32, 1, gset('simulationDistance'), (v) => v + ' chunks')),
      ctl('Límite de FPS', '260 = ilimitado', range(() => g.maxFps, 10, 260, 10, gset('maxFps'), (v) => (v >= 260 ? '∞' : v))),
      ctl('VSync', null, check('', () => g.vsync, gset('vsync'))),
      ctl('Calidad gráfica', null, sel(() => g.quality, [['fast', 'Rápida'], ['fancy', 'Detallada'], ['fabulous', 'Fabulosa']], gset('quality'))),
      ctl('Partículas', null, sel(() => g.particles, [['all', 'Todas'], ['decreased', 'Reducidas'], ['minimal', 'Mínimas']], gset('particles'))),
      ctl('Iluminación suave', null, check('', () => g.smoothLighting, gset('smoothLighting'))),
      ctl('Nubes', null, sel(() => g.clouds, [['true', 'Detalladas'], ['fast', 'Rápidas'], ['false', 'Desactivadas']], gset('clouds'))),
      ctl('Mipmaps', null, range(() => g.mipmaps, 0, 4, 1, gset('mipmaps'), (v) => (v === 0 ? 'No' : v))),
      ctl('Idioma del juego', null, sel(() => s.lang, [['es_es', 'Español (España)'], ['es_mx', 'Español (México)'], ['en_us', 'English (US)']], (v) => saveSettings({ lang: v }))),
      h('div', { class: 'note', text: 'OptiFine añade más opciones de vídeo dentro del juego cuando está instalado.' }));
  },
  async packs(b) {
    const draw = (list) => {
      b.replaceChildren(h('div', { class: 'note', text: 'Los packs obligatorios se descargan y actualizan solos y se activan automáticamente. Los opcionales los eliges tú.' }),
        h('div', { class: 'item' }, h('div', { class: 'grow' }, h('small', { text: list.length + ' packs en el servidor' })), h('button', { class: 'btn', text: 'Verificar integridad', onclick: async (e) => { e.target.disabled = true; try { const r = await call('packs:verify'); draw(r.after); toast(r.before.some((p) => p.corrupt || p.outdated) ? 'Se repararon o actualizaron archivos.' : 'Todos los packs están íntegros.', 'ok'); } catch { /* notificado */ } e.target.disabled = false; } })),
        h('div', { class: 'list' }, list.length ? list.map((p) => packItem(p, draw)) : h('p', { class: 'muted', text: 'El servidor aún no tiene Texture Packs publicados.' })));
    };
    draw(await call('packs:list'));
  },
  async media(b) {
    const s = S.settings;
    b.append(ctl('Música ambiental', null, check('', () => s.audio.music, async (v) => { await saveSettings({ audio: { music: v } }); const a = $('#music'); if (v) a.play().catch(() => {}); else a.pause(); syncMusicBtn(); })),
      ctl('Volumen de la música', null, range(() => s.audio.volume, 0, 1, 0.05, async (v) => { await saveSettings({ audio: { volume: v } }); $('#music').volume = v; $('#mVol').value = v * 100; }, (v) => Math.round(v * 100) + '%')),
      ctl('Silenciar', null, check('', () => s.audio.muted, async (v) => { await saveSettings({ audio: { muted: v } }); applySettingsFx(); })),
      ctl('Vídeo de fondo', 'Si no hay vídeo publicado se usa la imagen de fondo', check('', () => s.media.video, async (v) => { await saveSettings({ media: { video: v } }); loadMedia(); })),
      ctl('Efectos visuales y animaciones', 'Desactívalos para ahorrar recursos', check('', () => s.media.effects, async (v) => { await saveSettings({ media: { effects: v } }); applySettingsFx(); })),
      h('div', { class: 'note', text: 'La música, los vídeos y las imágenes se actualizan solos desde el repositorio del servidor.' }));
  },
  async opti(b) {
    b.append(h('p', { class: 'muted', text: 'Consultando optifine.net…' }));
    const i = await call('optifine:info'); b.replaceChildren();
    const c = i.check;
    b.append(h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: 'OptiFine para Minecraft ' + i.mcVersion }), h('small', { text: c.available === true ? (c.stable ? 'Versión oficial disponible: ' + c.versions.join(', ') : 'Solo preliminares: ' + c.versions.join(', ')) : c.reason })), h('span', { class: 'badge ' + (c.available === true ? (c.stable ? 'ok' : 'warn') : 'bad'), text: c.available === true ? (c.stable ? 'DISPONIBLE' : 'PREVIEW') : c.available === false ? 'NO DISPONIBLE' : 'SIN DATOS' })),
      h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: 'Instalado' }), h('small', { text: i.installed.length ? i.installed.join(', ') : 'No instalado' }))),
      h('div', { class: 'note', text: 'Pasos: 1) abre la página oficial y descarga el .jar; 2) pulsa "Instalar" y elige ese archivo; 3) en la ventana de OptiFine pulsa Install (se instala en la carpeta del launcher). Después elige "Con OptiFine" en Rendimiento. Si OptiFine no es compatible, sigues jugando con Minecraft normal.' }),
      h('div', { class: 'item' }, h('div', { class: 'grow' }), h('button', { class: 'btn', text: 'Abrir página oficial', onclick: () => quiet(api.invoke('optifine:open')) }), h('button', { class: 'btn primary', text: 'Instalar desde archivo…', disabled: c.available === false || S.task.busy, onclick: async (e) => { e.target.disabled = true; try { const r = await call('optifine:install'); if (r) { toast('OptiFine instalado: ' + r.join(', '), 'ok'); TAB.opti(b.replaceChildren() || b); } } catch { /* notificado */ } e.target.disabled = false; } })));
  },
  async gen(b) {
    const s = S.settings, p = S.init.paths;
    const act = (label, fn, cls = '') => h('button', { class: 'btn ' + cls, text: label, onclick: fn });
    b.append(ctl('Carpeta de instalación', p.root + (s.installDir ? '' : ' (predeterminada)') + ' — al cambiarla, Minecraft se instalará de nuevo allí', h('button', { class: 'btn', text: 'Cambiar…', onclick: async () => { const f = await call('dialog:folder'); if (f) { await saveSettings({ installDir: f }); toast('Carpeta cambiada. Pulsa JUGAR para instalar allí.', 'ok'); const i = await call('app:init'); S.init.paths = i.paths; } } })),
      h('h4', { class: 'grp', text: 'Mantenimiento' }),
      h('div', { class: 'list' },
        h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: 'Comprobar actualizaciones' }), h('small', { text: 'Minecraft, Java, packs y multimedia' })), act('Comprobar', () => { closeModal(); quiet(api.invoke('game:update')); })),
        h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: 'Reparar instalación' }), h('small', { text: 'Verifica el hash de todos los archivos y repara los dañados' })), act('Reparar', () => { closeModal(); quiet(api.invoke('game:repair')); })),
        h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: 'Limpiar archivos temporales' })), act('Limpiar', async () => { const n = await call('game:clean'); toast('Liberados ' + fmtB(n), 'ok'); })),
        h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: 'Carpetas' })), act('Minecraft', () => api.invoke('game:open-folder')), act('Capturas', () => api.invoke('game:open-screenshots')), act('Registros', () => api.invoke('game:open-logs'))),
        h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: 'Restaurar configuración predeterminada' }), h('small', { text: 'No borra tus cuentas ni tus mundos' })), act('Restaurar', async () => { S.settings = await call('settings:reset'); applySettingsFx(); toast('Configuración restaurada', 'ok'); showSettings('gen'); }, 'danger'))));
  },
};
function packItem(p, redraw) {
  const state = p.corrupt ? h('span', { class: 'badge bad', text: 'DAÑADO' }) : p.outdated ? h('span', { class: 'badge warn', text: 'ACTUALIZABLE' }) : p.installed ? h('span', { class: 'badge ok', text: 'INSTALADO' }) : h('span', { class: 'badge', text: 'NO INSTALADO' });
  const act = async (ch, ...a) => { try { redraw(await call(ch, ...a)); } catch { /* notificado */ } };
  const hist = p.history.length ? h('details', {}, h('summary', { class: 'muted', text: 'Historial (' + p.history.length + ')' }), ...p.history.map((x) => h('small', { class: 'muted', style: 'display:block', text: 'v' + x.version + ' · ' + fmtD(x.date) + ' · ' + x.sha256.slice(0, 10) }))) : null;
  return h('div', { class: 'item' }, h('div', { class: 'grow' }, h('b', { text: p.name }), h('small', { text: `v${p.version} · ${fmtB(p.size)}${p.updatedAt ? ' · ' + fmtD(p.updatedAt) : ''}${p.packFormat ? ' · formato ' + p.packFormat : ''}` }), p.description ? h('small', { text: p.description }) : null, hist),
    h('span', { class: 'badge ' + (p.category === 'required' ? 'req' : 'opt'), text: p.category === 'required' ? 'OBLIGATORIO' : 'OPCIONAL' }), state,
    h('button', { class: 'btn', title: 'Subir prioridad', text: '▲', onclick: () => act('packs:priority', p.id, 1) }), h('button', { class: 'btn', title: 'Bajar prioridad', text: '▼', onclick: () => act('packs:priority', p.id, -1) }),
    p.category === 'optional' ? [p.installed ? h('label', { style: 'display:flex;gap:5px;align-items:center' }, h('input', { type: 'checkbox', checked: p.enabled ? true : null, onchange: (e) => act('packs:toggle', p.id, e.target.checked) }), 'Activo') : null,
      h('button', { class: 'btn' + (p.installed ? ' danger' : ' primary'), text: p.installed ? 'Quitar' : 'Instalar', disabled: S.task.busy, onclick: (e) => { e.target.disabled = true; act(p.installed ? 'packs:uninstall' : 'packs:install', p.id); } })] : null);
}

boot().catch((e) => toast('Error al iniciar: ' + e.message, 'error', 15000));
