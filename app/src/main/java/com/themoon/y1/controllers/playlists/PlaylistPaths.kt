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

    /** True when [a] and [b] are the same file after the links are resolved; false when that cannot be told. */
    fun sameFile(a: java.io.File, b: java.io.File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: java.io.IOException) {
        false
    }
}
