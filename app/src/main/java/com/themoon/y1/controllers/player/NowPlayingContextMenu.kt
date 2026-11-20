package com.themoon.y1.controllers.player

import com.themoon.y1.MainActivity
import com.themoon.y1.cast.PlayOnMenu
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.LibrarySongs
import com.themoon.y1.models.ArtistNames
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import java.io.File

/**
 * The menu of a held centre button on Now Playing: an action screen (a list that slides in, Back comes back to
 * Now Playing). Replicates iPod Classic context actions:
 * - Add/Remove from Favorites
 * - Add to On-The-Go playlist
 * - Add to Playlist
 * - Browse Album / Browse Artist
 * - Play On (the speaker list; with the speaker name while a speaker plays)
 * Only actions on the current song and Play On: the display options (Spectrum, Total Time) are in Settings.
 * A row closes the menu, then does its action (ActionScreens.choose), so Browse Album goes from Now Playing.
 */
object NowPlayingContextMenu {

    fun show(
        songFile: File,
        songItem: SongItem?,
        isFavorite: Boolean,
        onToggleFavorite: () -> Unit,
        onAddToOnTheGo: (file: File) -> Unit,
        onAddToPlaylist: (file: File) -> Unit,
        onBrowseAlbum: (albumName: String) -> Unit,
        onBrowseArtist: (artistName: String) -> Unit,
        localize: (String) -> String
    ) {
        val rows = ArrayList<ActionScreens.Action>()
        MainActivity.instance?.let { m ->
            if (m.currentPlaylist.size > 1) rows += ActionScreens.Action(localize("Queue"), opensList = true) { showQueue(m) }
        }
        rows += ActionScreens.Action(if (isFavorite) localize("Remove from Favorites") else localize("Add to Favorites")) {
            onToggleFavorite()
        }
        rows += ActionScreens.Action(localize("Add to On-The-Go")) { onAddToOnTheGo(songFile) }
        rows += ActionScreens.Action(localize("Add to Playlist")) { onAddToPlaylist(songFile) }
        if (songItem != null) {
            if (songItem.album.isNotEmpty()) {
                rows += ActionScreens.Action(localize("Browse Album")) { onBrowseAlbum(songItem.album) }
            }
            val artist = if (songItem.albumArtist.isNotEmpty()) songItem.albumArtist else songItem.artist
            if (artist.isNotEmpty()) {
                // "A;B" opens the songs of A, as the Artists list shows A and B on their own
                val firstArtist = ArtistNames.split(artist).firstOrNull() ?: artist
                rows += ActionScreens.Action(localize("Browse Artist")) { onBrowseArtist(firstArtist) }
            }
        }
        // Play On: the speaker list of Main menu → Play On opens over this menu (Back comes back to it)
        val main = MainActivity.instance
        if (main != null) {
            rows += ActionScreens.Action(PlayOnMenu.rowLabel(main), opensList = true) { PlayOnMenu.open(main, fromNowPlaying = true) }
        }
        ActionScreens.open(ActionScreens.Spec(localize("Now Playing"), actions = rows))
    }

    /** The rows of the Queue screen at most: a queue of thousands (Shuffle Songs) would make one view for each. */
    internal const val QUEUE_WINDOW = 100

    /** The queue entries that show, [QUEUE_WINDOW] around [center]; a short queue shows whole. */
    internal fun queueWindow(size: Int, center: Int, rows: Int = QUEUE_WINDOW): IntRange {
        val start = (center - rows / 2).coerceIn(0, maxOf(0, size - rows))
        return start until minOf(size, start + rows)
    }

    /** The open Queue screen, and the queue it was built from: another playing track, size or track at that place rebuilds it. */
    private class Shown(val key: Int, val index: Int, val size: Int, val playing: File?)

    private var queue: Shown? = null

    /** Rows for the entries around [center]; "Earlier/Later Tracks" rows move the window by one page. */
    private fun queueSpec(main: MainActivity, center: Int): ActionScreens.Spec {
        val list = main.currentPlaylist
        val window = queueWindow(list.size, center)
        val rows = ArrayList<ActionScreens.Action>()
        if (window.first > 0) rows += ActionScreens.Action(main.t("Earlier Tracks"), key = "queueEarlier", closes = false) {
            queueMove(main, window.first - QUEUE_WINDOW / 2)
        }
        for (i in window) {
            val f = list[i]
            val title = LibrarySongs.songByPath(f.absolutePath)?.title?.takeIf { it.isNotEmpty() } ?: f.nameWithoutExtension
            // The key is the place in the queue: two tracks with one title stay apart when the list is shown again
            rows += ActionScreens.Action(title, checked = i == main.currentIndex, closes = false, key = "queue$i") {
                AudioPlayerManager.getInstance().jumpTo(i)
                // Straight back to Now Playing: its menu under this list was built for the song that played before
                NavigationManager.getInstance().popBackStackTo { it == ScreenState.Player }
            }
        }
        if (window.last < list.size - 1) rows += ActionScreens.Action(main.t("Later Tracks"), key = "queueLater", closes = false) {
            queueMove(main, window.last + QUEUE_WINDOW / 2)
        }
        return ActionScreens.Spec(main.t("Queue"), actions = rows, focusChecked = true)
    }

    private fun shownOf(main: MainActivity, key: Int) =
        Shown(key, main.currentIndex, main.currentPlaylist.size, main.currentPlaylist.getOrNull(main.currentIndex))

    private fun queueMove(main: MainActivity, center: Int) {
        val shown = queue ?: return
        ActionScreens.replace(shown.key, queueSpec(main, center))
    }

    /** The playing queue around the playing track, which is selected; a row plays that track. */
    private fun showQueue(main: MainActivity) {
        queue = shownOf(main, ActionScreens.open(queueSpec(main, main.currentIndex)))
    }

    /** MainActivity.updatePlayerUI: an open Queue screen follows the playing track (check mark) and a changed queue (rows). */
    fun refreshQueue() {
        val shown = queue ?: return
        val main = MainActivity.instance ?: return
        val list = main.currentPlaylist
        if (main.currentIndex == shown.index && list.size == shown.size && list.getOrNull(main.currentIndex) === shown.playing) return
        if (ActionScreens.spec(shown.key) == null) {
            queue = null // forgotten
            return
        }
        // Also while another screen covers the Queue: Back shows the retained spec again (replace redraws only a showing screen)
        queue = shownOf(main, shown.key)
        ActionScreens.replace(shown.key, queueSpec(main, main.currentIndex))
    }
}
