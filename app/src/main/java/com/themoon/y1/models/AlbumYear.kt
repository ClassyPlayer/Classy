package com.themoon.y1.models

/** "Sort by Year" on the album lists: the four digits of a song's year tag, and the row text "<year> - <Album>". */
object AlbumYear {
    const val PREF = "albums_by_year"

    /** "2004" from "2004" or "2004-05-03"; null for "Unknown Year" or anything else. */
    fun of(year: String): String? = year.trim().take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }

    fun label(year: String, album: String): String = of(year)?.let { "$it - $album" } ?: album
}
