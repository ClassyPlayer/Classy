package com.themoon.y1.controllers.home

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.View
import android.widget.ImageView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.managers.MediaLibraryManager
import java.io.File
import java.io.FileInputStream

/**
 * The art of the split screens: the cover pane of the main menu, of the Settings top level and of the Music and
 * Audiobooks menus (the art of the song that plays, else a random cover, else the default art).
 */
class HomeWidgetsController(
    private val context: Context,
    private val ivMainMenuCover: ImageView?,
    private val ivBrowserCover: ImageView?,
    private val frameBrowserCover: View?,
    private val ivSettingsCover: ImageView?,
    private val fetchRandomIdleCoverAsync: () -> Unit,
    private val isAudiobookMode: () -> Boolean = { false }
) {

    var lastAlbumArtBytes: ByteArray? = null
        set(value) {
            // The same cover again (a refresh with no track change) keeps its bitmap: no second decode
            if (value != null && value === field && cachedAlbumArtBitmap != null) return
            field = value
            cachedAlbumArtBitmap = if (value != null && value.isNotEmpty()) decodedArt(value) else null
        }
    private var cachedAlbumArtBitmap: Bitmap? = null

    // A bitmap of a cover that is already decoded (the one that Now Playing shows), and its bytes
    private var offeredBytes: ByteArray? = null
    private var offeredBitmap: Bitmap? = null

    /** The widgets use [bitmap] when they get [bytes], in place of a decode on the main thread. */
    fun offerDecodedArt(bytes: ByteArray, bitmap: Bitmap) {
        offeredBytes = bytes
        offeredBitmap = bitmap
    }

    /** The widget bitmap of [bytes], decoded on another thread with [decodeAlbumArtSmart], if they are still the cover. */
    fun setDecodedArt(bytes: ByteArray, bitmap: Bitmap) {
        if (bytes !== lastAlbumArtBytes) return
        if (cachedBookCoverBitmap != null && cachedBookCoverBitmap === cachedAlbumArtBitmap) cachedBookCoverBitmap = bitmap
        cachedAlbumArtBitmap = bitmap
        if (bytes === offeredBytes) offeredBitmap = bitmap
        refreshWidgets()
    }

    private fun decodedArt(bytes: ByteArray): Bitmap? =
        if (bytes === offeredBytes) offeredBitmap else decodeAlbumArtSmart(bytes)

    var cachedBookCoverBitmap: Bitmap? = null

    fun setAudiobookCoverBytes(bytes: ByteArray?) {
        if (bytes != null && bytes.isNotEmpty()) {
            cachedBookCoverBitmap = decodedArt(bytes)
        }
    }

    var idleRandomCoverBitmap: Bitmap? = null

    fun refreshWidgets() {
        val songArt = cachedAlbumArtBitmap ?: lastAlbumArtBytes?.takeIf { it.isNotEmpty() }?.let {
            try { decodeAlbumArtSmart(it) } catch (_: Exception) { null }
        }
        // The main menu cover pane, and the Settings one while it shows
        val covers = ArrayList<ImageView>(3)
        ivMainMenuCover?.let { covers.add(it) }
        ivSettingsCover?.takeIf { (it.parent as? View)?.visibility == View.VISIBLE }?.let { covers.add(it) }

        // The Music / Audiobooks menu cover pane
        if (ivBrowserCover != null && frameBrowserCover != null && frameBrowserCover.visibility == View.VISIBLE) {
            if (isAudiobookMode()) {
                val cached = cachedBookCoverBitmap
                if (cached != null) {
                    ivBrowserCover.setImageBitmap(cached)
                } else {
                    ivBrowserCover.setImageResource(R.drawable.default_album)
                    fetchAudiobookCoverAsync { bmp ->
                        ivBrowserCover.setImageBitmap(bmp)
                    }
                }
            } else {
                covers.add(ivBrowserCover)
            }
        }

        // The art of the song that plays, else a random cover, else the default art (and a random cover is on the way)
        val idle = idleRandomCoverBitmap
        for (cover in covers) {
            when {
                songArt != null -> cover.setImageBitmap(songArt)
                idle != null -> cover.setImageBitmap(idle)
                else -> cover.setImageResource(R.drawable.default_album)
            }
        }
        if (songArt == null && idle == null && covers.isNotEmpty()) fetchRandomIdleCoverAsync()
    }

    @Volatile
    private var isFetchingAudiobookCover = false

    private fun fetchAudiobookCoverAsync(onLoaded: (Bitmap) -> Unit) {
        if (isFetchingAudiobookCover) return
        isFetchingAudiobookCover = true
        Thread({
            try {
                val bmp = resolveAudiobookCover()
                if (bmp != null) {
                    cachedBookCoverBitmap = bmp
                    (context as? Activity)?.runOnUiThread {
                        onLoaded(bmp)
                    }
                }
            } finally {
                isFetchingAudiobookCover = false
            }
        }, "y1-book-cover").start()
    }

    private fun resolveAudiobookCover(): Bitmap? {
        val main = MainActivity.instance
        val savedPath = main?.prefs?.getString("last_played_audiobook_path", null)
            ?: if (main?.prefs?.getBoolean("last_played_is_audiobook", false) == true) {
                main.prefs.getString("last_played_song_path", null)
            } else null

        if (!savedPath.isNullOrEmpty()) {
            val file = File(savedPath)
            if (file.exists()) {
                val bmp = extractCoverFromFileOrFolder(file)
                if (bmp != null) return bmp
            }
        }

        val libraryBook = MainActivity.audiobookLibrary.firstOrNull()?.file
        if (libraryBook != null && libraryBook.exists()) {
            val bmp = extractCoverFromFileOrFolder(libraryBook)
            if (bmp != null) return bmp
        }

        for (dir in StoragePaths.getAudiobooksDirs()) {
            if (dir.exists()) {
                val folderCover = MediaLibraryManager.getInstance().findFolderCover(dir)
                if (folderCover != null && folderCover.exists()) {
                    val bmp = decodeFileSmart(folderCover)
                    if (bmp != null) return bmp
                }
                val subdirs = dir.listFiles()
                if (subdirs != null) {
                    for (sub in subdirs) {
                        if (sub.isDirectory) {
                            val subCover = MediaLibraryManager.getInstance().findFolderCover(sub)
                            if (subCover != null && subCover.exists()) {
                                val bmp = decodeFileSmart(subCover)
                                if (bmp != null) return bmp
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun decodeFileSmart(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            var w = opts.outWidth
            var h = opts.outHeight
            while (w > 512 || h > 512) {
                sample *= 2
                w /= 2
                h /= 2
            }
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCoverFromFileOrFolder(file: File): Bitmap? {
        val parent = file.parentFile
        val folderCover = MediaLibraryManager.getInstance().findFolderCover(parent)
        if (folderCover != null && folderCover.exists()) {
            val bmp = decodeFileSmart(folderCover)
            if (bmp != null) return bmp
        }

        val safeName = file.name.substringBeforeLast(".")
        val coverFile = File(StoragePaths.getCoversDir(), "$safeName.jpg")
        if (coverFile.exists()) {
            val bmp = decodeFileSmart(coverFile)
            if (bmp != null) return bmp
        }

        return try {
            val mmr = MediaMetadataRetriever()
            val fis = FileInputStream(file)
            mmr.setDataSource(fis.fd)
            val pic = mmr.embeddedPicture
            fis.close()
            mmr.release()
            if (pic != null) {
                decodeAlbumArtSmart(pic)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** The widget bitmap of a cover (at most 512 px), or null. Any thread. */
        fun decodeAlbumArtSmart(data: ByteArray): Bitmap? = try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)

            var sample = 1
            var w = opts.outWidth
            var h = opts.outHeight
            while (w > 512 || h > 512) {
                sample *= 2
                w /= 2
                h /= 2
            }

            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        } catch (_: Exception) {
            null
        }
    }
}
