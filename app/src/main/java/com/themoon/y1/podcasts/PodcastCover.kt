package com.themoon.y1.podcasts

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The show cover that Now Playing shows for an episode, downloaded or streamed: Podcasts/<show>/cover.jpg.
 * Opening the show keeps it once as a JPEG of at most [MAX_SIDE] px (PodcastRepository), so every later read
 * decodes a small baseline JPEG. Feed covers are 1400 to 3000 px, in any format.
 */
internal object PodcastCover {
    const val FILE_NAME = "cover.jpg"

    /** The longer side of the kept cover: Now Playing shows the art at 300 px or less. */
    const val MAX_SIDE = 500

    /** A download over this is not a cover: it is not kept. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * The pixels of a GIF or an interlaced PNG that are decoded: Android 4.4 decodes these whole before it samples
     * them (16 MB at 2048 x 2048), so a bigger one is not decoded.
     */
    const val MAX_WHOLE_PIXELS = 2048 * 2048

    /**
     * The pixels of any other image that are decoded. A sample size makes the bitmap small, but a progressive JPEG
     * keeps the coefficients of the whole image while it decodes. Feed covers are at most 3000 x 3000.
     */
    const val MAX_SOURCE_PIXELS = 4096 * 4096

    /** The first bytes of a file that [canDecode] reads: a PNG header to its interlace byte. */
    const val HEAD_BYTES = 29

    /** The JPEG quality of the kept cover. */
    const val JPEG_QUALITY = 90

    /** True for a cover that is already kept: a JPEG of at most [MAX_SIDE] px. */
    fun isKept(mimeType: String?, width: Int, height: Int): Boolean =
        mimeType == "image/jpeg" && width in 1..MAX_SIDE && height in 1..MAX_SIDE

    /**
     * True for an image that may be decoded, from its bounds and its first bytes ([head], [HEAD_BYTES] or more):
     * [MAX_WHOLE_PIXELS] for an image that Android 4.4 decodes whole, [MAX_SOURCE_PIXELS] for any other.
     * The kept cover and a cover that an older version kept as the feed gave it are both read through this.
     */
    fun canDecode(mimeType: String?, width: Int, height: Int, head: ByteArray): Boolean {
        if (width <= 0 || height <= 0) return false
        val decodesWhole = mimeType == "image/gif" || isInterlacedPng(head)
        return width.toLong() * height <= if (decodesWhole) MAX_WHOLE_PIXELS else MAX_SOURCE_PIXELS
    }

    /** True for a PNG whose header (IHDR) says Adam7 interlacing. */
    fun isInterlacedPng(bytes: ByteArray): Boolean {
        if (bytes.size < HEAD_BYTES) return false
        for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return false
        // Chunk length (4 bytes), "IHDR", width, height, depth, colour type, compression, filter, interlace
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()) return false
        return bytes[28].toInt() == 1
    }

    /** The largest power of two that keeps the longer side at [maxSide] or more: the decode is then scaled down. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        val longer = Math.max(width, height)
        var sample = 1
        while (longer / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** [width] x [height] scaled to fit [maxSide], never up and never below 1 px. */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longer = Math.max(width, height)
        if (longer <= maxSide) return width to height
        val scale = maxSide.toDouble() / longer
        return Math.max(1, Math.round(width * scale).toInt()) to Math.max(1, Math.round(height * scale).toInt())
    }

    /** The bytes of [input], or null over [limit]: a server's Content-Length can be missing or wrong. */
    fun readAtMost(input: InputStream, limit: Int = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) return null
            out.write(buffer, 0, n)
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A)
}
