'use strict';
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { evalRules, mavenPath, parseJvmArgs, safeJoin } = require('./util');
const log = require('./logger');

function resolveVersion(gameDir, id, depth = 0) {
  if (depth > 3) throw new Error('Cadena de herencia de versión demasiado profunda');
  const f = safeJoin(path.join(gameDir, 'versions'), id, id + '.json');
  const j = JSON.parse(fs.readFileSync(f, 'utf8'));
  if (!j.inheritsFrom) return { ...j, baseId: id };
  const p = resolveVersion(gameDir, j.inheritsFrom, depth + 1);
  return {
    ...p, ...j,
    libraries: [...(j.libraries || []), ...(p.libraries || [])],
    arguments: { game: [...((p.arguments || {}).game || []), ...((j.arguments || {}).game || [])], jvm: [...((p.arguments || {}).jvm || []), ...((j.arguments || {}).jvm || [])] },
    mainClass: j.mainClass || p.mainClass, assetIndex: j.assetIndex || p.assetIndex, downloads: p.downloads, baseId: p.baseId, jar: j.jar || p.jar || p.baseId,
  };
}

function expand(list, vars, features) {
  const out = [];
  for (const a of list || []) {
    if (typeof a === 'string') out.push(a);
    else if (evalRules(a.rules, features)) out.push(...(Array.isArray(a.value) ? a.value : [a.value]));
  }
  return out.map((s) => String(s).replace(/\$\{([^}]+)\}/g, (m, k) => (k in vars ? String(vars[k]) : '')));
}
function dropEmptyFlags(args) {
  const out = [];
  for (let i = 0; i < args.length; i++) { if (/^--/.test(args[i]) && args[i + 1] === '') { i++; continue; } out.push(args[i]); }
  return out;
}

function classpathFor(v, gameDir) {
  const libDir = path.join(gameDir, 'libraries');
  const seen = new Set(); const cp = [];
  for (const lib of v.libraries || []) {
    if (!evalRules(lib.rules)) continue;
    const rel = lib.downloads && lib.downloads.artifact ? lib.downloads.artifact.path : mavenPath(lib.name);
    const f = safeJoin(libDir, rel);
    if (seen.has(f)) continue;
    if (!fs.existsSync(f)) { log.warn('Biblioteca ausente (se omite): ' + rel); continue; }
    seen.add(f); cp.push(f);
  }
  const jarId = v.jar || v.baseId;
  cp.push(path.join(gameDir, 'versions', jarId, jarId + '.jar'));
  return cp;
}

// auth: { name, uuid, token, userType }   server: { host, port } | null
function buildCommand({ versionId, gameDir, javaExe, auth, settings, ramMb, server, launcherVersion }) {
  const v = resolveVersion(gameDir, versionId);
  if (!v.arguments) throw new Error('Esta versión usa un formato de argumentos antiguo y no está soportada.');
  const natives = path.join(gameDir, 'versions', v.baseId, 'natives');
  fs.mkdirSync(natives, { recursive: true });
  const cp = classpathFor(v, gameDir);
  const hasRes = settings.width > 0 && settings.height > 0;
  const vars = {
    auth_player_name: auth.name, version_name: v.id, game_directory: gameDir, assets_root: path.join(gameDir, 'assets'),
    assets_index_name: v.assetIndex.id, auth_uuid: auth.uuid, auth_access_token: auth.token, clientid: '', auth_xuid: '',
    user_type: auth.userType, version_type: v.type || 'release', resolution_width: settings.width, resolution_height: settings.height,
    natives_directory: natives, launcher_name: 'AetheriaRPG', launcher_version: launcherVersion || '1.0.0',
    classpath: cp.join(';'), classpath_separator: ';', library_directory: path.join(gameDir, 'libraries'),
  };
  const features = { has_custom_resolution: hasRes, is_demo_user: false };
  const userJvm = parseJvmArgs(settings.jvmArgs);
  const jvm = [`-Xms${ramMb.min}M`, `-Xmx${ramMb.max}M`, '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=100', '-XX:+UnlockExperimentalVMOptions', '-XX:+DisableExplicitGC', '-XX:+ParallelRefProcEnabled',
    ...userJvm.args, ...dropEmptyFlags(expand((v.arguments || {}).jvm, vars, features))];
  const lc = v.logging && v.logging.client;
  if (lc && lc.file) {
    const lf = path.join(gameDir, 'assets', 'log_configs', lc.file.id);
    if (fs.existsSync(lf)) jvm.push(String(lc.argument || '-Dlog4j.configurationFile=${path}').replace('${path}', lf));
  }
  let game = dropEmptyFlags(expand((v.arguments || {}).game, vars, features));
  if (settings.fullscreen) game.push('--fullscreen');
  if (server && !game.includes('--quickPlayMultiplayer')) game.push('--quickPlayMultiplayer', `${server.host}:${server.port}`);
  return { exe: javaExe, args: [...jvm, v.mainClass, ...game], cwd: gameDir, rejectedJvm: userJvm.rejected };
}

function run(cmd, { onLine, onExit }) {
  const mask = cmd.args.map((a, i, arr) => (arr[i - 1] === '--accessToken' ? '***' : a)).join(' ');
  log.info('Lanzando: ' + cmd.exe + ' ' + mask.slice(0, 300) + '…');
  const child = spawn(cmd.exe, cmd.args, { cwd: cmd.cwd, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const tail = [];
  const feed = (d) => String(d).split(/\r?\n/).filter(Boolean).forEach((l) => { tail.push(l); if (tail.length > 150) tail.shift(); onLine && onLine(l); });
  child.stdout.on('data', feed); child.stderr.on('data', feed);
  child.on('error', (e) => onExit(-1, [String(e.message)]));
  child.on('close', (code) => onExit(code, tail));
  return child;
}

module.exports = { buildCommand, run, resolveVersion, expand, dropEmptyFlags };
