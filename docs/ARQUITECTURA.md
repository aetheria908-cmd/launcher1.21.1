# Arquitectura

Electron (proceso principal Node + interfaz aislada). La interfaz **no** tiene acceso a Node: solo a una lista blanca de canales IPC (`src/main/preload.js`).

| Módulo | Responsabilidad |
|---|---|
| `core/remote.js` | Configuración central + manifiesto remoto validado (caché sin conexión) |
| `core/manifest.js` | Validación estricta del manifiesto y compatibilidad de esquema |
| `core/http.js`, `files.js`, `progress.js` | HTTPS, descargas reanudables, SHA, velocidad/ETA, cancelación |
| `core/minecraft.js`, `java.js`, `launch.js` | Manifiestos oficiales de Mojang, runtime de Java, comando de lanzamiento (+ `inheritsFrom` para OptiFine) |
| `core/packs.js`, `packcheck.js`, `zip.js`, `options.js` | Texture Packs: sincronizar, validar, activar en `options.txt` sin pisar preferencias |
| `core/media.js`, `optifine.js`, `serverping.js` | Multimedia, OptiFine oficial, estado real del servidor (Server List Ping) |
| `auth/*` | Microsoft (device code → Xbox → XSTS → Minecraft), Discord (PKCE), offline, almacén cifrado |
| `game.js` | Orquestación: manifiesto → cuenta → Minecraft/Java → packs → lanzar |

Esquema del manifiesto: `schemaVersion: 1`. Si algún día se sube a 2, los launchers antiguos mostrarán "actualiza el launcher" en vez de fallar.
