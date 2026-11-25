package com.themoon.y1.managers

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * AIFF and AIFF-C files (uncompressed PCM, from iTunes CD rips and studio tools): the file type, the COMM chunk and
 * where the other chunks are. No Android types: [AiffExtractor] (playback), [Id3] (the tag in the "ID3 " chunk),
 * [AudioLength] and the library scan use it, and the JVM tests run it directly.
 *
 * A file is one "FORM" chunk: its big-endian size, "AIFF" or "AIFC", then chunks of a 4-byte id, a 4-byte big-endian
 * size and the body, padded to an even size. COMM gives the format, SSND the samples (after its offset and block size
 * fields), and taggers add an "ID3 " chunk with a whole ID3v2 tag, before or after SSND (iTunes puts it at the end).
 */
object Aiff {
    /** The most of a COMM chunk that is read: AIFF-C adds the compression name, a Pascal string of up to 255 bytes. */
    private const val MAX_COMM = 512

    /** The most of a NAME or AUTH chunk that is read. */
    private const val MAX_TEXT = 1024

    /** At most this many chunks are walked, so a broken file cannot keep the walk going. */
    private const val MAX_CHUNKS = 512

    /** The extensions of AIFF files: .aif, .aiff and .aifc (AIFF-C). */
    val EXTENSIONS: Set<String> = setOf("aif", "aiff", "aifc")

    /** True when the file [name] has an AIFF extension, in any case. */
    fun isAiffName(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.US) in EXTENSIONS

    /** How the samples are stored. */
    enum class Encoding {
        /** Big-endian signed integers: AIFF, and AIFF-C "NONE" or "twos". */
        INT_BE,

        /** Little-endian signed integers: AIFF-C "sowt". */
        INT_LE,

        /** Big-endian 32-bit floats: AIFF-C "fl32". */
        FLOAT_BE,

        /** Compressed (ulaw, alaw, ima4), 64-bit floats or an unknown type: not played. */
        UNSUPPORTED
    }

    /** The COMM chunk: [bits] per sample, stored in [bytesPerSample] bytes (12-bit samples fill 2 bytes, left-justified). */
    class Comm(val channels: Int, val frames: Long, val bits: Int, val sampleRate: Int, val encoding: Encoding) {
        val bytesPerSample: Int get() = (bits + 7) / 8
        val blockAlign: Int get() = channels * bytesPerSample

        /** True when [AiffExtractor] can play it. */
        val isPlayable: Boolean
            get() = channels in 1..8 && sampleRate in 1..768_000 && when (encoding) {
                Encoding.INT_BE, Encoding.INT_LE -> bits in 1..32
                Encoding.FLOAT_BE -> bits == 32
                Encoding.UNSUPPORTED -> false
            }
    }

    /**
     * The COMM chunk [body]: channels (16 bits), sample frames (32), sample size (16), the 80-bit sample rate, then in
     * AIFF-C ([aifc]) the compression type. Null when the body is too short or the values cannot be a sound.
     */
    fun parseComm(body: ByteArray, aifc: Boolean): Comm? {
        if (body.size < 18) return null
        val channels = be16(body, 0)
        val frames = be32(body, 2)
        val bits = be16(body, 6)
        val rate = extendedToRate(body, 8)
        if (channels <= 0 || bits <= 0 || rate <= 0) return null
        var encoding = Encoding.INT_BE
        if (aifc) {
            if (body.size < 22) return null
            encoding = when (String(body, 18, 4, Charsets.ISO_8859_1)) {
                "NONE", "twos" -> Encoding.INT_BE
                // sowt is little-endian; one byte has no order
                "sowt" -> if (bits <= 8) Encoding.INT_BE else Encoding.INT_LE
                "fl32", "FL32" -> Encoding.FLOAT_BE
                else -> Encoding.UNSUPPORTED
            }
        }
        return Comm(channels, frames, bits, rate, encoding)
    }

