'use strict';
const { contextBridge, ipcRenderer } = require('electron');

// Lista blanca de canales: la interfaz no puede invocar nada más.
const INVOKE = ['app:init', 'win:minimize', 'win:maximize', 'win:close', 'shell:open', 'manifest:refresh', 'news:get', 'server:status', 'media:get',
  'accounts:list', 'accounts:select', 'accounts:remove', 'auth:offline', 'auth:ms:start', 'auth:ms:cancel', 'auth:discord:login', 'auth:discord:logout',
  'settings:get', 'settings:set', 'settings:reset', 'system:memory', 'dialog:folder', 'dialog:file',
  'game:play', 'game:update', 'game:repair', 'task:cancel', 'game:clean', 'game:open-folder', 'game:open-logs', 'game:open-screenshots',
  'packs:list', 'packs:install', 'packs:uninstall', 'packs:toggle', 'packs:priority', 'packs:verify', 'packs:active',
  'optifine:info', 'optifine:open', 'optifine:install', 'update:install'];
const EVENTS = ['task:progress', 'task:state', 'game:state', 'game:crash', 'toast', 'ms:code', 'update:state', 'remote:updated', 'media:updated'];

contextBridge.exposeInMainWorld('api', {
  invoke: async (ch, ...args) => {
    if (!INVOKE.includes(ch)) throw new Error('Canal no permitido: ' + ch);
    const r = await ipcRenderer.invoke(ch, ...args);
    if (!r.ok) { const e = new Error(r.error); e.code = r.code; throw e; }
    return r.data;
  },
  on: (ch, cb) => {
    if (!EVENTS.includes(ch)) throw new Error('Evento no permitido: ' + ch);
    const h = (_e, p) => cb(p);
    ipcRenderer.on(ch, h);
    return () => ipcRenderer.removeListener(ch, h);
  },
});
