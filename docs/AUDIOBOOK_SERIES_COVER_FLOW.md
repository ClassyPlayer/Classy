# Audiobook Series Cover Flow

An optional, folder-based Cover Flow for audiobooks on the Y2 and Y1 flavors.

## Enable it

Open **Settings → Library → Audiobook Series Cover Flow** and turn it on. Then open **Audiobooks → Series Cover Flow**.
The setting is off by default; when it is off, the Audiobooks menu and browsing are unchanged.

## Folder rules

- A folder directly inside an Audiobooks root that contains book subfolders is a **series**. The Cover Flow shows one cover for it (the first book's first file) and the status bar shows the series name inside it.
- A folder without book subfolders is **one standalone book**. A book never shows its individual audio files.
- Loose audio files directly in an Audiobooks root stay individual entries.
- Inside a series, books are sorted by the first number in the folder name, then alphabetically. The last played book opens first.
- A book's files play in this order: folder (Disc 1 before Disc 2), files with a track number by number, files without one after them, then natural file name.
- The most recently played series comes first at the top level.

## Playback and resume

- A book queues all its files and resumes at its saved chapter and position (`book_pos_<path>`); each book remembers its own current file.
- A series remembers the last book played in it.
- **Continue** only uses files from the Audiobooks folders, so music played afterwards never replaces the last audiobook.

## Audiobook speed

While an audiobook plays, hold the centre button and open **Audiobook Speed** to choose 1.0x, 1.2x, 1.5x or 2.0x.
The speed is saved and applies to audiobooks only; music and podcasts keep the speed of Settings → Playback Speed.
