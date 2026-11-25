package com.themoon.y1.managers

import java.security.MessageDigest

/**
 * Pure rules of the album cover cache (no Android dependencies, so they can be unit-tested).
 */
object CoverCacheRules {
    private val HEX = "0123456789abcdef".toCharArray()

    /** Lower-case hex of [bytes], with a table (String.format is slow on the Y2). */
    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    /** MD5 of the UTF-8 bytes of [input], as 32 lower-case hex digits. */
    fun md5Hex(input: String): String =
        toHex(MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)))

    /**
     * Signature of a set of album keys. It does not depend on the order (Cover Flow and the album
     * list sort the albums differently), and it changes when an album is added or removed.
     */
    fun albumListSignature(keys: Iterable<String>): Long {
        var count = 0L
        var sum = 0L
        for (key in keys) {
            count++
            // Spread each hash, so that two changes do not easily cancel.
            sum += (key.hashCode().toLong() and 0xFFFFFFFFL) * -0x61c8864680b583ebL
        }
        return sum xor (count * 0x5851F42D4C957F2DL)
    }

    private const val SIGNATURE_PREFIX = "albums-v1:"

    /** Text of the signature file. The prefix changes when the signature method changes. */
    fun formatSignature(signature: Long): String = SIGNATURE_PREFIX + signature

    /** Signature from the file text, or null when the text is not from [formatSignature]. */
    fun parseSignature(text: String): Long? {
        val t = text.trim()
        if (!t.startsWith(SIGNATURE_PREFIX)) return null
        return t.substring(SIGNATURE_PREFIX.length).toLongOrNull()
    }

    enum class PregenAction { SKIP, EXTRACT }

    /**
     * What thumbnail pre-generation does for one album. [hasNoArtMarker] is only called when there is
     * no thumbnail, so albums with art need no extra file check.
     */
    fun pregenAction(force: Boolean, hasThumb: Boolean, hasNoArtMarker: () -> Boolean): PregenAction = when {
        force -> PregenAction.EXTRACT
        hasThumb -> PregenAction.SKIP
        hasNoArtMarker() -> PregenAction.SKIP
        else -> PregenAction.EXTRACT
    }

    /** The result of a cover search: the [cover], or none. [failed]: a read failed, for example out of memory. */
    class CoverSearch<C : Any>(val cover: C?, val failed: Boolean) {
        /** Only clean reads record "no art": after a failed read the album is searched again later. */
        val recordsNoArt: Boolean get() = cover == null && !failed
    }

    /**
     * Reads [first], then the tracks of [more] (called only when [first] has no cover) until one has a cover. [read]
     * gives null for no cover and throws on a read error. The search continues after an Exception, and stops after an
     * Error (OutOfMemoryError): the next reads would fail too.
     */
    fun <T, C : Any> firstCover(first: T, more: () -> List<T>, read: (T) -> C?): CoverSearch<C> {
        var failed = false
        try {
            for (track in sequenceOf(first) + sequence { yieldAll(more()) }) {
                try {
                    read(track)?.let { return CoverSearch(it, false) }
                } catch (e: Exception) {
                    failed = true
                }
            }
        } catch (t: Throwable) {
            return CoverSearch(null, true)
        }
        return CoverSearch(null, failed)
    }
}
