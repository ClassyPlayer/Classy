package com.themoon.y1.books

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

/** The file types the reader opens. */
enum class BookFormat {
    EPUB, TXT;

    companion object {
        fun of(file: File): BookFormat? = when (file.name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "epub" -> EPUB
            "txt" -> TXT
            else -> null
        }
    }
}

/**
 * An open book: its sections in reading order, their weights (bytes, for the %), its table of contents, and its
 * images. [section] and [image] read the card: call them on a worker thread.
 */
interface BookSource {
    val format: BookFormat
    val title: String
    val author: String
    val language: String
    val sectionCount: Int
    val weights: LongArray
    val toc: List<TocEntry>

    fun section(index: Int): StyledText

    /** The bytes of an image of the book (EPUB), or null. */
    fun image(path: String): ByteArray? = null

    fun close() {}
}

class EpubSource(private val zip: BookZip, private val pkg: EpubPackage) : BookSource {
    override val format = BookFormat.EPUB
    override val title: String get() = pkg.title
    override val author: String get() = pkg.author
    override val language: String get() = pkg.language
    override val sectionCount: Int get() = pkg.spine.size
    override val weights: LongArray = LongArray(pkg.spine.size) { pkg.spine[it].size }
    override val toc: List<TocEntry> get() = pkg.toc

    override fun section(index: Int): StyledText {
        val item = pkg.spine.getOrNull(index) ?: return StyledText.EMPTY
        val bytes = zip.bytes(item.path) ?: return StyledText.EMPTY
        val dir = BookPaths.dirOf(item.path)
        return XhtmlConverter.convert(bytes) { src -> BookPaths.resolve(dir, src)?.let { zip.find(it) } }
    }

    override fun image(path: String): ByteArray? = zip.bytes(path)

    override fun close() = zip.close()
}

class TxtSource(private val file: File, private val index: TxtIndex) : BookSource {
    private val charset: Charset = Charset.forName(index.charset)
    override val format = BookFormat.TXT
    override val title: String = BookTitles.fromFileName(file)
    override val author: String = ""
    override val language: String = ""
    override val sectionCount: Int get() = index.sectionCount
    override val weights: LongArray = LongArray(index.sectionCount) { index.starts[it + 1] - index.starts[it] }
    override val toc: List<TocEntry> get() = index.headings

    override fun section(i: Int): StyledText {
        if (i !in 0 until sectionCount) return StyledText.EMPTY
        return TxtFormatter.format(TxtIndex.readSection(file, index.starts[i], index.starts[i + 1], charset))
    }
}

object BookTitles {
    /** A title from a file name: no extension, "_" as spaces. */
    fun fromFileName(file: File): String = file.name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { file.name }
}

/** Opens a book file for reading. */
object BookOpener {

    /**
     * Opens [file] (worker thread). [cacheDir] keeps the TXT indexes; [language] is the UI language file name (the
     * TXT encoding fallback). Throws [BookException] for a book that cannot be read (DRM: [DrmException]).
     */
    fun open(file: File, cacheDir: File, language: String?, newParser: () -> XmlPullParser, cancelled: () -> Boolean = { false }): BookSource {
        return when (BookFormat.of(file)) {
            BookFormat.EPUB -> {
                val zip = try {
                    ZipFileBook.open(file)
                } catch (e: Exception) {
                    throw BookException("Not a zip: ${e.message}")
                }
                try {
                    val pkg = EpubPackage.read(zip, newParser)
                    if (pkg.isDrm) throw DrmException()
                    EpubSource(zip, pkg)
                } catch (e: Throwable) {
                    zip.close()
                    if (e is BookException) throw e
                    throw BookException(e.message ?: e.javaClass.simpleName)
                }
            }
            BookFormat.TXT -> TxtSource(file, txtIndex(file, cacheDir, language, cancelled))
            null -> throw BookException("Unknown format")
        }
    }

    /** The TXT index of [file] from [cacheDir], or built now and saved there. */
    fun txtIndex(file: File, cacheDir: File, language: String?, cancelled: () -> Boolean = { false }): TxtIndex {
        val cache = File(cacheDir, "txt_" + BookKeys.hash(file.absolutePath) + ".json")
        if (cache.isFile) {
            try {
                TxtIndex.fromJson(JSONObject(cache.readText()), file)?.let { return it }
            } catch (_: Exception) {}
        }
        val index = TxtIndex.build(file, language, cancelled)
        if (!cancelled()) {
            try {
                cacheDir.mkdirs()
                val tmp = File(cache.path + ".tmp")
                tmp.writeText(index.toJson().toString())
                tmp.renameTo(cache)
            } catch (_: Exception) {}
        }
        return index
    }
}

class DrmException : BookException("DRM")

object BookKeys {
    /** A short stable name for a path (cache files). */
    fun hash(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 10) sb.append(String.format(Locale.US, "%02x", bytes[i]))
        return sb.toString()
    }
}
