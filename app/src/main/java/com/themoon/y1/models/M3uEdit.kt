package com.themoon.y1.models

import java.io.File

/** The pure rules for changing an .m3u playlist: remove one entry, and the songs that are not in it yet. */
object M3uEdit {

    /** A line that names a song (not empty, not a "#" comment or tag). */
    fun isEntry(line: String): Boolean = line.trim().let { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * [lines] without the entry that is the [occurrence]-th (0 = the first) entry for which [matches] is true, and
     * without the `#EXTINF` line right before it (its title and length belong to that entry). Null when there is no
     * such entry. [matches] gets the entry line as it is in the file (the caller resolves it to a file).
     */
    fun removeEntry(lines: List<String>, occurrence: Int, matches: (String) -> Boolean): List<String>? {
        var seen = 0
        for ((i, line) in lines.withIndex()) {
            if (!isEntry(line) || !matches(line)) continue
            if (seen++ < occurrence) continue
            val from = if (i > 0 && lines[i - 1].trim().startsWith("#EXTINF", ignoreCase = true)) i - 1 else i
            return lines.subList(0, from) + lines.subList(i + 1, lines.size)
        }
        return null
    }

    /**
     * The songs of [songs] to add to a playlist that has the songs [inPlaylist] (absolute paths): each song once, and
     * none that is in it already. The order of [songs] stays.
     */
    fun newSongs(songs: List<File>, inPlaylist: Set<String>): List<File> {
        val seen = HashSet(inPlaylist)
        return songs.filter { seen.add(it.absolutePath) }
    }
}
