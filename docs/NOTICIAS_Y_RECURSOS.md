# Noticias, multimedia y avisos

## Noticias (`news/news.json`)
Edita el archivo directamente en GitHub (lápiz ✏️ → *Commit changes*). Los jugadores lo ven al abrir el launcher.
```json
{ "id": "evento-halloween", "type": "evento", "title": "Evento de Halloween", "date": "2026-10-31",
  "summary": "Texto corto para la tarjeta.", "body": "Texto largo.\n\nUn párrafo por línea en blanco.",
  "image": "https://…/imagen.jpg", "url": "https://…", "pinned": false }
```
`type`: anuncio · actualizacion · evento · mantenimiento · importante · texturepack · servidor. `image` y `url` deben ser https. La noticia con `pinned: true` (o la más reciente) es la destacada.

## Música, vídeos e imágenes
Sube archivos a `resources/music` (.mp3 .ogg .wav), `resources/videos` (.mp4 .webm) y `resources/images` (.png .jpg .webp); el manifiesto se actualiza solo.
- Fondo de imagen / vídeo / música por defecto: en `manifest/manifest.base.json` → `settings` → `defaultBackground` / `defaultVideo` con el **id** (nombre del archivo en minúsculas, sin extensión, guiones en vez de espacios).
- Los vídeos solo se descargan si el jugador los tiene activados. Usa solo material con derechos.

## Mantenimiento y avisos
En `manifest/manifest.base.json`: `"maintenance": {"enabled": true, "message": "Volvemos a las 20:00"}` bloquea el botón JUGAR y muestra el aviso. Ponlo en `false` para reabrir.
`"launcher": {"minVersion": "1.0.2"}` obliga a actualizar el launcher a esa versión.

## Enlaces
`urls.discord` y `urls.website` (manifest.base.json) cambian los botones sin reconstruir el launcher.
