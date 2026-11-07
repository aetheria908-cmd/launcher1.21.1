# AetheriaRPG Launcher

Launcher propio de **Minecraft Java 1.21.1** para la comunidad de AetheriaRPG (Windows 10/11, 64 bits).
Los jugadores solo abren el launcher, inician sesión y pulsan **JUGAR / UNIRSE**.

- 🏰 Interfaz propia con el logo de AetheriaRPG, noticias, estado real del servidor, música ambiental original (incluida) y vídeo de fondo opcional.
- 👤 Cuentas independientes: **Discord** (comunidad), **Microsoft** (premium) y **perfil offline**.
- 🎨 **Texture Packs centralizados**: tú los publicas en este repositorio y a los jugadores se les descargan y actualizan solos (solo lo que cambió, con SHA-256).
- ☕ Instala solo Minecraft 1.21.1 (archivos oficiales de Mojang) y el Java oficial; sin Forge/Fabric/mods. OptiFine opcional (solo si existe versión oficial).
- 🔄 Todo se administra desde GitHub: IP del servidor, noticias, packs, música, mantenimiento… sin reinstalar el launcher.

## Probar que todo funciona (sin servidor)
1. Abre el launcher → **Cuentas → Perfil offline** → crea un nombre.
2. Pulsa **Modo prueba · sin servidor**: instala Minecraft 1.21.1 y los packs y abre el juego *sin conectarse* a ningún servidor.
3. En el juego: *Opciones → Paquetes de recursos* debe mostrar tus packs obligatorios activados. En el launcher, **Ajustes → Texture Packs** lista lo que hay activo en `options.txt`.
4. Si algo falla: pulsa **F12** (herramientas de desarrollo) y revisa `%APPDATA%\aetheriarpg-launcher\logs\launcher.log`.

## Documentación
| Guía | Contenido |
|---|---|
| [INSTALACION](docs/INSTALACION.md) | Cómo generar el instalador y entregarlo a los jugadores |
| [AUTENTICACION](docs/AUTENTICACION.md) | **Lo que debes proporcionar**: Client ID de Microsoft y de Discord |
| [TEXTURE_PACKS](docs/TEXTURE_PACKS.md) | Cómo añadir, actualizar y retirar packs |
| [PACKS_PRIVADOS](docs/PACKS_PRIVADOS.md) | **Packs cifrados**, clave `PACK_KEY`, OptiFine y prioridades |
| [NOTICIAS_Y_RECURSOS](docs/NOTICIAS_Y_RECURSOS.md) | Noticias, música, vídeos, imágenes, mantenimiento |
| [CAMBIAR_IP](docs/CAMBIAR_IP.md) | Cambiar `localhost` por la IP/dominio definitivo |
| [MANTENIMIENTO](docs/MANTENIMIENTO.md) | Mantenimiento del repositorio y solución de problemas |
| [ARQUITECTURA](docs/ARQUITECTURA.md) | Cómo está construido (para desarrolladores) |

## Desarrollo
```bash
npm install
npm test          # pruebas unitarias e integración
npm start         # abre el launcher
npm run dist      # genera el instalador en dist/
```
