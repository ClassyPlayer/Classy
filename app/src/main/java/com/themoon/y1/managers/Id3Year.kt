package com.themoon.y1.managers

import java.io.File
import java.io.RandomAccessFile

/**
 * The year of an MP3 from its ID3v2.3/v2.4 tag (TYER, or TDRC with a full date). Android 4.4's metadata reader
 * does not give the year of a v2.4 tag. Null when there is none. Frames are skipped by seeking, so cover art costs nothing.
 * Not handled: ID3v2.2 (TYE), and the v2.4 files of an old iTunes bug that store plain big-endian frame sizes instead
 * of syncsafe ones (the walk loses its place there and finds no year).
 */
object Id3Year {
    /** v2.3 tags with the unsynchronisation flag are read whole to undo it; a larger one gives no year. */
    private const val MAX_UNSYNC_TAG = 4 * 1024 * 1024

    fun read(file: File): String? = try {
        RandomAccessFile(file, "r").use { parse(it) }
    } catch (_: Throwable) { // also OutOfMemoryError: the unsynchronised path holds the tag a few times over
        null
    }

    /** Where the frames are read from: the file, or the tag in memory after its unsynchronisation was undone. */
    private class Cursor(private val f: RandomAccessFile?, private val mem: ByteArray?) {
        private var p = 0
        var pos: Long
            get() = f?.filePointer ?: p.toLong()
            set(v) { if (f != null) f.seek(v) else p = v.toInt() }
        fun readFully(b: ByteArray) {
            if (f != null) f.readFully(b) else {
                if (p + b.size > mem!!.size) throw java.io.EOFException()
                System.arraycopy(mem, p, b, 0, b.size)
                p += b.size
            }
        }
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)

    /** Unsynchronisation puts a 00 after every FF; takes them out again. */
    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun parse(f: RandomAccessFile): String? {
        val h = ByteArray(10)
        f.readFully(h)
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
        val version = h[3].toInt()
        if (version != 3 && version != 4) return null
        val tagFlags = h[5].toInt()
        var end = 10L + syncsafe(h, 6)
        var c = Cursor(f, null)
        if (version == 3 && tagFlags and 0x80 != 0) {
            // v2.3 unsynchronises the whole tag, and the sizes inside it are those of the original data
            val n = end - 10
            if (n > MAX_UNSYNC_TAG) return null
            val raw = ByteArray(n.toInt())
            f.readFully(raw)
            val tag = deUnsync(raw)
            c = Cursor(null, h + tag)
            c.pos = 10
            end = 10L + tag.size
        }
        if (tagFlags and 0x40 != 0) {
            // Extended header: v2.4 gives its size with the size field, v2.3 without it
            val eh = ByteArray(4)
            c.readFully(eh)
            val size = if (version == 4) syncsafe(eh, 0).toLong() else be32(eh, 0) + 4L
            if (size < 4 || 10L + size > end) return null
            c.pos = 10L + size
        }
        var best: String? = null
        val fh = ByteArray(10)
        try {
            while (c.pos + 10 <= end) {
                c.readFully(fh)
                if (fh[0].toInt() == 0) break // padding
                val id = String(fh, 0, 4, Charsets.ISO_8859_1)
                val size = if (version == 4) syncsafe(fh, 4) else be32(fh, 4)
                if (size < 0 || c.pos + size > end) break
                if ((id == "TDRC" || id == "TYER") && size in 2..128) {
                    val data = ByteArray(size)
                    c.readFully(data)
                    val fmt = fh[9].toInt() and 255
                    val v4 = version == 4
                    val packed = if (v4) fmt and 0x0C != 0 else fmt and 0xC0 != 0 // compressed or encrypted: skip
                    var skip = 0
                    if (if (v4) fmt and 0x40 != 0 else fmt and 0x20 != 0) skip += 1 // group identifier
                    if (v4 && fmt and 0x01 != 0) skip += 4 // data length indicator
                    var body = if (packed || skip >= size) continue else data.copyOfRange(skip, size)
                    // v2.4 unsynchronisation: the frame flag, or the tag flag (some taggers set only the tag flag)
                    if (v4 && (fmt and 0x02 != 0 || tagFlags and 0x80 != 0)) body = deUnsync(body)
                    if (body.size < 2) continue
                    val charset = when (body[0].toInt()) {
                        1 -> Charsets.UTF_16
                        2 -> Charsets.UTF_16BE
                        3 -> Charsets.UTF_8
                        else -> Charsets.ISO_8859_1
                    }
                    // The trim also removes a UTF-8 BOM (UTF-16 decoding removes its own BOM)
                    val year = String(body, 1, body.size - 1, charset).trim { it <= ' ' || it == '\uFEFF' }.take(4)
                    if (year.length == 4 && year.all { it in '0'..'9' }) {
                        if (id == "TDRC") return year
                        best = year
                    }
                } else {
                    c.pos = c.pos + size
                }
            }
        } catch (_: java.io.EOFException) { // a truncated file: keep a TYER year found before the cut
        }
        return best
    }
}
