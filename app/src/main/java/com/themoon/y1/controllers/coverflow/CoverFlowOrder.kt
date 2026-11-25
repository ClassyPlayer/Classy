package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import com.themoon.y1.models.SortName

/**
 * Cover Flow order of the iPod classic: albums sorted by album artist (the track artist when the album
 * artist is empty), then by album title, both in [SortName] order (a leading "The " and case are ignored,
 * and accented letters sort next to their base letters).
 */
object CoverFlowOrder {

    fun sort(albums: MutableList<SongItem>) {
        val order = SortName.order()
        albums.sortWith(compareBy(order) { a: SongItem -> artistOf(a) }.thenBy(order) { it.album })
    }

    /** The artist that Cover Flow sorts by: the album artist, or the track artist when the album artist is empty. */
    fun artistOf(song: SongItem): String =
        if (song.albumArtist.isNotBlank()) song.albumArtist else song.artist
}
