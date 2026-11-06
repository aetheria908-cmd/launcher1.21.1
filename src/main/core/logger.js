'use strict';
const fs = require('fs');
const path = require('path');
let file = null;

function init(dir) {
  file = path.join(dir, 'launcher.log');
  try { if (fs.statSync(file).size > 2 * 1024 * 1024) fs.renameSync(file, file + '.old'); } catch { /* primer arranque */ }
}
// Nunca registrar tokens.
const redact = (s) => String(s).replace(/(accessToken|access_token|refresh_token|Bearer|xsts|XBL3\.0 x=)[\s=:"']+[A-Za-z0-9._;\-]{16,}/gi, '$1 ***');
function write(level, msg) {
  const line = `${new Date().toISOString()} [${level}] ${redact(msg)}\n`;
  if (file) { try { fs.appendFileSync(file, line); } catch { /* ignorar */ } }
  if (process.env.AETHERIA_DEBUG) process.stdout.write(line);
}
module.exports = {
  init,
  info: (m) => write('INFO', m),
  warn: (m) => write('WARN', m),
  error: (e) => write('ERROR', e instanceof Error ? (e.stack || e.message) : e),
  file: () => file,
};
