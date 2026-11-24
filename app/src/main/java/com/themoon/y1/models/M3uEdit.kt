package com.themoon.y1.models

import java.io.File

/** The pure rules for changing an .m3u playlist: remove one entry, and the songs that are not in it yet. */
object M3uEdit {

    /** A line that names a song (not empty, not a "#" comment or tag). */
    fun isEntry(line: String): Boolean = line.trim().let { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * [lines] without the entry that is the [occurrence]-th (0 = the first) entry for which [matches] is true, and
     * without the `#EXT...` lines right before it (`#EXTINF`, `#EXTVLCOPT`, `#EXTGRP`: they belong to that entry; not
     * `#EXTM3U`). Null when there is no such entry. [matches] gets the entry line as it is in the file.
     */
    fun removeEntry(lines: List<String>, occurrence: Int, matches: (String) -> Boolean): List<String>? {
        var seen = 0
        for ((i, line) in lines.withIndex()) {
            if (!isEntry(line) || !matches(line)) continue
            if (seen++ < occurrence) continue
            var from = i
            while (from > 0 && isEntryTag(lines[from - 1])) from--
            return lines.subList(0, from) + lines.subList(i + 1, lines.size)
        }
        return null
    }

    /** A tag line of the entry under it (`#EXTINF`, `#EXTVLCOPT`, ...), not the `#EXTM3U` header. */
    private fun isEntryTag(line: String): Boolean =
        line.trim().let { it.startsWith("#EXT", ignoreCase = true) && !it.startsWith("#EXTM3U", ignoreCase = true) }

    /** The key of a song file for the duplicate check: its absolute path in lower case (FAT finds a file in any case). */
    fun key(file: File): String = file.absolutePath.lowercase()

    /**
     * The songs of [songs] to add to a playlist that has the songs [inPlaylist] ([key]s): each song once, and none that
     * is in it already. The order of [songs] stays.
     */
    fun newSongs(songs: List<File>, inPlaylist: Set<String>): List<File> {
        val seen = HashSet(inPlaylist)
        return songs.filter { seen.add(key(it)) }
    }
}
