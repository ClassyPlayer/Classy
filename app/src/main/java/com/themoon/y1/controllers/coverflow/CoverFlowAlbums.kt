package com.themoon.y1.controllers.coverflow

import android.content.SharedPreferences
import com.themoon.y1.Prefs
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.ReleaseTypes
import com.themoon.y1.models.SongItem

/**
 * The albums that Cover Flow shows (Settings → Menus → Cover Flow): all of them by default, or without the singles
 * and EPs ([ReleaseTypes]). Only Cover Flow uses this filter: Music → Albums and Artists keep every album.
 */
object CoverFlowAlbums {

    /** The values of Settings → Cover Flow → Untagged Singles: an album with this many tracks or fewer is a single. */
    val UNTAGGED_SINGLE_TRACKS = intArrayOf(0, 1, 3, 5)
    val UNTAGGED_SINGLE_LABELS = arrayOf("Off", "1 Track", "Up to 3 Tracks", "Up to 5 Tracks")

    data class Filter(val showSingles: Boolean, val showEps: Boolean, val untaggedSingleMaxTracks: Int) {
        val showsAll: Boolean get() = showSingles && showEps

        fun shows(primary: ReleaseTypes.Primary?): Boolean = when (primary) {
            ReleaseTypes.Primary.SINGLE -> showSingles
            ReleaseTypes.Primary.EP -> showEps
            else -> true
        }

        companion object {
            val ALL = Filter(showSingles = true, showEps = true, untaggedSingleMaxTracks = 0)

            fun of(prefs: SharedPreferences): Filter {
                val showSingles = Prefs.COVER_FLOW_SINGLES.get(prefs)
                return Filter(
                    showSingles = showSingles,
                    showEps = Prefs.COVER_FLOW_EPS.get(prefs),
                    // The track count only counts while singles are hidden (the row is grey otherwise)
                    untaggedSingleMaxTracks = if (showSingles) 0 else untaggedSingleTracks(Prefs.COVER_FLOW_UNTAGGED_SINGLES.get(prefs))
                )
            }
        }
    }

    /** The track limit of the Untagged Singles step [index] (0 = Off). */
    fun untaggedSingleTracks(index: Int): Int = UNTAGGED_SINGLE_TRACKS.getOrElse(index) { 0 }

    /** The Untagged Singles step that [index] stands for: [index] when it is one, else Off. */
    fun validUntaggedStep(index: Int): Int = if (index in UNTAGGED_SINGLE_TRACKS.indices) index else 0

    /** The value of the Cover Flow row in Settings: which albums show. */
    fun summary(filter: Filter): String = when {
        filter.showsAll -> "All Albums"
        !filter.showSingles && !filter.showEps -> "Albums Only"
        !filter.showSingles -> "No Singles"
        else -> "No EPs"
    }

    /**
     * The first song of each album of [songs] (in their order) that [filter] shows. Songs with no album title are
     * left out, as before. Grouped by [AlbumKey], as the Cover Flow list is.
     */
    fun select(songs: List<SongItem>, filter: Filter): List<SongItem> {
        val albums = LinkedHashMap<String, MutableList<SongItem>>()
        for (song in songs) {
            if (song.album.isBlank()) continue
            albums.getOrPut(AlbumKey.of(song)) { ArrayList(1) }.add(song)
        }
        val result = ArrayList<SongItem>(albums.size)
        for (tracks in albums.values) {
            if (filter.showsAll || filter.shows(ReleaseTypes.primaryOfAlbum(tracks, filter.untaggedSingleMaxTracks))) {
                result.add(tracks[0])
            }
        }
        return result
    }
}
