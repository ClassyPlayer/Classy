package com.themoon.y1.models

import java.io.File

/**
 * The order of the songs of an album: by track number (a song with no number comes first), then by title, ignoring case.
 * The album screen lists an album in this order, and the album hold menu (Add to Queue, Add to Playlist) adds it in it.
 */
object AlbumTrackOrder {
    private fun <T> by(trackNumber: (T) -> Int, title: (T) -> String): Comparator<T> =
        compareBy<T> { trackNumber(it) }.thenComparator { a, b -> title(a).compareTo(title(b), ignoreCase = true) }

    /** [trackNumbers]: the track number of each absolute path. */
    fun songs(trackNumbers: Map<String, Int>): Comparator<SongItem> =
        by({ trackNumbers[it.file.absolutePath] ?: 0 }, { it.title })

    /** [trackNumbers]: the track number of each absolute path; [titleOf]: the title of each file. */
    fun files(trackNumbers: Map<String, Int>, titleOf: (File) -> String): Comparator<File> =
        by({ trackNumbers[it.absolutePath] ?: 0 }, titleOf)
}
