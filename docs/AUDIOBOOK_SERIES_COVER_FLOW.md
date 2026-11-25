# Audiobook Series Cover Flow

An optional, folder-based Cover Flow for audiobooks on the Y2 and Y1 flavors.

## Enable it

Open **Settings → Library → Audiobook Series Cover Flow** and turn it on. Then open **Audiobooks → Series Cover Flow**.
The setting is off by default; when it is off, the Audiobooks menu has no Series Cover Flow row and Continue groups a book by its album tag, as before.
The audiobook speed, the Now Playing rows and shuffle (below) apply to every audiobook, with the setting on or off.

## Folder rules

- A folder directly inside an Audiobooks root that contains book subfolders is a **series**. The Cover Flow shows one cover for it (the first book's first file) and the status bar shows the series name inside it.
- A folder without book subfolders is **one standalone book**. A book never shows its individual audio files.
- Loose audio files directly in an Audiobooks root stay individual entries.
- Inside a series, books are sorted by the first number in the folder name, then alphabetically. The last played book opens first.
- A book's files play in this order: folder (Disc 1 before Disc 2), then natural file name (2 before 10). Track tags do not change the order.
- The most recently played series comes first at the top level.

## Playback and resume

- A book queues all its files and resumes at its saved chapter and position (`book_pos_<path>`); each book remembers its own current file.
- A series remembers the last book played in it.
- **Continue** only uses files from the Audiobooks folders, so music played afterwards never replaces the last audiobook. With the setting on, Continue queues the files of the book (its folder or .m4b), as the Cover Flow does.
- Shuffle is never used for audiobooks: a book always plays in order.

## Audiobook speed

While an audiobook plays, hold the centre button and open **Audiobook Speed** to choose 1.0x, 1.2x, 1.5x or 2.0x.
The speed is saved and applies to audiobooks only; music and podcasts keep the speed of Settings → Playback Speed.

## Audiobook menu (hold the middle button while an audiobook plays)

For audiobooks the menu shows three rows at the top, above the rows of every song (Queue, Play On and the others); music is unchanged:

- **Audiobook Speed**: the speed of every audiobook (music is not changed).
- **Chapters**: the chapters inside the .m4b that plays (a row jumps to it); for a book of several files, the files (a row plays that file from its start).
- **Sleep Timer**: 5, 10, 15, 30, 45, 60, 90 or 120 minutes (the most). When it ends, the book is paused, the place is saved, and the device is powered off. **Stop Sleep Timer** cancels it.

## Books

- An `.m4b` file is one book, loose in an Audiobooks folder or inside a series folder.
- A folder with more than one book (sub-folders or .m4b files) is a series. A folder with one book only shows as that book, not as a series.
