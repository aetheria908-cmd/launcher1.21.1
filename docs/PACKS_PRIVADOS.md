# Texture Packs privados (cifrados) y OptiFine

## Qué hace
- Los packs privados se publican **cifrados** (AES-256-GCM, archivos `.aep`). Quien descargue el archivo desde GitHub solo obtiene datos ilegibles.
- El launcher los descarga cifrados, los **descifra solo al pulsar JUGAR**, y los **borra de la carpeta `resourcepacks` al cerrar Minecraft**.
- Cada archivo está ligado a su id y a su hash: si alguien lo modifica o lo cambia por otro, el launcher lo rechaza y no lo activa.
- La clave **no está en el repositorio**: se inyecta al compilar desde el secreto `PACK_KEY`.

## ⚠️ Qué NO puede hacer (sé realista)
Minecraft necesita el pack descifrado para mostrarlo. Un jugador técnico que quiera copiarlo puede hacerlo mientras juega, o extraer la clave del launcher. El cifrado protege contra **la descarga casual y el reparto del archivo**, no es una protección absoluta. Para algo más fuerte, la clave debería entregarse desde un servidor solo a miembros verificados de Discord (ver al final).

## Configuración inicial (una sola vez)
1. GitHub → **Settings → Secrets and variables → Actions → New repository secret**.
   - Nombre: `PACK_KEY`  · Valor: la clave que te di (base64). **Guárdala también en un gestor de contraseñas**: sin ella no se pueden cifrar packs nuevos.
2. Ejecuta **Actions → Publicar launcher**. El flujo comprueba que la clave quedó dentro del launcher; si falta, falla con un mensaje claro.

## Subir los packs cifrados
- Packs pequeños (`.aep` de menos de 25 MB): súbelos a `resources/resourcepacks/required/` (junto a su `.aep.json`).
- Packs grandes (como RAZAS, 26 MB): GitHub → **Releases → Draft a new release** → tag `resources` → adjunta el `.aep` → *Publish*. Su ficha `.aep.json` (en el repositorio) ya apunta a esa URL.
- La acción **Actualizar manifiesto** regenera `manifest/manifest.json` sola.

## Añadir o actualizar un pack privado
```bash
# 1) clave en el entorno (PowerShell: $env:PACK_KEY="...")
export PACK_KEY="…tu clave…"
# 2) cifrar (valida el pack, cifra, comprueba el descifrado y crea la ficha)
node tools/encrypt-pack.js MiPack.zip --id mi-pack --category required --name "Mi Pack" --priority 10
#    pack grande:  añade --release-url https://github.com/aetheria908-cmd/launcher1.21.1/releases/download/resources/MiPack.aep
```
También puedes pasarme el `.zip` por el chat y lo cifro yo. **Nunca subas el `.zip` original al repositorio.**

## OptiFine
- Un pack con carpeta `assets/*/optifine/` (CIT, CEM, animaciones…) se marca automáticamente `requiresOptifine`: solo se descarga y activa si el jugador juega **con OptiFine instalado** (Ajustes → Rendimiento → "Con OptiFine"). Sin OptiFine el launcher ni lo descarga.
- Para cambiarlo, edita `requiresOptifine` en la ficha `.aep.json` (o `NombrePack.zip.json` en packs sin cifrar).
- El launcher comprueba en optifine.net si hay versión oficial para 1.21.1 y guía la instalación (Ajustes → OptiFine).

## Prioridad
Mayor número = se aplica encima. Ahora: Aetheria 0 < Gremios 5 < Razas 10. Solo hay un archivo en conflicto entre packs (`textures/item/knowledge_book.png`, en Aetheria y Razas): gana Razas.

## Mejora opcional: clave solo para miembros
Una función de Netlify de tu web podría devolver la clave únicamente a quien haya iniciado sesión con Discord y pertenezca a tu servidor. Así la clave no viajaría dentro del launcher. Es un cambio aparte; pídemelo si lo quieres.
