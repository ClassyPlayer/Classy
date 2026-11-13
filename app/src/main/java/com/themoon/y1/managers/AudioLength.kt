package com.themoon.y1.managers

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * The length of a track from its own headers, without MediaMetadataRetriever: FLAC STREAMINFO, the granule position of
 * the last Ogg page (Vorbis, Opus) and the MP4 mvhd box. Android 4.4 cannot open Opus with MediaMetadataRetriever.
 * All values are in milliseconds; 0 means "not known".
 */
object AudioLength {
    /** Opus granule positions always count 48 kHz samples. */
    private const val OPUS_RATE = 48_000L

    /** The largest Ogg page is 27 + 255 + 255 * 255 bytes: the end of the file to search for the last page. */
    private const val OGG_TAIL_BYTES = 65_536 + 282

    /** The length from a FLAC STREAMINFO block (the 34 bytes after its block header): total samples / sample rate. */
    fun flacStreamInfoMs(info: ByteArray): Long {
        if (info.size < 18) return 0L
        val rate = (u8(info, 10) shl 12) or (u8(info, 11) shl 4) or (u8(info, 12) ushr 4)
        val samples = ((u8(info, 13) and 0x0F).toLong() shl 32) or
            (u8(info, 14).toLong() shl 24) or (u8(info, 15).toLong() shl 16) or
            (u8(info, 16).toLong() shl 8) or u8(info, 17).toLong()
        return samplesToMs(samples, rate.toLong())
    }

    /** The sample rate of a Vorbis identification header ("\u0001vorbis" at [at]); 0 if [data] does not have one. */
    fun vorbisRate(data: ByteArray, at: Int = 0): Long {
        if (at < 0 || data.size < at + 16 || u8(data, at) != 1 || !matches(data, at + 1, "vorbis")) return 0L
        return le32(data, at + 12)
    }

    /** The pre-skip of an OpusHead packet at [at] (samples at 48 kHz to drop at the start); -1 if [data] does not have one. */
    fun opusPreSkip(data: ByteArray, at: Int = 0): Int {
        if (at < 0 || data.size < at + 12 || !matches(data, at, "OpusHead")) return -1
        return u8(data, at + 10) or (u8(data, at + 11) shl 8)
    }

    /** The length of a Vorbis stream whose last granule position is [granule]. */
    fun vorbisMs(granule: Long, rate: Long): Long = samplesToMs(granule, rate)

    /** The length of an Opus stream whose last granule position is [granule]. */
    fun opusMs(granule: Long, preSkip: Int): Long = samplesToMs(granule - preSkip.coerceAtLeast(0), OPUS_RATE)

    /**
     * The granule position of the last complete Ogg page in [tail] (the end of a file), of the stream [serial] when
     * it is not null; -1 if there is none. Pages with granule -1 (no packet ends in them) are skipped.
     */
    fun lastGranule(tail: ByteArray, serial: Int? = null): Long {
        var i = tail.size - 27
        while (i >= 0) {
            if (u8(tail, i) == 'O'.code && u8(tail, i + 1) == 'g'.code && u8(tail, i + 2) == 'g'.code &&
                u8(tail, i + 3) == 'S'.code && u8(tail, i + 4) == 0 && pageFits(tail, i)
            ) {
                val granule = le64(tail, i + 6)
                if (granule >= 0 && (serial == null || le32(tail, i + 14).toInt() == serial)) return granule
            }
            i--
        }
        return -1L
    }

