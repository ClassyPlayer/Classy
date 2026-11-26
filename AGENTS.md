# Agents and contributors

## Migrations

A migration (`app/src/main/java/com/themoon/y1/migrations/`) is for one of two changes:

1. A change outside the APK that an app update cannot deliver. System Update (OTA) installs only the APK. Examples: files
   in `/system`, the boot screen, framework resources.
2. A move of app or user files, when without it users cannot get to their data (for example playlists in an old
   folder).

- Do not write a migration for a default of a setting, the order or visibility of menu items, or a library rescan.
  Change the default in the code. Users change settings and menus in Settings. The code discards an old library cache
  and scans again.
- A migration must be idempotent. It must be safe on a player with a freshly flashed ROM, which can have the change
  already and has no old folders.
- Do not use the version or the id of a removed migration again (see `MigrationManager`).

The same rule is in the KDoc of `Migration` and `MigrationManager`.
