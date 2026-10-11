# Shared data format (Android ↔ Windows)

Nova IPTV for Android and for Windows use the same JSON field names, so a future
backup file can move accounts and favorites between the TV box and a PC.
Backup / restore on Windows comes in Phase 2; this page records the format so both
apps stay in step. Change it here whenever one side changes.

## Account

```json
{ "id": "a1b2c3", "name": "Main", "server": "http://host:8080", "username": "...", "password": "..." }
```

## Favorite (SavedItem)

```json
{ "type": "live", "id": "1234", "name": "BBC One", "icon": "http://...", "extension": null,
  "savedAt": 1760000000000, "accountId": "a1b2c3" }
```

- `type`: `live`, `movie`, `series` or `episode`
- `id`: stream id (live, movie), series id or episode id
- `extension`: container of films / episodes (`mp4`, `mkv`…)
- `savedAt`: milliseconds since 1970

## Where each app keeps them

| | Android | Windows |
|---|---|---|
| Accounts | SharedPreferences `accounts` | `accounts.json` |
| Favorites | SharedPreferences `favorites_v2` | `favorites.json` |
| Settings | SharedPreferences (many keys) | `settings.json` (Windows-only settings) |

Windows data folder: `%APPDATA%\NovaIPTV`, or the `data` folder next to `Nova.exe`
in the portable version.
