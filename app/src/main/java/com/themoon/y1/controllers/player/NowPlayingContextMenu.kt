package com.themoon.y1.controllers.player

import com.themoon.y1.MainActivity
import com.themoon.y1.cast.PlayOnMenu
import com.themoon.y1.StoragePaths
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.controllers.settings.SettingsController
import com.themoon.y1.views.Notice
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.LibrarySongs
import com.themoon.y1.managers.M4bChapterParser
import com.themoon.y1.managers.Plurals
import com.themoon.y1.managers.SleepTimer
import com.themoon.y1.models.ArtistNames
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.showNowPlaying
import java.io.File

/**
 * The menu of a held centre button on Now Playing: an action screen (a list that slides in, Back comes back to
 * Now Playing). Replicates iPod Classic context actions:
 * - Queue (the play queue around the playing track; a row plays its track; only for a queue of more than 1 track)
 * - Add/Remove from Favorites
 * - Add to On-The-Go playlist
 * - Add to Playlist
 * - Browse Album / Browse Artist
 * - Audiobook Speed, Chapters and Sleep Timer (audiobooks only, at the top; music is not changed)
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
        val main = MainActivity.instance
        var menuKey = -1
        fun spec(): ActionScreens.Spec {
            val rows = ArrayList<ActionScreens.Action>()
            // Audiobooks: Speed, Chapters and Sleep Timer first, then the rows of every song (Queue, Play On...).
            // A new speed or timer shows in its row at once (Back comes back to this menu).
            if (main != null && StoragePaths.isUnderAudiobooks(songFile.absolutePath)) {
                rows += audiobookRows(main, songFile) { if (menuKey >= 0) ActionScreens.replace(menuKey, spec()) }
            }
            if (main != null && main.currentPlaylist.size > 1) {
                rows += ActionScreens.Action(localize("Queue"), opensList = true) { showQueue(main) }
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
            if (main != null) {
                rows += ActionScreens.Action(PlayOnMenu.rowLabel(main), opensList = true) { PlayOnMenu.open(main, fromNowPlaying = true) }
            }
            return ActionScreens.Spec(localize("Now Playing"), actions = rows)
        }
        menuKey = ActionScreens.open(spec())
    }

    private fun speedIndex(speed: Float): Int =
        SettingsController.SPEED_VALUES.indexOfFirst { Math.abs(it - speed) < 0.01f }.coerceAtLeast(0)

    /** "45 minutes", translated. */
    private fun minutesText(main: MainActivity, minutes: Int): String = Plurals.format(main::t, "%d minutes", minutes)

    /** The rows of an audiobook: the speed, the chapters and the sleep timer. [changed]: redraws them after a change. */
    private fun audiobookRows(main: MainActivity, songFile: File, changed: () -> Unit): List<ActionScreens.Action> {
        val label = SettingsController.SPEED_LABELS[speedIndex(AudioPlayerManager.getInstance().audiobookSpeed())]
        val sleep = if (SleepTimer.isActive()) minutesText(main, SleepTimer.minutesLeft()) else main.t("Off")
        return listOf(
            ActionScreens.Action(main.t("Audiobook Speed") + ": " + main.t(label), opensList = true, key = "audiobook_speed") {
                showAudiobookSpeed(main, changed)
            },
            ActionScreens.Action(main.t("Chapters"), opensList = true) { showChapters(main, songFile) },
            ActionScreens.Action(main.t("Sleep Timer") + ": " + sleep, opensList = true, key = "sleep_timer") {
                showSleepTimer(main, changed)
            }
        )
    }

    /** True while [file] is still the queue entry that plays: a chapter row of another file must not act. */
    private fun stillPlaying(main: MainActivity, file: File): Boolean =
        main.currentPlaylist.getOrNull(main.currentIndex)?.absolutePath == file.absolutePath

    /** The chapter of [positionMs]: the last one that starts at or before it (0 when none). */
    internal fun chapterIndexAt(starts: List<Int>, positionMs: Int): Int {
        var found = 0
        for (i in starts.indices) if (starts[i] <= positionMs) found = i else break
        return found
    }

    /**
     * The chapters: the chapters inside the .m4b that plays (a row seeks to it), else the files of the book
     * (a row plays that file from its start).
     */
    private fun showChapters(main: MainActivity, songFile: File) {
        val player = AudioPlayerManager.getInstance()
        val embedded = M4bChapterParser.extractChapters(songFile)
        val rows = ArrayList<ActionScreens.Action>()
        if (embedded.timestamps.isNotEmpty()) {
            val starts = embedded.timestamps.toList()
            val current = chapterIndexAt(starts, player.getCurrentPosition())
            for ((i, start) in starts.withIndex()) {
                val title = (embedded.chaptersMap[start] ?: "").removePrefix("\uD83D\uDD16 ").ifBlank { "${i + 1}" }
                rows += ActionScreens.Action(title, checked = i == current, key = "chapter$i") {
                    // The times are those of songFile: after the next file started they would seek the wrong file
                    if (stillPlaying(main, songFile)) player.seekToMs(start.toLong())
                }
            }
        } else {
            val files = main.currentPlaylist.toList()
            for ((i, f) in files.withIndex()) {
                rows += ActionScreens.Action(f.nameWithoutExtension, checked = i == main.currentIndex, key = "chapter$i") {
                    // A chapter starts at its beginning, not at the bookmark of an earlier listen
                    if (main.currentPlaylist.getOrNull(i)?.absolutePath == f.absolutePath) player.jumpTo(i, fromStart = true)
                }
            }
        }
        ActionScreens.open(
            ActionScreens.Spec(main.t("Chapters"), info = if (rows.isEmpty()) listOf(main.t("No chapters found")) else emptyList(),
                actions = rows, focusChecked = true)
        )
    }

    /** The sleep timer: the time left on top, 5 minutes to 2 hours, and Stop Sleep Timer below while one runs. */
    private fun showSleepTimer(main: MainActivity, changed: () -> Unit) {
        val rows = ArrayList<ActionScreens.Action>()
        for (minutes in SleepTimer.CHOICES) {
            rows += ActionScreens.Action(minutesText(main, minutes)) {
                SleepTimer.start(main, minutes)
                changed()
                Notice.show(main, main.t("Sleep timer set for ") + minutesText(main, minutes))
            }
        }
        val info = ArrayList<String>()
        if (SleepTimer.isActive()) {
            info += main.t("Time left") + ": " + minutesText(main, SleepTimer.minutesLeft())
            rows += ActionScreens.Action(main.t("Stop Sleep Timer")) {
                SleepTimer.cancel()
                changed()
                Notice.show(main, main.t("Sleep timer cancelled"))
            }
        }
        ActionScreens.open(ActionScreens.Spec(main.t("Sleep Timer"), info = info, actions = rows))
    }

    /** The speeds of the audiobooks; the one that is set has the check mark, and a row sets it and goes back. */
    private fun showAudiobookSpeed(main: MainActivity, changed: () -> Unit) {
        val current = speedIndex(AudioPlayerManager.getInstance().audiobookSpeed())
        val rows = SettingsController.SPEED_LABELS.indices.map { i ->
            ActionScreens.Action(main.t(SettingsController.SPEED_LABELS[i]), checked = i == current) {
                AudioPlayerManager.getInstance().setAudiobookSpeed(SettingsController.SPEED_VALUES[i])
                changed()
                Notice.show(main, main.t("Audiobook Speed") + ": " + main.t(SettingsController.SPEED_LABELS[i]))
            }
        }
        ActionScreens.open(ActionScreens.Spec(main.t("Audiobook Speed"), actions = rows, focusChecked = true))
    }

    /** The rows of the Queue screen at most: a queue of thousands (Shuffle Songs) would make one view for each. */
    internal const val QUEUE_WINDOW = 100

    /** The queue entries that show, [QUEUE_WINDOW] around [center]; a short queue shows whole. */
    internal fun queueWindow(size: Int, center: Int, rows: Int = QUEUE_WINDOW): IntRange {
        val start = (center - rows / 2).coerceIn(0, maxOf(0, size - rows))
        return start until minOf(size, start + rows)
    }

    /** "Later Tracks": the [rows] entries right after [window] (fewer at the end of the queue). No entry shows again. */
    internal fun laterPage(window: IntRange, size: Int, rows: Int = QUEUE_WINDOW): IntRange =
        (window.last + 1) until minOf(size, window.last + 1 + rows)

    /** "Earlier Tracks": the [rows] entries right before [window] (fewer at the start of the queue). */
    internal fun earlierPage(window: IntRange, rows: Int = QUEUE_WINDOW): IntRange =
        maxOf(0, window.first - rows) until window.first

    /**
     * The key of the row of queue entry [index]: its place and its file. Two tracks with one title stay apart, and
     * after the queue changed, a key does not find another track at the same place.
     */
    internal fun queueKey(index: Int, file: File): String = "queue$index:${file.path}"

    /**
     * The rows of the Queue for the entries [window] of [queue] ([playing] has the check mark): a row plays its entry
     * ([jump]). The Earlier/Later rows open the page before/after ([page]); each has the key of the entry that it
     * then selects (the entry next to this page), so the user reads the queue in sequence.
     */
    internal fun queueRows(
        queue: List<File>, playing: Int, window: IntRange, t: (String) -> String, title: (File) -> String,
        jump: (Int) -> Unit, page: (IntRange) -> Unit
    ): List<ActionScreens.Action> {
        val rows = ArrayList<ActionScreens.Action>()
        if (window.first > 0) {
            val key = queueKey(window.first - 1, queue[window.first - 1])
            rows += ActionScreens.Action(t("Earlier Tracks"), key = key, closes = false) { page(earlierPage(window)) }
        }
        for (i in window) {
            rows += ActionScreens.Action(title(queue[i]), checked = i == playing, closes = false, key = queueKey(i, queue[i])) {
                jump(i)
            }
        }
        if (window.last < queue.size - 1) {
            val key = queueKey(window.last + 1, queue[window.last + 1])
            rows += ActionScreens.Action(t("Later Tracks"), key = key, closes = false) { page(laterPage(window, queue.size)) }
        }
        return rows
    }

    /**
     * The open Queue screen, the entries it shows, and the queue it was built from: another playing track, size or
     * track at that place builds the rows again.
     */
    private class Shown(val key: Int, val window: IntRange, val index: Int, val size: Int, val playing: File?)

    private var queue: Shown? = null

    private fun queueSpec(main: MainActivity, window: IntRange): ActionScreens.Spec {
        val rows = queueRows(main.currentPlaylist, main.currentIndex, window, main::t, ::queueTitle, jump = { i ->
            // The Queue cannot show again: no rows for it at the track change of the jump
            val shown = queue
            queue = null
            AudioPlayerManager.getInstance().jumpTo(i)
            // Straight back to Now Playing: its menu under this list was built for the song that played before
            main.showNowPlaying()
            shown?.let { ActionScreens.forget(it.key) }
        }, page = { queueMove(main, it) })
        return ActionScreens.Spec(main.t("Queue"), actions = rows, onCancel = ::closeQueue, focusChecked = true)
    }

    /** Back closed the Queue: it cannot show again, so it does not follow the playing track any more. */
    private fun closeQueue() {
        queue?.let { ActionScreens.forget(it.key) }
        queue = null
    }

    private fun queueTitle(f: File): String =
        LibrarySongs.songByPath(f.absolutePath)?.title?.takeIf { it.isNotEmpty() } ?: f.nameWithoutExtension

    private fun shownOf(main: MainActivity, key: Int, window: IntRange) =
        Shown(key, window, main.currentIndex, main.currentPlaylist.size, main.currentPlaylist.getOrNull(main.currentIndex))

    /** Shows the page [window] in the open Queue. */
    private fun queueMove(main: MainActivity, window: IntRange) {
        val shown = queue ?: return
        queue = shownOf(main, shown.key, window)
        ActionScreens.replace(shown.key, queueSpec(main, window))
    }

    /** The playing queue around the playing track, which is selected; a row plays that track. */
    private fun showQueue(main: MainActivity) {
        val window = queueWindow(main.currentPlaylist.size, main.currentIndex)
        queue = shownOf(main, ActionScreens.open(queueSpec(main, window)), window)
    }

    /**
     * MainActivity.updatePlayerUI: an open Queue screen follows the playing track (check mark) and a changed queue
     * (rows). When the track that played is still at its place (the next track plays, or tracks were added), the
     * page stays: the rows keep their keys and the selected track stays selected. Else the page shows the playing
     * track again.
     */
    fun refreshQueue() {
        val shown = queue ?: return
        val main = MainActivity.instance ?: return
        val list = main.currentPlaylist
        if (main.currentIndex == shown.index && list.size == shown.size && list.getOrNull(main.currentIndex) === shown.playing) return
        // The Queue cannot show again (forgotten, or a key went to another screen): it stops here
        val nav = NavigationManager.getInstance()
        val route = ActionScreens.route(shown.key)
        if (ActionScreens.spec(shown.key) == null || (nav.currentState != route && !nav.backStackHas { it == route })) {
            closeQueue()
            return
        }
        val samePlace = shown.playing != null && list.getOrNull(shown.index) === shown.playing
        val window = if (samePlace && shown.window.last < list.size) shown.window else queueWindow(list.size, main.currentIndex)
        // Also while another screen covers the Queue: Back shows the retained spec again (replace redraws only a showing screen)
        queue = shownOf(main, shown.key, window)
        ActionScreens.replace(shown.key, queueSpec(main, window))
    }
}
