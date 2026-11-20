package com.themoon.y1.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The thumbnails of the grid and the list rows: [WIDTH] x [HEIGHT] (4:3, centre crop), made once and kept as JPEG
 * files in `filesDir/photos/thumbs/` (named after the path, size and time of the photo), and in memory (~4 MB).
 *
 * One worker makes them, the newest request first: the page that shows now comes before the rows the wheel
 * already left.
 */
object PhotoThumbs {
    const val WIDTH = 128
    const val HEIGHT = 96

    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null

    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.rowBytes * value.height
    }

    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        // Newest first: offer() puts the task at the head
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }) { r -> Thread(r, "PhotoThumbs").apply { priority = Thread.NORM_PRIORITY - 1 } }

    fun init(context: Context) {
        if (dir == null) dir = File(File(context.filesDir, "photos"), "thumbs")
    }

    private fun key(p: PhotoItem): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest((p.path + "|" + p.size + "|" + p.modified).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 10) sb.append(String.format("%02x", bytes[i]))
        return sb.toString()
    }

    /** The thumbnail when it is in memory, else null (then [load]). */
    fun cached(p: PhotoItem): Bitmap? = memory.get(p.version)

    /**
     * Makes or reads the thumbnail on the worker; [done] runs on the main thread (null: the photo cannot be read).
     * It does not run when a USB share refused the read ([PhotoDecoder.close]) and reads are still closed.
     */
    fun load(p: PhotoItem, done: (Bitmap?) -> Unit) {
        val version = p.version
        memory.get(version)?.let {
            done(it)
            return
        }
        val waiting = pending[version]
        if (waiting != null) {
            waiting += done
            return
        }
        pending[version] = mutableListOf(done)
        worker.execute {
            val bmp = make(p)
            // A null from make() comes from a decode, so this is about that read
            val refused = bmp == null && PhotoDecoder.lastReadRefused()
            main.post {
                if (bmp != null) memory.put(version, bmp)
                val waiting = pending.remove(version).orEmpty()
                if (!refused) {
                    waiting.forEach { it(bmp) }
                    return@post
                }
                // A USB share refused the read (not a broken photo). Reads still closed: no answer, the grid asks again
                // after the rescan. Reads open again already: one more try for the requests that joined meanwhile.
                if (!PhotoDecoder.isClosed) for (w in waiting) load(p, w)
            }
        }
    }

    private fun make(p: PhotoItem): Bitmap? {
        val folder = dir
        val file = folder?.let { File(it, key(p) + ".jpg") }
        if (file != null && file.isFile) {
            try {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                    ?.let { return it }
            } catch (_: Throwable) {}
        }
        val bmp = PhotoDecoder.decode(p.path, p.orientation, WIDTH, HEIGHT, PhotoDecoder.Mode.CROP) ?: return null
        if (file != null) try {
            folder.mkdirs()
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Exception) {
            file.delete()
        }
        return bmp
    }

    /** Frees the thumbnails in memory (the card changed or memory is low). */
    fun clearMemory() = memory.evictAll()

    /** Deletes the thumbnail files of the photos that are gone (worker thread, after a scan). */
    fun prune(photos: List<PhotoItem>) {
        val folder = dir ?: return
        worker.execute {
            val keep = photos.mapTo(HashSet()) { key(it) + ".jpg" }
            folder.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        }
    }
}