    /** The granule position of the last page of the Ogg file [file], of the stream [serial] when it is not null. */
    fun lastGranule(file: File, serial: Int? = null): Long = try {
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(raf.length(), OGG_TAIL_BYTES.toLong()).toInt()
            val tail = ByteArray(n)
            raf.seek(raf.length() - n)
            raf.readFully(tail)
            lastGranule(tail, serial)
        }
    } catch (_: Throwable) {
        -1L
    }

    /** The length of an Ogg Vorbis or Opus file from its first page and its last page. */
    fun oggMs(file: File): Long = try {
        val head = ByteArray(27 + 255 + 64)
        val read = RandomAccessFile(file, "r").use { raf -> raf.read(head) }
        if (read < 28 || !matches(head, 0, "OggS")) {
            0L
        } else {
            val serial = le32(head, 14).toInt()
            val first = 27 + u8(head, 26)
            val preSkip = opusPreSkip(head, first)
            val granule = lastGranule(file, serial)
            when {
                granule < 0 -> 0L
                preSkip >= 0 -> opusMs(granule, preSkip)
                else -> vorbisMs(granule, vorbisRate(head, first))
            }
        }
    } catch (_: Throwable) {
        0L
    }

    /** The length of a FLAC file from its STREAMINFO block (always the first block). */
    fun flacMs(file: File): Long = try {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(4 + 4 + 34)
            raf.readFully(head)
            if (!matches(head, 0, "fLaC") || (u8(head, 4) and 0x7F) != 0) 0L
            else flacStreamInfoMs(head.copyOfRange(8, head.size))
        }
    } catch (_: Throwable) {
        0L
    }

    /** The length from the content of an MP4 mvhd box (after its 8-byte box header): duration / timescale. */
    fun mvhdMs(box: ByteArray): Long {
        if (box.isEmpty()) return 0L
        return if (u8(box, 0) == 1) {
            if (box.size < 32) 0L else samplesToMs(be64(box, 24), be32(box, 20))
        } else {
            if (box.size < 20) 0L else samplesToMs(be32(box, 16), be32(box, 12))
        }
    }

    /** The length of an MP4 file (M4A, M4B) from the mvhd box in its moov box. */
    fun mp4Ms(file: File): Long = try {
        RandomAccessFile(file, "r").use { raf ->
            val end = raf.length()
            var pos = 0L
            var result = 0L
            while (pos + 8 <= end) {
                raf.seek(pos)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }
                val name = String(type, Charsets.ISO_8859_1)
                // 1: a 64-bit size follows (a large mdat box); 0: the box goes to the end of the file
                if (size == 1L) size = raf.readLong() else if (size == 0L) size = end - pos
                if (name == "moov") {
                    pos += 8
                    continue
                }
                if (name == "mvhd") {
                    val box = ByteArray(minOf(size - 8, 32L).toInt().coerceAtLeast(0))
                    raf.readFully(box)
                    result = mvhdMs(box)
                    break
                }
                if (size < 8) break
                pos += size
            }
            result
        }
    } catch (_: Throwable) {
        0L
    }

    /** The length of [file] from its headers when its type has one of the readers above; null for other types. */
    fun fromHeaders(file: File): Long? {
        val name = file.name.lowercase(Locale.US)
        return when {
            name.endsWith(".flac") -> flacMs(file)
            name.endsWith(".ogg") || name.endsWith(".opus") -> oggMs(file)
            name.endsWith(".m4a") || name.endsWith(".m4b") || name.endsWith(".alac") -> mp4Ms(file)
            else -> null
        }
    }

    private fun samplesToMs(samples: Long, rate: Long): Long =
        if (samples <= 0L || rate <= 0L) 0L else samples * 1000L / rate

    private fun pageFits(data: ByteArray, at: Int): Boolean {
        if (at + 27 > data.size) return false
        val segments = u8(data, at + 26)
        if (at + 27 + segments > data.size) return false
        var body = 0
        for (s in 0 until segments) body += u8(data, at + 27 + s)
        return at + 27 + segments + body <= data.size
    }

    private fun matches(data: ByteArray, at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > data.size) return false
        for (k in text.indices) if (data[at + k] != text[k].code.toByte()) return false
        return true
    }

    private fun u8(data: ByteArray, at: Int): Int = data[at].toInt() and 0xFF

    private fun le32(data: ByteArray, at: Int): Long =
        (u8(data, at).toLong()) or (u8(data, at + 1).toLong() shl 8) or
            (u8(data, at + 2).toLong() shl 16) or (u8(data, at + 3).toLong() shl 24)

    private fun le64(data: ByteArray, at: Int): Long = le32(data, at) or (le32(data, at + 4) shl 32)

    private fun be32(data: ByteArray, at: Int): Long =
        (u8(data, at).toLong() shl 24) or (u8(data, at + 1).toLong() shl 16) or
            (u8(data, at + 2).toLong() shl 8) or u8(data, at + 3).toLong()

    private fun be64(data: ByteArray, at: Int): Long = (be32(data, at) shl 32) or be32(data, at + 4)
}