    /**
     * The sample rate in the 80-bit IEEE 754 extended number at [off] (sign and 15-bit exponent, then a 64-bit mantissa
     * with its integer bit), rounded to whole Hz. 0 when it is negative, not finite or above 10 MHz.
     */
    fun extendedToRate(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 10 > b.size) return 0
        val signExp = be16(b, off)
        if (signExp and 0x8000 != 0) return 0
        val exp = signExp and 0x7FFF
        var mantissa = 0L
        for (i in 0 until 8) mantissa = (mantissa shl 8) or (b[off + 2 + i].toLong() and 255)
        if (mantissa == 0L || exp == 0x7FFF) return 0
        // The mantissa as an unsigned 64-bit number, then times 2^(exp - 16383 - 63)
        val unsigned = (mantissa ushr 1).toDouble() * 2.0 + (mantissa and 1L).toDouble()
        val value = unsigned * Math.pow(2.0, (exp - 16383 - 63).toDouble())
        return if (value.isNaN() || value > 10_000_000.0) 0 else Math.round(value).toInt()
    }

    /**
     * Where the parts of an AIFF file are. [dataStart] is the first sample byte (-1 without SSND), [dataEnd] the end of
     * the samples: the end of SSND, cut to the file and to the [Comm.frames] of COMM. [id3Offset] is the start of the
     * ID3v2 tag in the "ID3 " chunk (-1 without one). [name] and [author] are the old NAME and AUTH text chunks.
     */
    class Layout(
        val aifc: Boolean,
        val comm: Comm?,
        val dataStart: Long,
        val dataEnd: Long,
        val id3Offset: Long,
        val id3Size: Long,
        val name: String?,
        val author: String?
    ) {
        /** The sample frames: from COMM, or from the size of SSND when COMM gives 0 (a recording that was not closed). */
        val frames: Long
            get() {
                val c = comm ?: return 0L
                if (c.frames > 0L || dataStart < 0L || c.blockAlign <= 0) return c.frames
                return (dataEnd - dataStart).coerceAtLeast(0L) / c.blockAlign
            }

        val durationMs: Long
            get() {
                val rate = comm?.sampleRate ?: 0
                return if (rate <= 0) 0L else frames * 1000L / rate
            }
    }

    /** True when [head] (at least 12 bytes) starts an AIFF or AIFF-C file; [isAifc] tells which. */
    fun isFormHeader(head: ByteArray): Boolean =
        head.size >= 12 && matches(head, 0, "FORM") && (matches(head, 8, "AIFF") || matches(head, 8, "AIFC"))

    fun isAifc(head: ByteArray): Boolean = head.size >= 12 && matches(head, 8, "AIFC")

    /** The [Layout] of the AIFF file open in [f], or null when it is not one. The file position is left anywhere. */
    fun locate(f: RandomAccessFile): Layout? {
        val length = f.length()
        if (length < 12) return null
        val head = ByteArray(12)
        f.seek(0)
        f.readFully(head)
        if (!isFormHeader(head)) return null
        val aifc = isAifc(head)
        var comm: Comm? = null
        var dataStart = -1L
        var ssndEnd = -1L
        var id3Offset = -1L
        var id3Size = 0L
        var name: String? = null
        var author: String? = null
        val ch = ByteArray(8)
        var pos = 12L
        var chunks = 0
        while (pos + 8 <= length && chunks++ < MAX_CHUNKS) {
            f.seek(pos)
            f.readFully(ch)
            val id = String(ch, 0, 4, Charsets.ISO_8859_1)
            val size = be32(ch, 4)
            val body = pos + 8
            val available = (length - body).coerceAtLeast(0L)
            when (id) {
                "COMM" -> if (comm == null) {
                    val b = ByteArray(minOf(size, available, MAX_COMM.toLong()).toInt())
                    f.readFully(b)
                    comm = parseComm(b, aifc)
                }
                "SSND" -> if (dataStart < 0 && available >= 8) {
                    val fields = ByteArray(8)
                    f.readFully(fields)
                    dataStart = body + 8 + be32(fields, 0)
                    // A size of 0 (a recording that was not closed): the samples go to the end of the file
                    ssndEnd = if (size < 8) length else minOf(body + size, length)
                }
                "ID3 ", "id3 " -> if (id3Offset < 0 && size >= 10) {
                    id3Offset = body
                    id3Size = minOf(size, available)
                }
                "NAME" -> if (name == null) name = text(f, minOf(size, available))
                "AUTH" -> if (author == null) author = text(f, minOf(size, available))
            }
            // After an SSND of no size, the rest of the file is samples
            pos = if (id == "SSND" && size < 8) length else body + size + (size and 1L)
        }
        var dataEnd = ssndEnd
        val c = comm
        if (dataStart >= 0 && c != null && c.frames > 0 && c.blockAlign > 0) {
            dataEnd = minOf(dataEnd, dataStart + c.frames * c.blockAlign)
        }
        if (dataStart > dataEnd) dataEnd = dataStart
        return Layout(aifc, comm, dataStart, dataEnd, id3Offset, id3Size, name, author)
    }

    /** The [Layout] of [file], or null when it is not an AIFF file or cannot be read. */
    fun layout(file: File): Layout? = try {
        RandomAccessFile(file, "r").use { locate(it) }
    } catch (_: Throwable) {
        null
    }

    /** The length of [file] from its COMM chunk; 0 when it is not known. */
    fun durationMs(file: File): Long = layout(file)?.durationMs ?: 0L

    /**
     * The tags of an AIFF [file] in the slots of AudioPlayerManager.extractFlacMetadata: the ID3v2 tag of its
     * "ID3 " chunk ([Id3.readTags]), else the title and artist of the NAME and AUTH chunks, and the length (slot 11).
     * With [includeArt], a read error throws when no cover was found, as the other readers of embedded art do.
     */
    fun readTags(file: File, includeArt: Boolean): Array<Any?> {
        val tags = Id3.readTags(file, includeArt)
        val layout = layout(file)
        if (layout != null) {
            if ((tags[0] as? String).isNullOrBlank() && !layout.name.isNullOrBlank()) tags[0] = layout.name
            if ((tags[1] as? String).isNullOrBlank() && !layout.author.isNullOrBlank()) tags[1] = layout.author
            tags[11] = layout.durationMs
        }
        return tags
    }

    /** A NAME or AUTH chunk of [size] bytes at the file position: plain text, in practice ASCII or Latin-1. */
    private fun text(f: RandomAccessFile, size: Long): String? {
        val b = ByteArray(minOf(size, MAX_TEXT.toLong()).toInt())
        f.readFully(b)
        var end = b.size
        while (end > 0 && (b[end - 1].toInt() == 0 || b[end - 1].toInt() == ' '.code)) end--
        return if (end == 0) null else String(b, 0, end, Charsets.ISO_8859_1).trim()
    }

    private fun matches(b: ByteArray, off: Int, s: String): Boolean =
        off + s.length <= b.size && s.indices.all { b[off + it].toInt() == s[it].code }

    private fun be16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)

    /** An unsigned big-endian 32-bit number. */
    internal fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 255) shl 24) or ((b[o + 1].toLong() and 255) shl 16) or
            ((b[o + 2].toLong() and 255) shl 8) or (b[o + 3].toLong() and 255)
}
