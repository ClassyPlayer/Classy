package com.themoon.y1.controllers.playlists

/** Paths of the songs in an M3U playlist. */
object PlaylistPaths {

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

    /** True when [a] and [b] are the same file after the links are resolved; false when that cannot be told. */
    fun sameFile(a: java.io.File, b: java.io.File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: java.io.IOException) {
        false
    }
}
