package com.themoon.y1.controllers.playlists

import com.themoon.y1.managers.MediaLibraryManager

/** Paths of the songs in an M3U playlist. */
object PlaylistPaths {

    /**
     * True when [name] is the name of a playlist file: .m3u or .m3u8 (any case), and not hidden
     * ([MediaLibraryManager.isHiddenName]). macOS writes a "._<name>" AppleDouble file (no playlist) beside each file
     * that it copies to the microSD card.
     */
    fun isPlaylistName(name: String): Boolean {
        val lower = name.lowercase()
        return !MediaLibraryManager.isHiddenName(name) && (lower.endsWith(".m3u") || lower.endsWith(".m3u8"))
    }

    /** The playlist files ([isPlaylistName]) of each folder of [dirs], A to Z (ignoring case). */
    fun playlistsIn(dirs: List<java.io.File>): List<java.io.File> {
        val files = ArrayList<java.io.File>()
        for (dir in dirs) {
            for (f in dir.listFiles() ?: emptyArray()) {
                if (isPlaylistName(f.name) && f.isFile) files.add(f)
            }
        }
        return files.sortedWith { f1, f2 -> f1.name.compareTo(f2.name, ignoreCase = true) }
    }

    /**
     * [path] without "." parts and empty parts, and with each ".." part removed together with the part before it.
     * Only the text changes: links stay as they are, because the library keys are absolute paths, not canonical paths.
     */
    fun normalize(path: String): String {
        val absolute = path.startsWith("/")
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when {
                part.isEmpty() || part == "." -> {}
                part == ".." && parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.size - 1)
                // Above the root there is nothing to go to; a relative path keeps the ".."
                part == ".." && absolute -> {}
                else -> parts.add(part)
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    /**
     * The file of a playlist [entry] (backslashes as in Windows playlists work). A relative entry is first read from
     * the playlist's folder [playlistDir] (as playlists made on a computer are), then from [musicRoot] when it is not there.
     */
    fun resolve(entry: String, playlistDir: java.io.File?, musicRoot: java.io.File): java.io.File {
        val path = entry.replace("\\", "/")
        val file = java.io.File(path)
        if (file.isAbsolute) return file
        if (playlistDir != null) {
            val nextToPlaylist = java.io.File(playlistDir, path)
            if (nextToPlaylist.exists()) return nextToPlaylist
        }
        return java.io.File(musicRoot, path)
    }

    /**
     * The list label of each playlist in [files]: the name without the extension, and "Name (storage)" when another
     * playlist has the same name (ignoring case), so that the playlists of the card and internal storage can be told apart.
     */
    fun labels(files: List<java.io.File>, storage: (java.io.File) -> String): List<String> {
        val names = files.map { it.nameWithoutExtension }
        val counts = names.groupingBy { it.lowercase() }.eachCount()
        return files.mapIndexed { i, f -> if (counts.getValue(names[i].lowercase()) > 1) "${names[i]} (${storage(f)})" else names[i] }
    }

    /** True when [file] has text and its last character is not a line break (\n or \r). False for a missing or empty file. */
    fun endsWithoutLineBreak(file: java.io.File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            val last = raf.read()
            return last != '\n'.code && last != '\r'.code
        }
    }

    /** True when [a] and [b] are the same file after the links are resolved; false when that cannot be told. */
    fun sameFile(a: java.io.File, b: java.io.File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: java.io.IOException) {
        false
    }
}
