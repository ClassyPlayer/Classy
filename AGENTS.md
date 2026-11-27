# Agents and contributors

## Two players, one codebase

The app has two flavors with the same `applicationId`: `y2` (Android 4.4, API 19, microSD at `/storage/sdcard1`) and
`y1` (Android 4.2, API 17, microSD at `/storage/sdcard0`). Every feature must work on both.

- Write code for API 17. Lint treats `NewApi` as fatal (`app/lint.xml`). Put fallbacks for newer APIs in
  `core/ApiCompat`.
- Get storage folders from `StoragePaths`. Do not write a card path in the code.
- Before a pull request, run `./gradlew testY1ReleaseUnitTest testY2ReleaseUnitTest lintY1Release`. If lint fails on
  errors that were there before your change, check that none of them is `NewApi` and none is in a file you changed.

## Code

- Keep logic without Android types in its own classes, with JVM tests in `app/src/test/` (see `books/`, `photos/`,
  `managers/Aiff.kt`). Android code then only wires them to views and files.
- UI sizes are in iPod classic screen units (320×240). `ClassyMetrics` scales them to the screen of the player.
- The main menu rows are in `controllers/menu/MainMenuItems.kt`. Users hide and order them in Settings
  (`Prefs.MAIN_MENU_HIDDEN`, `Prefs.MENU_ORDER`).
- When the library scan reads something new, raise `CACHE_VERSION` in `MediaLibraryManager` and in
  `tools/build_library_cache.py` together. The app then discards the old cache and scans again (no migration).
- The native game core binds its JNI symbols to `com.themoon.y1.games.FliwheelBridge`. Renaming or moving that class
  needs a rebuild of `libfliwheel_android.so`.
- New icons come from Tabler Icons.

## Texts

- Every text that the user sees goes through `t()` / `localize()`, with its key in all files of
  `app/src/main/assets/languages/`. CI runs `python3 scripts/check_translations.py --strict`.
- A text with a count uses `Plurals.format`, with one form for each plural category of the language (see the README).
- The built-in languages always load from the app. An update of a language file needs no version or migration.

## Testing on a player

If `adb devices -l` lists a player, test the change on it, not only with unit tests. Build the flavor of that player
(`model:Y2` is `y2`).

- The app is a system app. On a player with an official firmware it is signed with the release key, so a debug build
  fails with `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`, and `adb uninstall` removes only the update, not the
  copy in `/system`. Flash a firmware with your debug build once (`scripts/build-rom.sh`, see the README). After that,
  `adb install -r` installs your next debug builds.
- After `adb install -r`, run `adb shell am force-stop com.themoon.y1`: HOME starts the new build. Do not reboot to
  restart it. An update with the same `versionCode` as the system copy is dropped at reboot.
- Menus: `adb shell input keyevent 21` / `22` turn the wheel and `23` is the centre button. The app times the Back
  presses itself, so `input keyevent 4` does not work as Back. Send the top button as a real press:
  `sendevent /dev/input/event3 1 158 1`, `sendevent /dev/input/event3 0 0 0`, about 150 ms later the same with `0` in
  place of the last `1` of the first line.
- Check which row has focus (a screenshot) before a scripted centre press or hold. A hold on Now Playing changes
  My Favorites.
- Injected wheel steps do not reach the games. Ask a person to try the real wheel there.
- Screenshots: `adb shell screencap -p /data/local/tmp/s.png`, then `adb pull`. Wake the screen first
  (`input keyevent 26`), else the picture is black. Copy files off the player with `adb pull`, not
  `adb shell cat > file`: the adb shell of Android 4.4 turns LF into CRLF.
- The app's `Log` lines do not show in `adb logcat` on the Y2. Check the result in the device state instead: shared
  preferences, `getprop`, `settings get`, files on the card. `adb shell am broadcast -a com.themoon.y1.RESCAN_LIBRARY`
  starts a library scan.
- Do not write to `/system` (remount, push to `priv-app` or `/system/lib`) without asking the owner of the player.
- In the pull request, say what you tested on which player and what you did not.

## Commits and pull requests

- Titles follow Conventional Commits, in plain words: `fix(tags): keep genre text after a number as it is written`.
- One change for each pull request. The description starts with `Closes #N.` when there is an issue, then a few short
  paragraphs: what changes for the user, any decision a reviewer should know, and what was tested on a player.
- Release notes: `.agents/skills/release-notes/` and `RELEASING.md`.

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
