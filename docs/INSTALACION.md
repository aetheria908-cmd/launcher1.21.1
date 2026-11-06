# Instalación y distribución

## Para los jugadores
1. Descargar `AetheriaRPG-Launcher-Setup-x.y.z.exe` desde **Releases** del repositorio.
2. Ejecutarlo (no necesita permisos de administrador; puede elegir carpeta).
3. Abrir el launcher → elegir cuenta (Microsoft o perfil offline; Discord es opcional).
4. El launcher descarga Minecraft 1.21.1, Java y los Texture Packs automáticamente.
5. Pulsar **JUGAR / UNIRSE**.

> Windows SmartScreen puede avisar de "editor desconocido" porque el instalador no está firmado con un certificado de pago. Es normal: *Más información → Ejecutar de todas formas*. Para evitarlo hay que comprar un certificado de firma de código (ver MANTENIMIENTO).

## Para el administrador: generar el instalador
1. En GitHub → **Actions → Publicar launcher → Run workflow**.
2. Espera ~5 min. El instalador `.exe` aparece en **Releases** junto con `latest.yml` (lo usa el actualizador automático).
3. Para sacar una versión nueva: sube `"version"` en `package.json` (por ejemplo `1.0.1`) y repite el paso 1. Los jugadores reciben la actualización solos al abrir el launcher.

Alternativa local (en un PC con Windows y Node 22): `npm install && npm run dist` → instalador en `dist/`.

## Dónde guarda el launcher los datos
- Juego y recursos: `%APPDATA%\AetheriaRPG\` (`.minecraft`, `runtime`, `media`, `cache`). Se puede cambiar en Ajustes → General.
- Configuración y cuentas (cifradas con Windows DPAPI): `%APPDATA%\aetheriarpg-launcher\`.
- Registros: `%APPDATA%\aetheriarpg-launcher\logs\launcher.log`.
