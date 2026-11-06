'use strict';
// Música, vídeo e imágenes de fondo gestionados desde el manifiesto (descargas con SHA-256).
const path = require('path');
const { pathToFileURL } = require('url');
const fs = require('fs');
const { safeJoin } = require('./util');
const { ensureFiles } = require('./files');

async function sync({ manifest, settings, paths, progress, signal }) {
  const dir = paths.media();
  const mk = (kind, r) => ({ url: r.url, dest: safeJoin(path.join(dir, kind), r.file), sha256: r.sha256, size: r.size });
  const files = [];
  for (const r of manifest.media.music) files.push(mk('music', r));
  for (const r of manifest.media.images) files.push(mk('images', r));
  // Los vídeos pesan: solo se descarga el elegido y solo si el jugador los tiene activados.
  if (settings.media.video) {
    const want = manifest.settings.defaultVideo || (manifest.media.videos[0] && manifest.media.videos[0].id);
    const v = manifest.media.videos.find((x) => x.id === want);
    if (v) files.push(mk('videos', v));
  }
  await ensureFiles(files, { progress, signal, full: true, concurrency: 2, strict: true });
}

function current({ manifest, settings, paths }) {
  const url = (kind, r) => { const f = safeJoin(path.join(paths.media(), kind), r.file); return fs.existsSync(f) ? pathToFileURL(f).toString() : ''; };
  const bg = manifest.media.images.find((i) => i.id === manifest.settings.defaultBackground) || manifest.media.images[0];
  const vid = manifest.media.videos.find((i) => i.id === (manifest.settings.defaultVideo || '')) || manifest.media.videos[0];
  return {
    background: bg ? url('images', bg) : '',
    video: settings.media.video && vid ? url('videos', vid) : '',
    tracks: manifest.media.music.map((t) => ({ id: t.id, title: t.name, url: url('music', t) })).filter((t) => t.url),
  };
}
module.exports = { sync, current };
