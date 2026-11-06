# Administrar Texture Packs

Estructura:
```
resources/resourcepacks/required/   ← obligatorios (se instalan y actualizan solos para todos)
resources/resourcepacks/optional/   ← opcionales (el jugador decide en Ajustes → Texture Packs)
```

## Añadir o actualizar un pack
1. (Recomendado) Valídalo: `node tools/validate-pack.js MiPack.zip` — comprueba `pack.mcmeta`, `pack_format` (1.21.1 = **34**), carpeta `assets/`, rutas peligrosas (ZIP Slip) y calcula el SHA-256.
2. Sube el `.zip` a la carpeta `required` u `optional` (GitHub → *Add file → Upload files*; el navegador admite hasta 25 MB por archivo; con git hasta 100 MB).
3. GitHub Actions ejecuta **Actualizar manifiesto** y regenera `manifest/manifest.json` con hash, tamaño, versión e historial. A partir de ahí los jugadores lo reciben al abrir el launcher (solo se descarga el archivo cambiado).
   - Para actualizar un pack, sube un `.zip` con **el mismo nombre**: la versión sube sola (1.0.0 → 1.0.1).
4. Metadatos opcionales: crea `MiPack.zip.json` junto al zip: `{"name":"Nombre bonito","description":"…","priority":10,"version":"2.0.0"}`. `priority` mayor = se aplica encima de los demás.

## Packs de más de 95 MB
Súbelos a una **GitHub Release** (hasta 2 GB) y pon su URL en `MiPack.zip.json`: `{"url":"https://github.com/…/releases/download/…/MiPack.zip"}`. Mantén un `MiPack.zip` pequeño de marcador o crea el manifiesto a mano; consulta MANTENIMIENTO.

## Retirar un pack
Borra el `.zip` del repositorio. Al próximo arranque el launcher lo elimina de los jugadores y lo desactiva.

## Comportamiento en el cliente
- Se verifica SHA-256 en cada arranque; un archivo dañado se vuelve a descargar.
- Un pack inválido publicado **no se activa** y queda registrado en el log.
- Los obligatorios se activan en `options.txt` conservando los packs y preferencias personales del jugador.
- Un pack con `pack_format` distinto de 34 puede aparecer como "incompatible" en Minecraft. Si te lo envío por el chat, **te aviso antes de modificar su contenido**.
