# Autenticación: qué debes proporcionar

El ejecutable **no contiene secretos**. Solo necesita dos identificadores públicos que se escriben en
`config/launcher.config.json` → `auth` (después, publica una versión nueva del launcher).

## 1) Microsoft / Minecraft Premium
Usa el flujo oficial de *código de dispositivo*: el jugador escribe un código en microsoft.com/link; nunca se solicita ni guarda su contraseña.

1. Entra en <https://portal.azure.com> → **Microsoft Entra ID → Registros de aplicaciones → Nuevo registro**.
2. Tipos de cuenta: **Solo cuentas personales de Microsoft**. Sin URI de redirección.
3. En **Autenticación** activa **"Permitir flujos de cliente público"** = Sí.
4. Copia el **Id. de aplicación (cliente)** a `auth.microsoft.clientId`.
5. **Importante:** Mojang exige que toda aplicación que use sus servicios sea aprobada. Solicítalo en el formulario oficial: <https://aka.ms/AppRegInfo>. **Hasta que la aprueben, el inicio de sesión fallará en el último paso** (`login_with_xbox`) con un error 403 y el launcher lo indicará. Suele tardar días.

## 2) Discord
1. <https://discord.com/developers/applications> → **New Application**.
2. **OAuth2 → Redirects**: añade exactamente `http://127.0.0.1:53682/callback`.
3. Activa **Public Client** en OAuth2 (permite PKCE sin secreto).
4. Copia el **Client ID** a `auth.discord.clientId`.
5. (Opcional) Para marcar a los miembros de tu servidor: pon el ID de tu servidor en `auth.discord.guildId`.
6. Si prefieres no usar *Public Client*, monta un endpoint propio (p. ej. una función de Netlify de tu web) que reciba `{code, code_verifier, redirect_uri}`, haga el intercambio con tu *client secret* y devuelva el JSON de tokens; pon su URL en `auth.discord.tokenExchangeUrl`. **Nunca pongas el secret en el launcher.**

## 3) Perfil offline
Crea un perfil local (UUID offline estándar). **No es premium ni lo simula.** Solo funciona en servidores con `online-mode=false`; los servidores con autenticación oficial lo rechazan. Si habilitas modo offline en tu servidor, protégelo con un plugin de login (AuthMe o similar) para evitar suplantaciones.

## Independencia
Discord, Microsoft y offline son independientes. Discord identifica a la comunidad pero **no** da licencia de Minecraft ni sustituye la autenticación de Minecraft.
