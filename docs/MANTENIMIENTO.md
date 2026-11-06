# Mantenimiento del repositorio

## Estructura
```
src/main        motor (descargas, Minecraft, Java, auth, packs, lanzamiento)
src/renderer    interfaz
config          valores por defecto (marca, URLs, Client IDs)
manifest        base editable + manifest.json generado (no lo edites a mano)
news            noticias
resources       packs, música, vídeos, imágenes
tools           validar packs, generar manifiesto, icono
.github         CI, manifiesto automático, publicación del instalador
```
## Tareas habituales
- Nueva versión del launcher: subir `version` en `package.json` → Actions → *Publicar launcher*.
- Probar antes de publicar: `npm test` (25 pruebas automáticas).
- Cambiar nombre/marca: `config/launcher.config.json` → `brand` y `package.json` → `productName`.
- Cambiar tema visual: `src/renderer/styles.css` (variables en `:root`).

## Problemas frecuentes
| Síntoma | Causa / solución |
|---|---|
| Microsoft falla con 403 al final | La app de Azure aún no está aprobada por Mojang (docs/AUTENTICACION.md) |
| "No se pudo leer la configuración remota" | Revisa que `manifest/manifest.json` existe y la URL de `config/launcher.config.json` apunta a tu repo/rama |
| Pack "inválido" | `node tools/validate-pack.js pack.zip` indica el motivo |
| Minecraft se cierra | El launcher muestra las últimas líneas del log; Ajustes → Reparar instalación |
| Aviso SmartScreen | Sin certificado de firma; ver INSTALACION |

## Seguridad
Descargas solo por HTTPS desde hosts permitidos (`remote.allowedHosts`), SHA-256 en todo, validación estricta del manifiesto (solo datos, nunca se ejecuta nada remoto), protección ZIP Slip/path traversal, tokens cifrados con DPAPI, argumentos JVM filtrados. Recomendación: activa la protección de la rama `main` para que nadie publique manifiestos sin revisión, y firma el instalador con un certificado de código.
