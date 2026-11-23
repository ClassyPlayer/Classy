package com.themoon.y1.books

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.text.Collator
import java.util.Locale

/** A book of the library: its file and what the list shows. [cover]: a thumbnail file, or null. */
class BookItem(val file: File, val title: String, val author: String, val format: BookFormat, val cover: File?) {
    val path: String get() = file.absolutePath
}

/**
 * The ebooks in the Books folders. [scan] walks the folders, reads the title, author and cover of each new or
 * changed book (an EPUB's package; a TXT's file name and folder), and keeps them in `index.json` under [dir] with a
 * small cover thumbnail, so the next scan reads only what changed.
 */
class BookLibrary(private val dir: File, private val newParser: () -> XmlPullParser) {

    private val thumbs = File(dir, "thumbs")

    /**
     * The books under [roots] (worker thread), sorted by title. [onProgress] gets the books found so far while the
     * scan reads new books. [cancelled] stops it.
     */
    fun scan(roots: List<File>, onProgress: (List<BookItem>) -> Unit = {}, cancelled: () -> Boolean = { false }): List<BookItem> {
        val files = ArrayList<File>()
        for (root in roots) collect(root, files, 0, cancelled)
        val index = readIndex()
        val out = ArrayList<BookItem>()
        val newIndex = JSONObject()
        var lastReport = System.currentTimeMillis()
        for (f in files.distinctBy { it.absolutePath }) {
            if (cancelled()) break
            val key = f.absolutePath
            val cached = index.optJSONObject(key)
            val item = if (cached != null && cached.optLong("m") == f.lastModified() && cached.optLong("s") == f.length()) {
                fromJson(f, cached)
            } else {
                read(f, roots)
            }
            out += item
            newIndex.put(key, toJson(item, f))
            val now = System.currentTimeMillis()
            if (now - lastReport > 700) {
                lastReport = now
                onProgress(sorted(out))
            }
        }
        if (!cancelled()) writeIndex(newIndex)
        return sorted(out)
    }

    private fun collect(dir: File, out: MutableList<File>, depth: Int, cancelled: () -> Boolean) {
        if (depth > MAX_DEPTH || cancelled()) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) collect(f, out, depth + 1, cancelled)
            else if (BookFormat.of(f) != null && f.length() > 0) out += f
        }
    }

    /** The title, author and cover of a new or changed book. A book that does not read still shows (by file name). */
    private fun read(f: File, roots: List<File>): BookItem {
        val format = BookFormat.of(f) ?: BookFormat.TXT
        val fallbackTitle = BookTitles.fromFileName(f)
        if (format == BookFormat.TXT) return BookItem(f, fallbackTitle, folderAuthor(f, roots), format, null)
        return try {
            ZipFileBook.read(f) { zip ->
                val pkg = EpubPackage.read(zip, newParser, withToc = false)
                val cover = pkg.coverPath?.let { path -> zip.bytes(path)?.let { saveThumb(f, it) } }
                BookItem(f, pkg.title.ifEmpty { fallbackTitle }, pkg.author.ifEmpty { folderAuthor(f, roots) }, format, cover)
            }
        } catch (_: Throwable) {
            BookItem(f, fallbackTitle, folderAuthor(f, roots), format, null)
        }
    }

    /** The folder of [f] as its author ("Books/Author/Title.txt"), as the audiobooks do; none at a Books folder. */
    private fun folderAuthor(f: File, roots: List<File>): String {
        val parent = f.parentFile ?: return ""
        if (roots.any { it.absolutePath == parent.absolutePath }) return ""
        return parent.name
    }

    private fun saveThumb(book: File, bytes: ByteArray): File? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
        if (raw == null) null else {
            val scale = THUMB_PX.toFloat() / minOf(raw.width, raw.height)
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * scale).toInt()), maxOf(1, (raw.height * scale).toInt()), true) else raw
            thumbs.mkdirs()
            val out = File(thumbs, BookKeys.hash(book.absolutePath) + ".jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (bmp !== raw) bmp.recycle()
            raw.recycle()
            out
        }
    } catch (_: Throwable) {
        null
    }

    private fun toJson(item: BookItem, f: File): JSONObject = JSONObject()
        .put("m", f.lastModified())
        .put("s", f.length())
        .put("t", item.title)
        .put("a", item.author)
        .put("c", item.cover?.name ?: "")

    private fun fromJson(f: File, o: JSONObject): BookItem {
        val cover = o.optString("c").takeIf { it.isNotEmpty() }?.let { File(thumbs, it) }?.takeIf { it.isFile }
        return BookItem(f, o.optString("t").ifEmpty { BookTitles.fromFileName(f) }, o.optString("a"), BookFormat.of(f) ?: BookFormat.TXT, cover)
    }

    private fun readIndex(): JSONObject = try {
        val f = File(dir, INDEX)
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun writeIndex(index: JSONObject) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(index.toString())
            tmp.renameTo(File(dir, INDEX))
        } catch (_: Exception) {}
    }

    companion object {
        const val INDEX = "index.json"
        const val THUMB_PX = 64
        private const val MAX_DEPTH = 8

        fun sorted(items: List<BookItem>): List<BookItem> {
            val c = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
            return items.sortedWith { a, b -> c.compare(a.title, b.title) }
        }

        /** The authors of [items] in A–Z order (an empty author is not one). */
        fun authors(items: List<BookItem>): List<String> {
            val c = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
            return items.map { it.author }.filter { it.isNotBlank() }.distinct().sortedWith { a, b -> c.compare(a, b) }
        }

        /** The cover of [file] at about [maxPx] on its long side, from the book itself (worker thread), or null. */
        fun paneCover(file: File, newParser: () -> XmlPullParser, maxPx: Int): Bitmap? {
            if (BookFormat.of(file) != BookFormat.EPUB) return null
            return try {
                ZipFileBook.read(file) { zip ->
                    val pkg = EpubPackage.read(zip, newParser, withToc = false)
                    val bytes = pkg.coverPath?.let { zip.bytes(it) } ?: return@read null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
