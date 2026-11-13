package com.themoon.y1.adapters

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.core.TimeFormat
import com.themoon.y1.lists.ArtRow
import com.themoon.y1.lists.RowTexts
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.models.ArtistNames
import com.themoon.y1.models.SongItem
import com.themoon.y1.podcasts.PodcastDownloadManager
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date

class SongListAdapter(
    private val items: List<SongItem>,
    /** Playlist rows: the cover, the title over the artist, and the track length at the right. */
    private val artRows: Boolean = false,
    /** The rows are the episodes of a podcast channel (Podcasts), not songs of the library. */
    private val isPodcast: Boolean = false
) : BaseAdapter(), RowTexts {

    private val showShuffleRow: Boolean = !isPodcast && determineShuffleRow(items)

    fun hasShuffleRow(): Boolean = showShuffleRow

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int {
        if (showShuffleRow && position == 0) {
            return VIEW_TYPE_SHUFFLE
        }
        return VIEW_TYPE_SONG
    }

    override fun getCount(): Int = items.size + (if (showShuffleRow) 1 else 0)

    override fun getItem(position: Int): Any? {
        if (showShuffleRow) {
            if (position == 0) return null
            return items[position - 1]
        }
        return items[position]
    }

    override fun getItemId(position: Int): Long = position.toLong()

    /** The song title for the letter jump; none for the Shuffle row. */
    override fun rowText(position: Int): String? = (getItem(position) as? SongItem)?.title

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        if (hasShuffleRow() && position == 0) {
            return getShuffleRowView(convertView)
        }
        val songPosition = if (hasShuffleRow()) position - 1 else position
        val song = items[songPosition]

        val main = MainActivity.instance
        if (artRows && main != null) return getArtRowView(main, convertView, song, position, songPosition)
        var iconCode = if (main != null && main.isAudiobookLibraryMode) "\uE310" else "\uE405"
        var displayTitle = song.title
        var customColor = 0

        if (main != null) {
            if (isPodcast) {
                val audioUrl = song.genre
                val channelName = song.artist
                val pubDate = song.year
                val datePrefix = if (!pubDate.isNullOrEmpty()) "[${pubDate.trim()}] " else ""

                val safeChannel = channelName.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                val safeTitle = song.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
                val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)

                var savedPos = main.prefs.getInt("book_pos_" + localFile.absolutePath, 0)
                if (savedPos == 0) {
                    val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                    savedPos = main.prefs.getInt("book_pos_$streamKey", 0)
                }

                var progressText = ""
                if (savedPos > 0) {
                    progressText = " [⏱ " + TimeFormat.format(savedPos) + "]"
                }

                val pdm = PodcastDownloadManager.getInstance()
                if (pdm.isDownloading(audioUrl)) {
                    val prog = pdm.getProgress(audioUrl)
                    displayTitle = "⏳ [$prog%] $datePrefix${song.title}"
                } else if (localFile.exists() && localFile.length() > 0) {
                    displayTitle = "✔ $datePrefix${song.title}$progressText"
                } else {
                    displayTitle = "$datePrefix${song.title}$progressText"
                }
            } else if ("RECENT" == main.virtualQueryType) {
                val lastMod = song.file.lastModified()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayStart = cal.timeInMillis
                val yesterdayStart = todayStart - (24 * 60 * 60 * 1000)
                val datePrefix = when {
                    lastMod >= todayStart -> "[${main.t("Today")}] "
                    lastMod >= yesterdayStart -> "[${main.t("Yesterday")}] "
                    else -> {
                        val sdf = SimpleDateFormat("yy.MM.dd")
                        "[${sdf.format(Date(lastMod))}] "
                    }
                }
                displayTitle = datePrefix + song.title
            } else if ("SEARCH" == main.virtualQueryType) {
                val q = main.virtualQueryValue?.lowercase() ?: ""
                val titleMatch = song.title.lowercase().contains(q)
                if (!titleMatch && song.artist.lowercase().contains(q)) {
                    displayTitle = "${song.title}  —  ${song.artist}"
                } else if (!titleMatch && song.album.lowercase().contains(q)) {
                    displayTitle = "${song.title}  —  ${song.album}"
                }
            }
        }

        val isSongRowValid = convertView is LinearLayout &&
            "song_row" == convertView.tag &&
            convertView.childCount > 1 &&
            convertView.getChildAt(0) is TextView

        val btn: View = if (!isSongRowValid || main == null) {
            main?.createListButtonWithIcon(iconCode, displayTitle, customColor)?.apply {
                tag = "song_row"
                val rowHeight = if (ThemeManager.isClassicTheme()) (ClassicMenuStyle.ROW_HEIGHT_DP * main.resources.displayMetrics.density).toInt() else AbsListView.LayoutParams.WRAP_CONTENT
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    rowHeight
                )
            } ?: View(parent?.context)
        } else {
            val layout = convertView as LinearLayout
            val tvIcon = layout.getChildAt(0) as TextView
            val tvText = layout.getChildAt(1) as TextView
            tvIcon.text = iconCode
            tvText.text = displayTitle
            val applyColor = if (customColor != 0) customColor else ThemeManager.getTextColorPrimary()
            if (!convertView.hasFocus()) {
                tvIcon.setTextColor(applyColor)
                tvText.setTextColor(applyColor)
            }
            convertView
        }

        var hasProgress = false
        if (main != null && main.isAudiobookLibraryMode) {
            val pos = main.prefs.getInt("book_pos_" + song.file.absolutePath, 0)
            val dur = main.prefs.getInt("book_dur_" + song.file.absolutePath, 0)
            if (pos > 0 && dur > 0) {
                main.setupAudiobookProgress(btn, pos, dur)
                hasProgress = true
            }
        }

        if (main != null && isPodcast) {
            val safeChannel = song.artist.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            val safeTitle = song.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
            val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)
            val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"

            var pos = main.prefs.getInt("book_pos_" + localFile.absolutePath, 0)
            var dur = main.prefs.getInt("book_dur_" + localFile.absolutePath, 0)

            if (pos == 0 || dur == 0) {
                pos = main.prefs.getInt("book_pos_$streamKey", 0)
                dur = main.prefs.getInt("book_dur_$streamKey", 0)
            }

            if (pos > 0 && dur > 0) {
                main.setupAudiobookProgress(btn, pos, dur)
                hasProgress = true
            }
        }

        if (!hasProgress) {
            applyDefaultFocusListener(btn, position, song.title, customColor)
        }

        btn.setOnClickListener {
            if (main == null) return@setOnClickListener
            main.virtualListSelectedIndex = position
            main.clickFeedback()

            if (isPodcast) {
                val audioUrl = song.genre
                val imageUrl = song.album
                val channelName = song.artist

                val safeChannel = channelName.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                val safeTitle = song.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
                val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)

                // A file that still downloads is not complete: it is not played (the episode screen offers Stream)
                val pdm = PodcastDownloadManager.getInstance()
                if (localFile.exists() && localFile.length() > 0 && !pdm.isDownloading(audioUrl)) {
                    var savedPos = main.prefs.getInt("book_pos_" + localFile.absolutePath, 0)
                    if (savedPos == 0) {
                        val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                        savedPos = main.prefs.getInt("book_pos_$streamKey", 0)
                    }

                    val playList = ArrayList<File>()
                    var targetIdx = 0

                    for (ep in items) {
                        val epTitle = ep.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
                        val epFile = File(StoragePaths.getPodcastChannelDir(safeChannel), epTitle)
                        if (epFile.exists() && epFile.length() > 0 && !pdm.isDownloading(ep.genre)) {
                            playList.add(epFile)
                            if (epFile.absolutePath == localFile.absolutePath) {
                                targetIdx = playList.size - 1
                            }
                        }
                    }

                    if (savedPos > 0) {
                        AudioPlayerManager.getInstance().playTrackListWithOffset(playList, targetIdx, savedPos)
                    } else {
                        AudioPlayerManager.getInstance().playTrackList(playList, targetIdx)
                    }
                    main.changeScreen(3)
                } else {
                    main.showPodcastActionDialog(song.title, audioUrl, imageUrl, channelName)
                }
                return@setOnClickListener
            }

            AudioPlayerManager.getInstance().playTrackList(main.virtualSongList, songPosition)
            main.changeScreen(3)
        }

        btn.setOnLongClickListener {
            if (main == null) return@setOnLongClickListener false
            main.clickFeedback()
            main.isLongPressConsumed = true

            when (main.currentBrowserMode) {
                5 -> main.showRemoveFromFavoritesDialog(song.file)
                7 -> main.showRemoveFromPlaylistDialog(song.file)
                else -> main.showAddToPlaylistDialog(song.file)
            }
            true
        }

        return btn
    }

    private fun getShuffleRowView(convertView: View?): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val d = main.resources.displayMetrics.density
        val coverSize = (52f * d).toInt()
        val art = ArtRow.of(convertView, main)
        val row = art.view

        if (shuffleTileBitmap == null || shuffleTileBitmap?.width != coverSize) {
            shuffleTileBitmap = TablerIcons.renderTile(
                TablerIcons.SHUFFLE,
                coverSize,
                (28f * d).toInt(),
                0xFFFFFFFF.toInt(),
                0xFF1C1C1E.toInt(),
                8f * d
            )
        }
        art.image.setImageBitmap(shuffleTileBitmap)

        val count = if (main.virtualSongList != null && main.virtualSongList.isNotEmpty()) {
            main.virtualSongList.size
        } else {
            items.size
        }
        art.bind(main.t("Shuffle"), if (count == 1) "1 " + main.t("Song") else "$count " + main.t("Songs"))

        row.setOnClickListener {
            main.clickFeedback()
            val listToShuffle: List<File> = if (main.virtualSongList != null && main.virtualSongList.isNotEmpty()) {
                main.virtualSongList
            } else {
                val list = ArrayList<File>()
                for (item in items) {
                    list.add(item.file)
                }
                list
            }
            val shuffled = ArrayList(listToShuffle)
            Collections.shuffle(shuffled)
            AudioPlayerManager.getInstance().playTrackList(shuffled, 0)
            main.changeScreen(3)
        }
        row.setOnLongClickListener(null)

        return row
    }

    private fun getArtRowView(main: MainActivity, convertView: View?, song: SongItem, position: Int, songPosition: Int): View {
        val art = ArtRow.of(convertView, main)
        val path = song.file.absolutePath
        val artist = ArtistNames.display(song.artist).takeUnless { it.isBlank() || it.equals("Unknown Artist", ignoreCase = true) } ?: ""
        // The scan keeps the length; a library cache from before it did is read from the file once
        val length = if (song.durationMs > 0L) TimeFormat.format(song.durationMs) else durations[path]
        art.bind(song.title, artist, length ?: "")
        art.image.tag = path
        if (length == null) loadDuration(path, art) else stopWaiting(art)

        val covers = AlbumCoverManager.getInstance()
        val cover = covers.getThumbnailDirect(AlbumCoverManager.getAlbumKey(song.file, song.album))
        if (cover != null) {
            art.image.setImageBitmap(cover)
        } else {
            art.image.setImageResource(R.drawable.default_album)
            covers.loadCoverAsync(song, object : AlbumCoverManager.CoverCallback {
                override fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?) {
                    if (cover != null && art.image.tag == path) art.image.setImageBitmap(cover)
                }
            })
        }

        art.view.setOnClickListener {
            main.virtualListSelectedIndex = position
            main.clickFeedback()
            AudioPlayerManager.getInstance().playTrackList(main.virtualSongList, songPosition)
            main.changeScreen(3)
        }
        art.view.setOnLongClickListener {
            main.clickFeedback()
            main.isLongPressConsumed = true
            when (main.currentBrowserMode) {
                5 -> main.showRemoveFromFavoritesDialog(song.file)
                7 -> main.showRemoveFromPlaylistDialog(song.file)
                else -> main.showAddToPlaylistDialog(song.file)
            }
            true
        }
        return art.view
    }

    private fun applyDefaultFocusListener(btn: View, position: Int, title: String, customColor: Int) {
        val main = MainActivity.instance ?: return
        val normalColor = if (customColor != 0) customColor else ThemeManager.getTextColorPrimary()

        val listener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                main.virtualListSelectedIndex = position
                btn.background = main.createFocusedButtonBackground()

                if (v is LinearLayout) {
                    if (v.childCount > 1) {
                        (v.getChildAt(0) as? TextView)?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        val tvText = v.getChildAt(1) as? TextView
                        tvText?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        if (v.childCount > 2) v.getChildAt(2).visibility = View.VISIBLE
                        tvText?.isSelected = true
                    }
                } else if (v is Button) {
                    v.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    v.isSelected = true
                }

                main.showFastScrollLetter(title)
            } else {
                btn.background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())

                if (v is LinearLayout) {
                    if (v.childCount > 1) {
                        (v.getChildAt(0) as? TextView)?.setTextColor(normalColor)
                        val tvText = v.getChildAt(1) as? TextView
                        tvText?.setTextColor(normalColor)
                        if (v.childCount > 2) v.getChildAt(2).visibility = View.GONE
                        tvText?.isSelected = false
                    }
                } else if (v is Button) {
                    v.setTextColor(normalColor)
                    v.isSelected = false
                }
            }
        }

        btn.onFocusChangeListener = listener
        if (btn.isFocused) {
            listener.onFocusChange(btn, true)
        }
    }

    companion object {
        const val VIEW_TYPE_SHUFFLE: Int = 0
        const val VIEW_TYPE_SONG: Int = 1

        private var shuffleTileBitmap: Bitmap? = null

        // Lengths of songs whose cache entry has none, read when a row first shows (kept until Classy restarts)
        private val durations = java.util.concurrent.ConcurrentHashMap<String, String>()
        // The rows that wait for a length, with the path that each one shows. Weak keys: a row that goes away
        // stays not here. Lock it for each use, also of [pendingPaths].
        private val waitingRows = java.util.WeakHashMap<ArtRow, String>()
        // The paths in the queue of [durationWorker]: one read for each path
        private val pendingPaths = HashSet<String>()
        private val durationWorker = java.util.concurrent.Executors.newSingleThreadExecutor { job ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                job.run()
            }, "y1-row-length")
        }
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        private fun loadDuration(path: String, art: ArtRow) {
            synchronized(waitingRows) {
                waitingRows[art] = path
                if (!pendingPaths.add(path)) return
            }
            durationWorker.execute { readDuration(path) }
        }

        /** [art] shows a song with a known length now: a read that ends later does not change it. */
        private fun stopWaiting(art: ArtRow) {
            synchronized(waitingRows) { waitingRows.remove(art) }
        }

        private fun readDuration(path: String) {
            // No read when no row shows the path now (the list scrolled on or closed); a new row queues it again
            val wanted = synchronized(waitingRows) {
                waitingRows.containsValue(path).also { if (!it) pendingPaths.remove(path) }
            }
            if (!wanted) return
            val ms = MediaLibraryManager.getInstance().readLengthMs(File(path))
            val text = if (ms > 0L) TimeFormat.format(ms) else ""
            durations[path] = text
            // Not View.post: from a worker thread it is lost while the row is not attached (Android 4.4)
            mainHandler.post {
                val rows = synchronized(waitingRows) {
                    pendingPaths.remove(path)
                    val done = waitingRows.filterValues { it == path }.keys.toList()
                    for (row in done) waitingRows.remove(row)
                    done
                }
                for (row in rows) if (row.image.tag == path) row.setTrailing(text)
            }
        }

        private fun determineShuffleRow(items: List<SongItem>?): Boolean {
            if (items.isNullOrEmpty()) return false
            val main = MainActivity.instance ?: return false
            val type = main.virtualQueryType
            return "ALL" == type || "GENRE" == type || "GENRE_ARTIST_SONGS" == type ||
                "ARTIST" == type || "ARTIST_ALL_ALBUMS" == type
        }
    }
}
