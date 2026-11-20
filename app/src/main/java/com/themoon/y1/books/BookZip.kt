package com.themoon.y1.books

import com.themoon.y1.core.useZip
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Read access to the files of a book archive (an EPUB is a zip). [find] also finds a path whose case differs (books
 * made on Windows link "Images/Cover.JPG" to "images/cover.jpg").
 */
interface BookZip {
    /** The entry name for [path], or null when the book has no such file. */
    fun find(path: String): String?

    /** The bytes of [path], or null. Files over [maxBytes] are not read (null). */
    fun bytes(path: String, maxBytes: Int = MAX_FILE_BYTES): ByteArray?

    /** The uncompressed size of [path] (0 when not known). */
    fun size(path: String): Long

    fun close() {}

    companion object {
        /** A chapter or image larger than this is not read (a broken or hostile file). */
        const val MAX_FILE_BYTES = 24 * 1024 * 1024
    }
}

/** A [BookZip] over a file on the card. Thread-safe: [ZipFile] reads its entries under a lock. */
class ZipFileBook(private val zip: ZipFile) : BookZip {
    private val lower: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val name = e.nextElement().name
            m[name.lowercase(Locale.ROOT)] = name
        }
        m
    }

    override fun find(path: String): String? {
        if (zip.getEntry(path) != null) return path
        return lower[path.lowercase(Locale.ROOT)]
    }

    override fun bytes(path: String, maxBytes: Int): ByteArray? {
        val name = find(path) ?: return null
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > maxBytes) return null
        return try {
            zip.getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream(if (entry.size in 1..maxBytes.toLong()) entry.size.toInt() else 16 * 1024)
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun size(path: String): Long {
        val name = find(path) ?: return 0
        val entry = zip.getEntry(name) ?: return 0
        return if (entry.size >= 0) entry.size else maxOf(entry.compressedSize, 0)
    }

    override fun close() {
        try {
            zip.close()
        } catch (_: Exception) {}
    }

    companion object {
        fun open(file: File): ZipFileBook = ZipFileBook(ZipFile(file))

        /** Opens [file], runs [block], and closes it. */
        fun <R> read(file: File, block: (BookZip) -> R): R = ZipFile(file).useZip { block(ZipFileBook(it)) }
    }
}

/** Paths inside a book. */
object BookPaths {

    /** The folder part of [path] ("OEBPS/Text/ch1.xhtml" → "OEBPS/Text/"; "" at the top). */
    fun dirOf(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" else path.substring(0, i + 1)
    }

    /**
     * The archive path that [href] names from a file in [baseDir]: percent-decoded, "." and ".." resolved, without
     * the fragment. Null for a link out of the book (http:, mailto:, data:) or an empty link.
     */
    fun resolve(baseDir: String, href: String): String? {
        var h = href.trim()
        val hash = h.indexOf('#')
        if (hash >= 0) h = h.substring(0, hash)
        if (h.isEmpty()) return null
        if (SCHEME.containsMatchIn(h)) return null
        h = decode(h)
        val joined = if (h.startsWith("/")) h.substring(1) else baseDir + h
        val out = ArrayList<String>()
        for (part in joined.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += part
            }
        }
        return out.joinToString("/").ifEmpty { null }
    }

    /** The fragment of [href] (after '#'), or null. */
    fun fragment(href: String): String? {
        val i = href.indexOf('#')
        if (i < 0 || i == href.length - 1) return null
        return decode(href.substring(i + 1))
    }

    private fun decode(s: String): String {
        if ('%' !in s) return s
        return try {
            // '+' is a plus in a path, not a space
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            s
        }
    }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
}
