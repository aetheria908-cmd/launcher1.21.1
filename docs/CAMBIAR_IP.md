# Cambiar la dirección del servidor

La dirección vive en **un solo sitio** y no hace falta reconstruir el launcher:

1. Edita `manifest/manifest.base.json`:
```json
"server": { "name": "AetheriaRPG", "address": "play.tudominio.com", "port": 25565, "minecraftVersion": "1.21.1" }
```
2. *Commit changes*. La acción **Actualizar manifiesto** lo propaga a `manifest/manifest.json` y todos los launchers lo usan al abrirse.

`config/launcher.config.json` solo contiene el valor de respaldo (`localhost`) por si no hubiera conexión.
Admite dominios con registro SRV (`_minecraft._tcp`). El estado del servidor (jugadores, latencia) y el botón JUGAR usan esta misma dirección.
