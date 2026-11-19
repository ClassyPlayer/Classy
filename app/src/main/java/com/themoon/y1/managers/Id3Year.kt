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

    private fun parse(f: RandomAccessFile): String? {
        val h = ByteArray(10)
        f.readFully(h)
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
        val version = h[3].toInt()
        if (version != 3 && version != 4) return null
        val end = 10L + syncsafe(h, 6)
        var best: String? = null
        val fh = ByteArray(10)
        while (f.filePointer + 10 <= end) {
            f.readFully(fh)
            if (fh[0].toInt() == 0) break // padding
            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            val size = if (version == 4) syncsafe(fh, 4) else
                ((fh[4].toInt() and 255) shl 24) or ((fh[5].toInt() and 255) shl 16) or ((fh[6].toInt() and 255) shl 8) or (fh[7].toInt() and 255)
            if (size < 0 || f.filePointer + size > end) break
            if ((id == "TDRC" || id == "TYER") && size in 2..64) {
                val data = ByteArray(size)
                f.readFully(data)
                val charset = when (data[0].toInt()) {
                    1 -> Charsets.UTF_16
                    2 -> Charsets.UTF_16BE
                    3 -> Charsets.UTF_8
                    else -> Charsets.ISO_8859_1
                }
                val year = String(data, 1, size - 1, charset).trim { it <= ' ' || it == '\u0000' }.take(4)
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
