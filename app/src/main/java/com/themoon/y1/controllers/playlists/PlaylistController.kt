package com.themoon.y1.controllers.playlists

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.adapters.SongListAdapter
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.managers.PlayHistoryManager
import com.themoon.y1.managers.Texts
import com.themoon.y1.models.M3uEdit
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.Notice
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.util.ArrayList

/**
 * Controller for M3U playlist browsing, song listing, creation, and editing:
 * - Lists M3U / M3U8 playlists in the `Playlists` folders (microSD card, internal storage; [StoragePaths.getPlaylistsDirs])
 * - Parses playlist track sequences without losing custom ordering
 * - Displays smart popup dialogs for "Add to Playlist" and "Delete Playlist"
 * - Handles track deletion from individual M3U playlists
 */
class PlaylistController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val listVirtualSongs: ListView?,
    private val musicRootFolder: File,
    private val isAudioFile: (File) -> Boolean,
    private val clickFeedback: () -> Unit,
    private val onPlaylistSelected: (m3uFile: File) -> Unit,
    private val localize: (String) -> String,
    /** Settings → Library → No Duplicates in Playlists: a song that a playlist has already is not added again. */
    private val noDuplicates: () -> Boolean = { false }
) : ScreenController {

    override val listScreen: ListScreen get() = screen

    private val screen = ListScreen(rootView.findViewById(R.id.container_browser_items), listVirtualSongs, clickFeedback)

    override fun onEnter(state: ScreenState) {
        when (state) {
            is ScreenState.Playlists -> buildPlaylistsUI()
            is ScreenState.M3uPlaylistSongs -> buildM3uSongsUI(state.m3uFile)
            is ScreenState.SmartPlaylistSongs -> buildSmartPlaylistUI(state)
            is ScreenState.Browser -> {
                when (state.mode) {
                    is BrowserMode.Playlists -> buildPlaylistsUI()
                    is BrowserMode.M3uSongs -> buildM3uSongsUI(state.mode.m3uFile)
                    else -> buildPlaylistsUI()
                }
            }
            else -> buildPlaylistsUI()
        }
    }

    override fun onExit() {
        screen.clear()
    }

    override fun onThemeChanged() {
        com.themoon.y1.navigation.NavigationManager.getInstance().currentState?.let { onEnter(it) }
    }

    /** The M3U files of each Playlists folder, A to Z. */
    private fun playlistFiles(): List<File> {
        val files = ArrayList<File>()
        for (dir in StoragePaths.getPlaylistsDirs()) {
            for (f in dir.listFiles() ?: emptyArray()) {
                val name = f.name.lowercase()
                if (f.isFile && (name.endsWith(".m3u") || name.endsWith(".m3u8"))) files.add(f)
            }
        }
        return files.sortedWith { f1, f2 -> f1.name.compareTo(f2.name, ignoreCase = true) }
    }

    /** The smart playlists, then the M3U files of the Playlists folders (A to Z). Opens at the last opened playlist. */
    fun buildPlaylistsUI() {
        val m3uFiles = playlistFiles()

        val rows = ArrayList<RowSpec>()
        // iPod smart playlists first
        for (kind in PlayHistoryManager.Kind.values()) {
            rows += RowSpec(smartPlaylistTitle(kind), onClick = {
                (activity as? com.themoon.y1.MainActivity)?.changeScreen(ScreenState.SmartPlaylistSongs(kind))
            })
        }
        val labels = PlaylistPaths.labels(m3uFiles, ::storageName)
        for ((i, m3u) in m3uFiles.withIndex()) {
            rows += RowSpec(labels[i], onClick = { onPlaylistSelected(m3u) }, onHold = { showDeletePlaylistDialog(m3u) })
        }
        if (m3uFiles.isEmpty()) rows += RowSpec(localize("No .m3u files found in Playlists"), color = ThemeManager.getTextColorSecondary())
        screen.show(ListSpec(localize("Playlists"), rows, focus = Focus.LAST))
    }

    /** The storage of a playlist, for telling apart playlists of the same name. */
    private fun storageName(file: File): String {
        val storage = localize(if (StoragePaths.isOnSecondary(file)) "MicroSD Card" else "Internal Storage")
        return if (file.parentFile?.name == StoragePaths.LEGACY_PLAYLISTS_DIR) "$storage, ${StoragePaths.LEGACY_PLAYLISTS_DIR}" else storage
    }

    fun buildM3uSongsUI(m3uFile: File) {
        val songs = parseM3uFile(m3uFile)
        val main = activity as? com.themoon.y1.MainActivity
        main?.virtualSongList?.clear()
        for (song in songs) {
            main?.virtualSongList?.add(song.file)
        }
        // Hold a song: remove it from this playlist (the occurrence of this row, when the song is in it more than once)
        val adapter = SongListAdapter(songs, artRows = true, onHold = { song, occurrence -> showRemoveFromPlaylistDialog(m3uFile, song.file, occurrence) })
        screen.show(ListSpec(localize("Playlist") + ": " + m3uFile.nameWithoutExtension, adapter = adapter, empty = localize("No Songs")))
    }

    private fun smartPlaylistTitle(kind: PlayHistoryManager.Kind): String = when (kind) {
        PlayHistoryManager.Kind.RECENTLY_PLAYED -> localize("Recently Played")
        PlayHistoryManager.Kind.MOST_PLAYED -> localize("Top 25 Most Played")
    }

    /**
     * Recently Played (newest first) or Top 25 Most Played (most plays first), from [PlayHistoryManager].
     * Rows show the cover, with the title over the artist. Not alphabetical, so no index bar. A song plays the list
     * from that song.
     */
    fun buildSmartPlaylistUI(state: ScreenState.SmartPlaylistSongs) {
        // Empty until the list comes from the worker thread (a few ms)
        screen.show(ListSpec(smartPlaylistTitle(state.kind)))
        PlayHistoryManager.getInstance(activity).loadSongs(state.kind) { files ->
            val nav = com.themoon.y1.navigation.NavigationManager.getInstance()
            if (nav.currentState == state) showSmartPlaylistSongs(state, files)
        }
    }

    private fun showSmartPlaylistSongs(state: ScreenState.SmartPlaylistSongs, files: List<File>) {
        val main = activity as? com.themoon.y1.MainActivity
        val byPath = libraryByPath()
        val rows = ArrayList<SongItem>()
        for (file in files) rows.add(taggedRow(file, byPath))
        main?.virtualSongList?.clear()
        main?.virtualSongList?.addAll(files)
        main?.virtualListSelectedIndex = 0
        screen.show(ListSpec(smartPlaylistTitle(state.kind), adapter = SongListAdapter(rows, artRows = true), empty = localize("No Songs")))
    }

    private fun libraryByPath(): HashMap<String, SongItem> {
        val byPath = HashMap<String, SongItem>()
        for (song in com.themoon.y1.MainActivity.customLibrary) byPath[song.file.absolutePath] = song
        return byPath
    }

    /** A playlist row with the title, artist and album of the library tags; the file name when the library does not have the file. */
    private fun taggedRow(file: File, byPath: Map<String, SongItem>): SongItem {
        val song = byPath[file.absolutePath]
        val title = song?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val row = SongItem(file, title, song?.artist, song?.album)
        row.durationMs = song?.durationMs ?: 0L
        return row
    }

    fun parseM3uFile(m3uFile: File): List<SongItem> {
        val songs = ArrayList<SongItem>()
        if (!m3uFile.exists()) return songs
        val byPath = libraryByPath()

        try {
            val br = BufferedReader(InputStreamReader(FileInputStream(m3uFile), "UTF-8"))
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val raw = line?.trim() ?: continue
                if (raw.isEmpty() || raw.startsWith("#")) continue

                val audioFile = resolveEntry(raw, m3uFile)
                if (audioFile.exists() && isAudioFile(audioFile)) {
                    songs.add(taggedRow(audioFile, byPath))
                }
            }
            br.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return songs
    }

    /**
     * The file of the playlist line [raw] (a path relative to the playlist, absolute, or below the music folder).
     * "./" and "../" parts: the library keys and the play queue use the plain absolute path. Only when it is the same
     * file: after a link, ".." goes up from the target of the link, not from the link.
     */
    private fun resolveEntry(raw: String, m3uFile: File): File {
        val audioFile = PlaylistPaths.resolve(raw.trim(), m3uFile.parentFile, musicRootFolder)
        val plain = File(PlaylistPaths.normalize(audioFile.path))
        return if (plain.path != audioFile.path && plain.exists() && PlaylistPaths.sameFile(plain, audioFile)) plain else audioFile
    }

    /**
     * The lines of [m3uFile], or null when it cannot be read. [strict]: also null when the file is not valid UTF-8 (a
     * playlist of a computer in an old code page): writing it again would break its other non-ASCII names for ever.
     */
    private fun readLines(m3uFile: File, strict: Boolean = false): List<String>? = try {
        val decoder = Charsets.UTF_8.newDecoder().apply {
            val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
            onMalformedInput(action)
            onUnmappableCharacter(action)
        }
        BufferedReader(InputStreamReader(FileInputStream(m3uFile), decoder)).use { it.readLines() }
    } catch (e: Exception) {
        null
    }

    /** The [M3uEdit.key] of each song in [m3uFile] (also songs that are not on the player now). */
    private fun songKeysIn(m3uFile: File): Set<String> =
        readLines(m3uFile).orEmpty().filter(M3uEdit::isEntry).mapTo(HashSet()) { M3uEdit.key(resolveEntry(it, m3uFile)) }

    /** With No Duplicates in Playlists on: the songs of [songFiles] that [m3uFile] does not have yet. */
    private fun songsToAdd(m3uFile: File, songFiles: List<File>): List<File> =
        if (noDuplicates()) M3uEdit.newSongs(songFiles, if (m3uFile.exists()) songKeysIn(m3uFile) else emptySet()) else songFiles

    fun showDeletePlaylistDialog(m3uFile: File) {
        ActionScreens.open(ActionScreens.Spec(
            localize("Delete Playlist"),
            listOf(m3uFile.nameWithoutExtension, localize("Are you sure you want to delete this playlist?")),
            listOf(ActionScreens.Action(localize("Delete")) {
                if (m3uFile.exists() && m3uFile.delete()) {
                    Notice.show(activity, localize("Playlist deleted."))
                    buildPlaylistsUI()
                } else {
                    Notice.error(activity, localize("Failed to delete playlist."))
                }
            })
        ))
    }

    /** Asks, then removes the [occurrence]-th (0 = first) entry of [audioFile] from [m3uFile] and shows the playlist again. */
    fun showRemoveFromPlaylistDialog(m3uFile: File, audioFile: File, occurrence: Int = 0) {
        ActionScreens.open(ActionScreens.Spec(
            localize("Remove from Playlist"),
            listOf(audioFile.nameWithoutExtension, localize("Do you want to remove this track from the playlist?")),
            listOf(ActionScreens.Action(localize("Remove")) {
                when (removeSongFromM3uFile(m3uFile, audioFile, occurrence)) {
                    RemoveResult.REMOVED -> Notice.show(activity, localize("Track removed."))
                    RemoveResult.NOT_FOUND -> {} // the file changed since the list was made: the list shows it again now
                    RemoveResult.FAILED -> Notice.error(activity, localize("Failed to save."))
                }
                buildM3uSongsUI(m3uFile)
            }),
            selectAction = false // one centre push does not remove a song by accident
        ))
    }

    fun showAddToPlaylistDialog(songFile: File) = showAddToPlaylistDialog(listOf(songFile))

    fun showAddToPlaylistDialog(songFiles: List<File>) {
        val playlistDir = StoragePaths.getPlaylistsDir()
        if (!playlistDir.exists()) {
            playlistDir.mkdirs()
        }

        val onTheGoFile = File(playlistDir, "On-The-Go.m3u8")
        if (!onTheGoFile.exists()) {
            try {
                val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(onTheGoFile), "UTF-8"))
                bw.write("#EXTM3U\n")
                bw.close()
            } catch (ignored: Exception) {}
        }

        val allFiles = playlistFiles()
        val labels = PlaylistPaths.labels(allFiles, ::storageName)

        // A list of targets: On-The-Go, a new playlist, then each playlist. Back cancels.
        val targets = ArrayList<ActionScreens.Action>()
        targets += ActionScreens.Action(localize("On-The-Go")) {
            addSongsToOnTheGo(songFiles)
        }
        targets += ActionScreens.Action(localize("Create New Playlist")) {
            var count = 1
            var newPlaylistFile: File
            do {
                newPlaylistFile = File(playlistDir, localize("Playlist ") + count + ".m3u8")
                count++
            } while (newPlaylistFile.exists())

            if (writeSongsToM3uFile(newPlaylistFile, songsToAdd(newPlaylistFile, songFiles), false)) {
                Notice.show(
                    activity,
                    Texts.format(localize, "Created Playlist %d successfully!", count - 1)
                )
            } else {
                Notice.error(activity, localize("Failed to save."))
            }
        }
        for ((i, targetM3u) in allFiles.withIndex()) {
            if (targetM3u == onTheGoFile) continue
            targets += ActionScreens.Action(labels[i]) {
                val toAdd = songsToAdd(targetM3u, songFiles)
                when {
                    toAdd.isEmpty() -> Notice.show(activity, localize("Already in Playlist"))
                    writeSongsToM3uFile(targetM3u, toAdd, true) -> Notice.show(activity, localize("Added to playlist successfully!"))
                    else -> Notice.error(activity, localize("Failed to save."))
                }
            }
        }
        ActionScreens.open(ActionScreens.Spec(localize("Add to Playlist"), actions = targets))
    }

    fun addSongToOnTheGo(songFile: File) = addSongsToOnTheGo(listOf(songFile))

    /** One write and one notice for all [songFiles]. */
    fun addSongsToOnTheGo(songFiles: List<File>) {
        val playlistDir = StoragePaths.getPlaylistsDir()
        if (!playlistDir.exists()) {
            playlistDir.mkdirs()
        }
        val onTheGoFile = File(playlistDir, "On-The-Go.m3u8")
        if (!onTheGoFile.exists()) {
            try {
                val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(onTheGoFile), "UTF-8"))
                bw.write("#EXTM3U\n")
                bw.close()
            } catch (ignored: Exception) {}
        }
        val toAdd = songsToAdd(onTheGoFile, songFiles)
        when {
            toAdd.isEmpty() -> Notice.show(activity, localize("Already in Playlist"))
            writeSongsToM3uFile(onTheGoFile, toAdd, true) -> Notice.show(activity, localize("Added to On-The-Go."))
            else -> Notice.error(activity, localize("Failed to save."))
        }
    }

    /** Writes [songFile] to [m3uFile] (a new file when not [append]). False when it could not be written. */
    fun writeSongToM3uFile(m3uFile: File, songFile: File, append: Boolean): Boolean =
        writeSongsToM3uFile(m3uFile, listOf(songFile), append)

    /**
     * Writes all [songFiles] to [m3uFile] (a new file when not [append]) in one open and one flush. False when it could
     * not be written; a failed write can still leave the lines before it in the file.
     */
    fun writeSongsToM3uFile(m3uFile: File, songFiles: List<File>, append: Boolean): Boolean {
        return try {
            // A playlist made on a computer may not end with a line break: the new path goes on a line of its own
            val newLineFirst = append && PlaylistPaths.endsWithoutLineBreak(m3uFile)
            val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(m3uFile, append), "UTF-8"))
            bw.use {
                if (!append) it.write("#EXTM3U\n")
                if (newLineFirst) it.write("\n")
                for (f in songFiles) it.write(f.absolutePath + "\n")
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    enum class RemoveResult { REMOVED, NOT_FOUND, FAILED }

    /**
     * Removes the [occurrence]-th (0 = first) entry of [songFile] from [m3uFile], with its `#EXT...` lines. The entry is
     * found by its file (the same rules as [parseM3uFile]), not by its name: two songs with one file name in two
     * albums stay apart. The new text goes to a temporary file, which one rename puts in place (also on FAT, the
     * microSD card): a failed write keeps the playlist as it was. A file that is not valid UTF-8 is not written.
     */
    fun removeSongFromM3uFile(m3uFile: File, songFile: File, occurrence: Int = 0): RemoveResult {
        if (!m3uFile.exists()) return RemoveResult.NOT_FOUND
        val lines = readLines(m3uFile, strict = true) ?: return RemoveResult.FAILED
        val target = songFile.absolutePath
        val kept = M3uEdit.removeEntry(lines, occurrence) { resolveEntry(it, m3uFile).absolutePath == target }
            ?: return RemoveResult.NOT_FOUND
        val tmp = File(m3uFile.path + ".tmp")
        return try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(tmp, false), "UTF-8")).use { w -> for (l in kept) w.write(l + "\n") }
            if (tmp.renameTo(m3uFile)) RemoveResult.REMOVED else RemoveResult.FAILED
        } catch (e: Exception) {
            e.printStackTrace()
            RemoveResult.FAILED
        } finally {
            if (tmp.exists()) tmp.delete() // after a failure: the playlist is as it was, and no .tmp file stays
        }
    }
}
