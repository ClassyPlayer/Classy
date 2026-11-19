package com.themoon.y1.managers

import java.io.File
import java.io.RandomAccessFile

/**
 * The year of an MP3 from its ID3v2.3/v2.4 tag (TYER, or TDRC with a full date). Android 4.4's metadata reader
 * does not give the year of a v2.4 tag. Null when there is none. Frames are skipped by seeking, so cover art costs nothing.
 */
object Id3Year {
    fun read(file: File): String? = try {
        RandomAccessFile(file, "r").use { parse(it) }
    } catch (_: Exception) {
        null
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        (b[o].toInt() shl 21) or (b[o + 1].toInt() shl 14) or (b[o + 2].toInt() shl 7) or b[o + 3].toInt()

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
        val tagUnsync = tagFlags and 0x80 != 0
        val end = 10L + syncsafe(h, 6)
        if (tagFlags and 0x40 != 0) {
            // Extended header: v2.4 gives its size with the size field, v2.3 without it
            val eh = ByteArray(4)
            f.readFully(eh)
            val size = if (version == 4) syncsafe(eh, 0).toLong() else be32(eh, 0) + 4L
            if (size < 4 || 10L + size > end) return null
            f.seek(10L + size)
        }
        var best: String? = null
        val fh = ByteArray(10)
        while (f.filePointer + 10 <= end) {
            f.readFully(fh)
            if (fh[0].toInt() == 0) break // padding
            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            val size = if (version == 4) syncsafe(fh, 4) else be32(fh, 4)
            if (size < 0 || f.filePointer + size > end) break
            if ((id == "TDRC" || id == "TYER") && size in 2..128) {
                val data = ByteArray(size)
                f.readFully(data)
                val fmt = fh[9].toInt() and 255
                val v4 = version == 4
                val packed = if (v4) fmt and 0x0C != 0 else fmt and 0xC0 != 0 // compressed or encrypted: skip
                var skip = 0
                if (if (v4) fmt and 0x40 != 0 else fmt and 0x20 != 0) skip += 1 // group identifier
                if (v4 && fmt and 0x01 != 0) skip += 4 // data length indicator
                var body = if (packed || skip >= size) continue else data.copyOfRange(skip, size)
                if (tagUnsync || (v4 && fmt and 0x02 != 0)) body = deUnsync(body)
                if (body.size < 2) continue
                val charset = when (body[0].toInt()) {
                    1 -> Charsets.UTF_16
                    2 -> Charsets.UTF_16BE
                    3 -> Charsets.UTF_8
                    else -> Charsets.ISO_8859_1
                }
                val year = String(body, 1, body.size - 1, charset).trim { it <= ' ' || it == '\u0000' }.take(4)
                if (year.length == 4 && year.all(Char::isDigit)) {
                    if (id == "TDRC") return year
                    best = year
                }
            } else {
                f.seek(f.filePointer + size)
            }
        }
        return best
    }
}
