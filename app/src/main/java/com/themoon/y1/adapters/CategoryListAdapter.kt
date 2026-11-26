package com.themoon.y1.adapters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.Prefs
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.controllers.browser.BrowserController
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.lists.ArtRow
import com.themoon.y1.lists.RowTexts
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.LibrarySongs
import com.themoon.y1.managers.Plurals
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.AlbumTrackOrder
import com.themoon.y1.models.AlbumYear
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.views.ClassyMetrics
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.Notice
import java.io.File
import java.io.FileInputStream
import java.util.Collections

class CategoryListAdapter(
    private val items: List<String>,
    private val type: String,
    private val isAudiobook: Boolean = MainActivity.instance?.let {
        it.isAudiobookLibraryMode || (it.isBrowserControllerInitialized() && it.browserController.isAudiobookLibraryMode)
    } ?: false,
    /** Album artist of each album row in [items], so albums with the same title stay apart. */
    private val albumArtists: List<String>? = null,
    /** Year tag of each album row (Sort by Year is On): the row shows "<year> - <Album>". */
    private val albumYears: List<String>? = null
) : BaseAdapter(), RowTexts {

    private var albumSongCounts: MutableMap<String, Int>? = null
    private var albumFilesMap: MutableMap<String, MutableList<File>>? = null
    private var albumArtistsMap: MutableMap<String, String>? = null
    private var cachedAllSongsCount = -1
    private val showAlbumArtist = AlbumKey.byArtist && type == "ALBUM" && MainActivity.instance?.browserController?.categoryArtistFilter.isNullOrEmpty()

    init {
        if (coverCache == null) {
            coverCache = LruCache(50)
        }

        if (type == "ALBUM" || type == "GENRE_ALBUM") {
            val counts = HashMap<String, Int>()
            val filesMap = HashMap<String, MutableList<File>>()
            val artists = HashMap<String, String>()
            val bc = MainActivity.instance?.browserController
            val genreFilter = bc?.categoryGenreFilter ?: ""
            val artistFilter = bc?.categoryArtistFilter ?: ""
            val isTrackArtist = bc?.categoryArtistFilterIsTrackArtist == true
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary
            // The same filters as BrowserController.buildVirtualCategories: Compilations, and Artists without them.
            val compilationsOnly = type == "ALBUM" && bc?.currentBrowserMode is BrowserMode.Compilations
            val hideCompilationTracks = type == "ALBUM" && artistFilter.isNotEmpty() && isTrackArtist &&
                bc?.artistsHideCompilations() == true

            for (song in activeLibrary) {
                if (compilationsOnly && !song.isCompilation) continue
                if (com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)) continue
                if (type == "GENRE_ALBUM") {
                    if (!song.matchesGenre(genreFilter)) {
                        continue
                    }
                    if (artistFilter.isNotEmpty() && !song.matchesArtist(artistFilter, isTrackArtist)) {
                        continue
                    }
                } else if (type == "ALBUM" && artistFilter.isNotEmpty() && !song.matchesArtist(artistFilter, isTrackArtist)) {
                    continue
                }

                val c = counts[song.album]
                counts[song.album] = if (c == null) 1 else c + 1

                var files = filesMap[song.album]
                if (files == null) {
                    files = ArrayList()
                    filesMap[song.album] = files
                }
                files.add(song.file)

                val primaryArtist = if (song.albumArtist.isNotBlank()) song.albumArtist else song.artist
                val compKey = AlbumKey.of(song)
                val compC = counts[compKey]
                counts[compKey] = if (compC == null) 1 else compC + 1
                var compFiles = filesMap[compKey]
                if (compFiles == null) {
                    compFiles = ArrayList()
                    filesMap[compKey] = compFiles
                }
                compFiles.add(song.file)

                if (!artists.containsKey(song.album) && primaryArtist.isNotBlank()) {
                    artists[song.album] = primaryArtist
                }
            }
            albumSongCounts = counts
            albumFilesMap = filesMap
            albumArtistsMap = artists
        }
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    /** The name of the row for the letter jump; none for the "All Songs" row. */
    override fun rowText(position: Int): String? = items[position].takeUnless { it == BrowserController.ALL_SONGS_SENTINEL }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 3

    override fun getItemViewType(position: Int): Int {
        val name = items[position]
        return when {
            BrowserController.ALL_SONGS_SENTINEL == name -> 0
            type == "ALBUM" || type == "GENRE_ALBUM" -> 1
            else -> 2
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val name = items[position]
        return when {
            BrowserController.ALL_SONGS_SENTINEL == name -> getAllSongsRowView(convertView)
            type == "ALBUM" || type == "GENRE_ALBUM" -> getAlbumRowView(position, convertView, name)
            else -> getSimpleRowView(convertView, name)
        }
    }

    private fun getAllSongsRowView(convertView: View?): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val art = ArtRow.of(convertView, main)
        art.image.setImageResource(if (isAudiobook) R.drawable.book else R.drawable.icon_all_songs)
        if (cachedAllSongsCount < 0) {
            var allCount = 0
            val bc = main.browserController
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary
            if (type == "GENRE_ARTIST") {
                for (song in activeLibrary) {
                    if (song.matchesGenre(bc.categoryGenreFilter)) {
                        allCount++
                    }
                }
            } else if (type == "GENRE_ALBUM") {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in activeLibrary) {
                    if (song.matchesGenre(bc.categoryGenreFilter)) {
                        if (song.matchesArtist(bc.categoryArtistFilter, isTrack)) {
                            allCount++
                        }
                    }
                }
            } else {
                val isTrackArtistMode = bc.categoryArtistFilterIsTrackArtist
                val hideCompilationTracks = isTrackArtistMode && bc.artistsHideCompilations()
                for (song in activeLibrary) {
                    if (song.matchesArtist(bc.categoryArtistFilter, isTrackArtistMode) &&
                        !com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)
                    ) {
                        allCount++
                    }
                }
            }
            cachedAllSongsCount = allCount
        }

        val allCount = cachedAllSongsCount
        art.bind(if (isAudiobook) main.t("All Audiobooks") else main.t("All Songs"), if (isAudiobook) {
            files(allCount)
        } else {
            songs(allCount)
        })
        val row = art.view

        row.setOnClickListener {
            val bc = main.browserController
            main.clickFeedback()
            when (type) {
                "GENRE_ARTIST" -> bc.openSongs("GENRE", bc.categoryGenreFilter)
                "GENRE_ALBUM" -> bc.openSongs("GENRE_ARTIST_SONGS", bc.categoryArtistFilter)
                else -> bc.openSongs(if (bc.categoryArtistFilterIsTrackArtist) "ARTIST" else "ARTIST_ALL_ALBUMS", bc.categoryArtistFilter)
            }
        }

        row.setOnLongClickListener {
            main.clickFeedback()
            main.isLongPressConsumed = true
            val bc = main.browserController
            val tracks = ArrayList<File>()
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary
            if (type == "GENRE_ARTIST") {
                for (song in activeLibrary) {
                    if (song.matchesGenre(bc.categoryGenreFilter)) {
                        tracks.add(song.file)
                    }
                }
            } else if (type == "GENRE_ALBUM") {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in activeLibrary) {
                    if (song.matchesGenre(bc.categoryGenreFilter)) {
                        if (song.matchesArtist(bc.categoryArtistFilter, isTrack)) {
                            tracks.add(song.file)
                        }
                    }
                }
            } else {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                val hideCompilationTracks = isTrack && bc.artistsHideCompilations()
                for (song in activeLibrary) {
                    if (song.matchesArtist(bc.categoryArtistFilter, isTrack) &&
                        !com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)
                    ) {
                        tracks.add(song.file)
                    }
                }
            }
            if (tracks.isNotEmpty()) {
                Collections.shuffle(tracks)
                com.themoon.y1.managers.AudioPlayerManager.getInstance().playTrackList(tracks, 0)
                main.changeScreen(3)
            }
            true
        }

        return row
    }

    private fun getAlbumRowView(position: Int, convertView: View?, name: String): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val art = ArtRow.of(convertView, main)
        val row = art.view
        val ivCover = art.image

        val albumArtist = albumArtists?.getOrNull(position)
        val lookupKey = albumArtist?.let { AlbumKey.of(name, it) } ?: name
        val displayName = albumYears?.getOrNull(position)?.let { AlbumYear.label(it, name) } ?: name
        ivCover.tag = lookupKey
        val songCount = albumSongCounts?.get(lookupKey) ?: albumSongCounts?.get(name) ?: 0
        val artist = (albumArtist ?: albumArtistsMap?.get(name))?.let { com.themoon.y1.models.ArtistNames.display(it) }
        art.bind(displayName, if (isAudiobook) {
            files(songCount)
        } else if (showAlbumArtist && !artist.isNullOrBlank() && !artist.equals("Unknown Artist", ignoreCase = true)) {
            "$artist • " + songs(songCount)
        } else {
            songs(songCount)
        })

        val cached = coverCache?.get(lookupKey)
        if (cached != null) {
            ivCover.setImageDrawable(cached)
        } else {
            val albumFiles = albumFilesMap?.get(lookupKey) ?: albumFilesMap?.get(name)
            val sampleFile = if (!albumFiles.isNullOrEmpty()) albumFiles[0] else null
            val albumKey = if (sampleFile != null) AlbumCoverManager.getAlbumKey(sampleFile, name) else name // the year label is only the title: covers keep the key that the prefetch uses

            // Memory cache only: a disk thumbnail is read and decoded on the cover worker, not in the wheel step
            val directBmp = AlbumCoverManager.getInstance().getMemoryCover(albumKey)
            if (directBmp != null) {
                val directDrawable = BitmapDrawable(row.resources, directBmp)
                coverCache?.put(lookupKey, directDrawable)
                ivCover.setImageDrawable(directDrawable)
            } else {
                ivCover.setImageResource(if (isAudiobook) R.drawable.book else R.drawable.default_album)
                ivCover.tag = lookupKey
                loadAlbumArtAsync(lookupKey, name, sampleFile, ivCover)
            }
        }

        prefetchCovers(position)

        row.setOnClickListener {
            val bc = main.browserController
            main.clickFeedback()
            val query = when {
                type == "GENRE_ALBUM" -> "GENRE_ALBUM_SONGS"
                bc.currentBrowserMode is BrowserMode.Compilations -> "COMPILATION_ALBUM"
                else -> type
            }
            bc.openSongs(query, name, albumArtist ?: "")
        }

        row.setOnLongClickListener {
            main.clickFeedback()
            main.isLongPressConsumed = true
            val tracks = albumFilesMap?.get(lookupKey) ?: albumFilesMap?.get(name)
            if (!tracks.isNullOrEmpty()) {
                ActionScreens.open(ActionScreens.Spec(
                    displayName,
                    actions = listOf(
                        ActionScreens.Action(main.t("Shuffle Play")) {
                            val shuffled = ArrayList(tracks)
                            Collections.shuffle(shuffled)
                            AudioPlayerManager.getInstance().playTrackList(shuffled, 0)
                            main.changeScreen(MainActivity.STATE_PLAYER)
                        },
                        ActionScreens.Action(main.t("Add to Queue")) {
                            // With nothing playing, the album just starts: no "Added" notice
                            if (!AudioPlayerManager.getInstance().enqueue(inTrackOrder(tracks))) {
                                Notice.show(main, main.t("Added to Queue"))
                            }
                        },
                        ActionScreens.Action(main.t("Add to Playlist"), opensList = true) {
                            main.showAddToPlaylistDialog(inTrackOrder(tracks))
                        },
                        ActionScreens.Action(
                            main.t("Sort by Year"), value = onOff(Prefs.ALBUMS_BY_YEAR.get(main.prefs)) { main.t(it) }
                        ) {
                            Prefs.ALBUMS_BY_YEAR.set(main.prefs, !Prefs.ALBUMS_BY_YEAR.get(main.prefs))
                            main.browserController.buildVirtualCategories(type)
                        }
                    )
                ))
            }
            true
        }

        return row
    }

    /** The album in the order of its screen. */
    private fun inTrackOrder(tracks: List<File>): ArrayList<File> = ArrayList(tracks.sortedWith(
        AlbumTrackOrder.files(MainActivity.trackNumberMap) { LibrarySongs.byPath(it.absolutePath)?.title ?: it.name }
    ))

    /**
     * Loads the covers of the rows near [position] on the cover worker, so that a row that comes into the list
     * has its cover at once (not the placeholder first).
     */
    private fun prefetchCovers(position: Int) {
        val files = albumFilesMap ?: return
        for (p in position - PREFETCH_ROWS..position + PREFETCH_ROWS) {
            if (p == position || p !in items.indices) continue
            val name = items[p]
            if (name == BrowserController.ALL_SONGS_SENTINEL) continue
            val key = albumArtists?.getOrNull(p)?.let { AlbumKey.of(name, it) } ?: name
            if (coverCache?.get(key) != null) continue
            val sample = (files[key] ?: files[name])?.firstOrNull() ?: continue
            AlbumCoverManager.getInstance().loadCoverAsync(SongItem(sample, "", "", name), object : AlbumCoverManager.CoverCallback {
                override fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?) {
                    val res = MainActivity.instance?.resources ?: return
                    if (cover != null && coverCache?.get(key) == null) coverCache?.put(key, BitmapDrawable(res, cover))
                }
            })
        }
    }

    private fun loadAlbumArtAsync(cacheKey: String, name: String, sampleFile: File?, targetView: ImageView) {
        if (sampleFile == null) return
        val stubItem = SongItem(sampleFile, "", "", name)
        AlbumCoverManager.getInstance().loadCoverAsync(stubItem, object : AlbumCoverManager.CoverCallback {
            override fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?) {
                if (cover != null) {
                    val res = MainActivity.instance?.resources ?: targetView.resources
                    val result = BitmapDrawable(res, cover)
                    coverCache?.put(cacheKey, result)
                    if (cacheKey == targetView.tag) {
                        targetView.setImageDrawable(result)
                    }
                }
            }
        })
    }

    /** A name row: the MENU row of the list factory (the same row as the song rows). */
    private fun getSimpleRowView(convertView: View?, name: String): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val row: View
        if (convertView != null && SIMPLE_ROW == convertView.tag) {
            row = convertView
            ((row as LinearLayout).getChildAt(1) as TextView).text = name
        } else {
            row = ListItemViewFactory.createListButtonWithIcon(main, "", name).apply {
                tag = SIMPLE_ROW
                val d = main.resources.displayMetrics.density
                val height = (ClassyMetrics.ROW_HEIGHT * d).toInt()
                layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, height)
            }
        }

        row.setOnClickListener {
            val bc = main.browserController
            main.clickFeedback()
            when (type) {
                "GENRE" -> bc.openList(BrowserMode.GenreArtists(name))
                "GENRE_ARTIST" -> bc.openList(BrowserMode.GenreAlbums(bc.categoryGenreFilter, name))
                "ARTIST", "TRACK_ARTIST" -> bc.openList(BrowserMode.Albums(name, type == "TRACK_ARTIST"))
                else -> bc.openSongs(type, name)
            }
        }

        return row
    }

    private fun localize(key: String): String = MainActivity.instance?.t(key) ?: key

    private fun songs(n: Int): String = Plurals.format(::localize, "%d Songs", n)

    private fun files(n: Int): String = Plurals.format(::localize, "%d Files", n)

    companion object {
        private const val SIMPLE_ROW = "simple_row"
        private var coverCache: LruCache<String, Drawable>? = null

        /** Rows above and below a bound row whose covers load ahead ([prefetchCovers]). */
        private const val PREFETCH_ROWS = 3
    }
}
