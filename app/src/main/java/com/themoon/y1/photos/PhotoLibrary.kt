package com.themoon.y1.photos

import android.content.Context
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.themoon.y1.StoragePaths
import java.io.File

/**
 * The photos of the Photos folders, for the Photos screens and the photo preview of the main menu.
 *
 * A scan walks the folders on a worker thread. The EXIF data (date taken, orientation) of each photo is kept in
 * `filesDir/photos/index.tsv`, so a later scan reads only the new and changed files. The index also keeps when each
 * photo was found first (Recently Added: a copied photo often keeps the file time of the camera).
 * The list stays in memory; Photos scans again when it opens ([refresh]) and after a USB share ([forget]).
 */
object PhotoLibrary {

    /** Folders deeper than this under a Photos folder are not read. */
    private const val MAX_DEPTH = 8

    private val main = Handler(Looper.getMainLooper())
    private var indexFile: File? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    @Volatile
    var photos: List<PhotoItem>? = null
        private set

    @Volatile
    var isScanning = false
        private set

    /** [forget] came during a scan: scan again when it ends (the card can have changed under it). */
    private var rescanPending = false

    /** Photos found so far by the scan that runs (for its progress line). */
    @Volatile
    var scanCount = 0
        private set

    fun init(context: Context) {
        if (indexFile == null) indexFile = File(File(context.filesDir, "photos"), "index.tsv")
    }

    /** Called on the main thread after each change (scan step, scan end). */
    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun notifyChanged() {
        for (l in listeners.toList()) l()
    }

    /** The photos now; with none yet, the index of the last scan (fast) and a scan in the background. */
    fun ensureLoaded() {
        if (photos != null || isScanning) return
        scan()
    }

    /** The photos on the card may have changed: scan again now. */
    fun forget() {
        if (isScanning) {
            rescanPending = true
            return
        }
        photos = null
        scan()
    }

    /** The microSD card is mounted: scan again if the photos are loaded or a scan runs (it may have missed the card). */
    fun onCardReady() {
        if (photos != null || isScanning) forget()
    }

    /** Scans again when the last scan is older than [REFRESH_MS] (Photos opens: new photos may be on the card). */
    fun refresh() {
        if (isScanning) return
        if (photos == null || android.os.SystemClock.uptimeMillis() - lastScanEnd > REFRESH_MS) scan()
    }

    private var lastScanEnd = 0L
    private const val REFRESH_MS = 60_000L

    private var setsOf: List<PhotoItem>? = null
    private val sets = HashMap<String, List<PhotoItem>>()

    /**
     * The photos of the set [key] ([PhotoIndex.select]), made once for each list of photos. Recently Added is made each
     * time: it depends on today's date, so a photo leaves it after 30 days without a new scan.
     */
    fun set(key: String): List<PhotoItem> {
        val list = photos.orEmpty()
        val now = System.currentTimeMillis()
        if (key == PhotoIndex.RECENT) return PhotoIndex.select(list, key, now)
        if (list !== setsOf) {
            sets.clear()
            setsOf = list
        }
        return sets.getOrPut(key) { PhotoIndex.select(list, key, now) }
    }

    /** Scans the Photos folders on a worker thread; not while a USB share closes the photo files ([PhotoDecoder.close]). */
    fun scan() {
        if (isScanning || PhotoDecoder.isClosed) return
        isScanning = true
        scanCount = 0
        val roots = StoragePaths.getPhotosDirs().filter { it.isDirectory }
        val file = indexFile
        Thread({
            val old = HashMap<String, PhotoItem>()
            var hadIndex = false
            if (file != null) try {
                // AtomicFile: the last whole index, also after a write that broke off
                PhotoIndex.decode(String(AtomicFile(file).readFully(), Charsets.UTF_8)).associateByTo(old) { it.path }
                hadIndex = old.isNotEmpty()
            } catch (_: Exception) {}
            // The last index first: the lists fill at once, the scan then brings the changes
            if (photos == null && old.isNotEmpty()) {
                val cached = old.values.filter { item -> roots.any { item.path.startsWith(it.absolutePath + "/") } }
                main.post {
                    if (photos == null) photos = cached
                    notifyChanged()
                }
            }
            val found = ArrayList<PhotoItem>()
            // The first scan has no "found" times: the file time stands in, so not all photos are Recently Added
            val now = if (hadIndex) System.currentTimeMillis() else 0L
            // One long photo read: a USB share waits for it, and a share that started first refuses it
            val walked = PhotoDecoder.reading { walkAndSave(roots, old, now, found, file); true } == true
            main.post {
                if (walked) photos = found
                isScanning = false
                lastScanEnd = android.os.SystemClock.uptimeMillis()
                if (rescanPending) {
                    rescanPending = false
                    forget()
                }
                notifyChanged()
            }
        }, "PhotoScanner").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
        notifyChanged()
    }

    /** Walks [roots] into [found] and saves the index (with the records the walk could not see). */
    private fun walkAndSave(roots: List<File>, old: Map<String, PhotoItem>, now: Long, found: MutableList<PhotoItem>, file: File?) {
        val failed = ArrayList<String>()
        for (root in roots) walk(root, 0, old, now, found, failed)
        // The records that this walk could not see: an unmounted card, a folder that failed to list
        val notSeen = PhotoIndex.notSeen(old.values, roots.map { it.absolutePath }, failed)
        val index = if (notSeen.isEmpty()) found else found + notSeen
        if (file != null) {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            var out: java.io.FileOutputStream? = null
            try {
                out = atomic.startWrite()
                out.write(PhotoIndex.encode(index).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(out)
            } catch (_: Exception) {
                // The last index stays
                if (out != null) atomic.failWrite(out)
            }
        }
        // Thumbnails of photos that are gone; not after an empty scan (a card that is not mounted yet)
        if (index.isNotEmpty()) PhotoThumbs.prune(index)
    }

    /** [now]: the "found" time of a new photo; 0 = its file time. [failed]: the folders that could not be listed. */
    private fun walk(dir: File, depth: Int, old: Map<String, PhotoItem>, now: Long, out: MutableList<PhotoItem>, failed: MutableList<String>) {
        val files = try { dir.listFiles() } catch (_: Exception) { null }
        if (files == null) {
            failed += dir.absolutePath
            return
        }
        for (f in files) {
            val name = f.name
            if (name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth < MAX_DEPTH) walk(f, depth + 1, old, now, out, failed)
                continue
            }
            if (!PhotoIndex.isPhoto(name)) continue
            val size = f.length()
            if (size <= 0) continue
            val modified = f.lastModified()
            val path = f.absolutePath
            val known = old[path]
            out += when {
                known == null -> read(path, size, modified, if (now > 0) now else modified)
                known.isSameFile(size, modified) -> known
                else -> read(path, size, modified, known.added)
            }
            scanCount = out.size
            if (out.size % 200 == 0) main.post { notifyChanged() }
        }
    }

    /** A new or changed photo: its EXIF date and orientation (JPEG only). */
    private fun read(path: String, size: Long, modified: Long, added: Long): PhotoItem {
        var date = 0L
        var orientation = ExifOrientation.NORMAL
        if (PhotoIndex.hasExif(path)) try {
            val exif = ExifInterface(path)
            // On Android 4.4 "DateTime" already holds DateTimeOriginal when the photo has it (jhead)
            date = PhotoIndex.parseExifDate(exif.getAttribute("DateTimeOriginal") ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
            orientation = ExifOrientation.normalize(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifOrientation.NORMAL))
        } catch (_: Throwable) {}
        return PhotoItem(path, size, modified, date, orientation, added)
    }
}
