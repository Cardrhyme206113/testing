# Offline Identity (Fabric 1.20.1)

Client-side Fabric mod that lets Modrinth/Minecraft authenticate normally, then replaces the in-game `Session` with a configurable offline identity.

## Behavior

1. Minecraft is launched normally through Modrinth and receives its authenticated session.
2. The mod checks that the launch session has a non-empty access token and a valid UUID.
3. It replaces the runtime session with:
   - the configured username;
   - the standard offline-mode UUID derived from `OfflinePlayer:<username>`;
   - an empty access token;
   - empty XUID/client ID.
4. Online-mode servers will reject the resulting offline session. Singleplayer/LAN/offline-mode servers can use it.

This check is intended to preserve the normal licensed-launch workflow; it is not DRM or cryptographic proof of ownership by itself.

## Config

The default config is global across Minecraft/Modrinth instances:

```text
~/.offline-identity/config.json
```

It is created automatically on first launch:

```json
{
  "enabled": true,
  "username": "Player"
}
```

Valid usernames are 3-16 characters using letters, numbers, and `_`.

### Per-instance override

If this file exists inside a Minecraft instance, it takes priority over the global config:

```text
config/offline-identity.json
```

Set `enabled` to `false` to leave the original authenticated session untouched.

## Build

Requires Java 17 and Gradle 8.x.

```bash
gradle build
```

The remapped mod jar is produced in `build/libs/`.

## Scope / "global"

The **configuration is global** by default, so one username setting can apply everywhere the mod is installed. Fabric still loads mods per Minecraft instance, so the jar itself must be present in each profile's `mods` directory (or shared/symlinked there).

The session implementation is intentionally tiny so additional Minecraft-version-specific source sets/branches can reuse the same config and identity rules later.
