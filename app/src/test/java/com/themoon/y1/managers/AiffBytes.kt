package com.themoon.y1.managers

import java.io.File

/** Builds AIFF and AIFF-C files in memory for the tests. */
internal object AiffBytes {
    /** [rate] as an 80-bit IEEE extended number, as COMM stores it. */
    fun extended(rate: Int): ByteArray {
        val shift = 31 - Integer.numberOfLeadingZeros(rate)
        val exp = 16383 + shift
        val mantissa = rate.toLong() shl (63 - shift)
        return byteArrayOf((exp shr 8).toByte(), exp.toByte()) + ByteArray(8) { (mantissa ushr (56 - 8 * it)).toByte() }
    }

    fun be(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() }

    /** A chunk: id, big-endian size, body, and a pad byte when the size is odd. [size] overrides the size field. */
    fun chunk(id: String, body: ByteArray, size: Long = body.size.toLong()): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + be(size, 4) + body + (if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    /** A COMM chunk; [type] makes it an AIFF-C one ("NONE", "sowt", "fl32", "ulaw"). */
    fun comm(channels: Int, frames: Long, bits: Int, rate: Int, type: String? = null): ByteArray {
        var body = be(channels.toLong(), 2) + be(frames, 4) + be(bits.toLong(), 2) + extended(rate)
        if (type != null) body += type.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0) // empty Pascal name, padded
        return chunk("COMM", body)
    }

    /** An SSND chunk: offset, block size, then [samples]. [offset] bytes come before the samples. */
    fun ssnd(samples: ByteArray, offset: Int = 0, size: Long? = null): ByteArray {
        val body = be(offset.toLong(), 4) + be(0, 4) + ByteArray(offset) + samples
        return chunk("SSND", body, size ?: body.size.toLong())
    }

    /** The whole file: FORM, its size, "AIFF" or "AIFC" ([aifc]), the [chunks]. */
    fun form(aifc: Boolean, vararg chunks: ByteArray): ByteArray {
        val all = chunks.fold(ByteArray(0)) { a, b -> a + b }
        return "FORM".toByteArray() + be(4L + all.size, 4) + (if (aifc) "AIFC" else "AIFF").toByteArray() + all
    }

    /** A v2.3 ID3 tag with [frames]. */
    fun id3v23(vararg frames: ByteArray): ByteArray {
        val all = frames.fold(ByteArray(0)) { a, b -> a + b }
        val n = all.size
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) +
            byteArrayOf((n shr 21 and 127).toByte(), (n shr 14 and 127).toByte(), (n shr 7 and 127).toByte(), (n and 127).toByte()) + all
    }

    /** A v2.3 frame with a raw [body]. */
    fun v3frame(id: String, body: ByteArray): ByteArray = id.toByteArray() + be(body.size.toLong(), 4) + byteArrayOf(0, 0) + body

    /** A v2.3 UTF-8 text frame. */
    fun text(id: String, value: String): ByteArray = v3frame(id, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))

    fun file(bytes: ByteArray, extension: String = ".aif"): File =
        File.createTempFile("aiff", extension).apply { writeBytes(bytes); deleteOnExit() }
}
