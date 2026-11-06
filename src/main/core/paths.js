'use strict';
const { app } = require('electron');
const path = require('path');
const fs = require('fs');

let customRoot = '';
const ensure = (d) => { fs.mkdirSync(d, { recursive: true }); return d; };

// Estructura: <root>/.minecraft (juego), <root>/runtime (Java), <root>/media, <root>/cache, <root>/state
const P = {
  setRoot(p) { customRoot = p || ''; },
  userData: () => app.getPath('userData'),
  appDir: () => app.getAppPath(),
  root() { return ensure(customRoot || path.join(app.getPath('appData'), 'AetheriaRPG')); },
  game() { return ensure(path.join(this.root(), '.minecraft')); },
  packs() { return ensure(path.join(this.game(), 'resourcepacks')); },
  runtime() { return ensure(path.join(this.root(), 'runtime')); },
  media() { return ensure(path.join(this.root(), 'media')); },
  cache() { return ensure(path.join(this.root(), 'cache')); },
  state() { return ensure(path.join(this.root(), 'state')); },
  logs() { return ensure(path.join(app.getPath('userData'), 'logs')); },
};
module.exports = P;
