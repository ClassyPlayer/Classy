# Audiobook Series Cover Flow

This source modification adds an optional folder-based audiobook Cover Flow for the Y2 and Y1 flavors.

## Enable it

Open **Settings → Library → Audiobook Series Cover Flow** and turn it on. Then open **Audiobooks → Series Cover Flow**.

## Folder rules

- A directory directly inside an Audiobooks storage root that contains child directories with scanned audiobook files is treated as a series.
- Each child directory is treated as a book. The series Cover Flow uses the first book's first naturally sorted audio file as its cover representative.
- A directory without child book directories is treated as one standalone audiobook.
- Loose audio files directly inside an Audiobooks root remain individual entries.
- Inside a series, books are sorted by the first number in their folder name, then alphabetically. A remembered book is placed first when reopening that series.
- The most recently played series is promoted in the root Cover Flow.

The setting is off by default. When it is off, the existing Audiobooks menu and browsing behavior are unchanged.

## Playback and resume

Book selection reuses the existing audiobook playback queue and `book_pos_<path>` bookmark keys. The currently heard file updates the last-book path for its containing series, so reopening that series starts Cover Flow at the remembered book. The ordinary global audiobook Continue behavior remains intact.

## Build on Windows without Android Studio or GitHub

Extract the supplied build kit on Windows, install Ubuntu through WSL2 once, then double-click `BUILD-Y2-WINDOWS.bat`. The helper installs Java 17 and the Android SDK command-line components, runs `testY2DebugUnitTest` and `assembleY2Debug`, then packages the resulting APK into a Y2 `rom_y2.zip` using `scripts/build-rom.sh`. Outputs are copied to the `OUTPUT` folder. See `START-HERE.txt` for setup and important backup warnings.

The full Android build and on-device behavior must still be validated by the local build and device testing. The ROM build is not a backup of the player's existing app-private data.
