'use strict';
const { listEntries, readEntry, checkEntryNames } = require('./zip');

const FORMAT_TO_VERSION = { 1: '1.6–1.8.9', 2: '1.9–1.10.2', 3: '1.11–1.12.2', 4: '1.13–1.14.4', 5: '1.15–1.16.1',
  6: '1.16.2–1.16.5', 7: '1.17.x', 8: '1.18.x', 9: '1.19–1.19.2', 12: '1.19.3', 13: '1.19.4', 15: '1.20–1.20.1',
  18: '1.20.2', 22: '1.20.3–1.20.4', 32: '1.20.5–1.20.6', 34: '1.21–1.21.1' };
const TARGET_FORMAT = 34; // Minecraft 1.21.1

function formatRange(meta) {
  const sf = meta.supported_formats;
  if (typeof sf === 'number') return [sf, sf];
  if (Array.isArray(sf) && sf.length === 2) return [sf[0], sf[1]];
  if (sf && typeof sf === 'object' && 'min_inclusive' in sf) return [sf.min_inclusive, sf.max_inclusive];
  return null;
}

function inspectPack(file, target = TARGET_FORMAT) {
  const r = { ok: false, errors: [], warnings: [], info: {} };
  let entries;
  try { entries = listEntries(file); } catch (e) { r.errors.push(e.message); return r; }
  const names = checkEntryNames(entries);
  r.errors.push(...names.problems);
  const files = entries.filter((e) => !e.name.endsWith('/'));
  r.info.fileCount = files.length;
  r.info.uncompressedSize = names.totalUncompressed;
  const meta = entries.find((e) => e.name === 'pack.mcmeta');
  if (!meta) {
    const nested = entries.find((e) => /^[^/]+\/pack\.mcmeta$/.test(e.name));
    r.errors.push(nested
      ? `pack.mcmeta está dentro de la carpeta "${nested.name.split('/')[0]}/". Debe estar en la raíz del ZIP o Minecraft no lo detectará.`
      : 'Falta pack.mcmeta en la raíz del ZIP.');
  } else {
    try {
      const txt = readEntry(file, meta).toString('utf8').replace(/^\uFEFF/, '');
      const j = JSON.parse(txt);
      if (!j.pack || typeof j.pack !== 'object') r.errors.push('pack.mcmeta no contiene el objeto "pack".');
      else {
        const pf = j.pack.pack_format;
        r.info.packFormat = pf;
        r.info.minecraft = FORMAT_TO_VERSION[pf] || 'desconocida';
        r.info.description = typeof j.pack.description === 'string' ? j.pack.description : JSON.stringify(j.pack.description ?? '');
        if (!Number.isInteger(pf)) r.errors.push('pack.mcmeta: "pack_format" debe ser un número entero.');
        else {
          const range = formatRange(j.pack);
          const ok = pf === target || (range && range[0] <= target && target <= range[1]);
          if (!ok) r.warnings.push(`pack_format ${pf} (Minecraft ${r.info.minecraft}); 1.21.1 usa ${target}. Minecraft lo marcará como incompatible aunque puede funcionar. Se recomienda adaptarlo (requiere tu aprobación).`);
        }
        if (j.pack.description === undefined) r.warnings.push('pack.mcmeta no tiene "description".');
      }
    } catch (e) { r.errors.push('pack.mcmeta no es un JSON válido: ' + e.message); }
  }
  const ns = new Set();
  for (const f of files) { const m = /^assets\/([^/]+)\//.exec(f.name); if (m) ns.add(m[1]); }
  r.info.namespaces = [...ns];
  r.info.usesOptifine = files.some((f) => /^assets\/[^/]+\/optifine\//.test(f.name));
  r.info.hasIcon = entries.some((e) => e.name === 'pack.png');
  if (!ns.size) r.errors.push('El pack no contiene ninguna carpeta assets/<namespace>/.');
  r.ok = r.errors.length === 0;
  return r;
}

module.exports = { inspectPack, TARGET_FORMAT, FORMAT_TO_VERSION };
