package com.themoon.y1.managers

import com.themoon.y1.controllers.player.LyricsText
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads frames of the ID3v2.3/v2.4 tag at the start of a file: the year and the release type (library scan), the lyrics
 * (lyrics thread) and the MusicBrainz album id (cast thread). Frames that are not wanted are skipped by seeking, so cover art costs nothing.
 * Handled: the extended header, v2.3 32-bit and v2.4 syncsafe frame sizes, frame and tag unsynchronisation, the group
 * id and data length prefixes, and the four text encodings with their BOMs. A broken or cut tag does not throw: the walk
 * stops and keeps what it found.
 * Not handled: ID3v2.2 (3-letter frame ids), compressed or encrypted frames, and the v2.4 files of an old iTunes bug that
 * store plain big-endian frame sizes instead of syncsafe ones (the walk loses its place there).
 */
object Id3 {
    /** v2.3 tags with the unsynchronisation flag are read into memory to undo it, at most this much of them. */
    private const val MAX_UNSYNC_TAG = 4 * 1024 * 1024
    private const val MAX_YEAR_FRAME = 128
    private const val MAX_LYRICS_FRAME = 512 * 1024
    private const val MAX_TXXX_FRAME = 1024
    private val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** The year (TDRC, which can be a full date, or TYER), or null. Android 4.4's metadata reader gives none for v2.4. */
    fun year(file: File): String? {
        var best: String? = null
        forEachFrame(file, setOf("TDRC", "TYER"), MAX_YEAR_FRAME) { id, body ->
            val year = yearOf(body)
            if (year != null) {
                best = year
                id != "TDRC" // TDRC wins over TYER
            } else true
        }
        return best
    }

    /** What the library scan reads from the tag of an MP3 besides Android's metadata reader, in one walk. */
    class ScanTags(val year: String?, val releaseType: String?)

    /**
     * The year (as [year]) and the release type: the values of the TXXX frame "MusicBrainz Album Type" (Picard, beets)
     * or "RELEASETYPE" (Mp3tag), joined with ";" (null when there is none). See ReleaseTypes for the values.
     */
    fun scanTags(file: File): ScanTags {
        var year: String? = null
        var yearFromTdrc = false
        var releaseType: String? = null
        forEachFrame(file, SCAN_FRAMES, MAX_TXXX_FRAME) { id, body ->
            if (id == "TXXX") {
                val fields = strings(body)
                if (fields.size >= 2 && RELEASE_TYPE_DESCRIPTIONS.any { fields[0].equals(it, ignoreCase = true) }) {
                    val value = fields.subList(1, fields.size).joinToString(";")
                    releaseType = if (releaseType == null) value else "$releaseType;$value"
                }
            } else if (!yearFromTdrc) {
                val found = yearOf(body)
                if (found != null) {
                    year = found
                    yearFromTdrc = id == "TDRC" // TDRC wins over TYER
                }
            }
            true
        }
        return ScanTags(year, releaseType)
    }

    private val SCAN_FRAMES = setOf("TDRC", "TYER", "TXXX")
    private val RELEASE_TYPE_DESCRIPTIONS = listOf("MusicBrainz Album Type", "RELEASETYPE")

    /** The 4-digit year at the start of a TDRC or TYER [body], or null. */
    private fun yearOf(body: ByteArray): String? {
        val year = strings(body).firstOrNull()?.trim { it <= ' ' || it == '\uFEFF' }?.take(4)
        return if (year != null && year.length == 4 && year.all { it in '0'..'9' }) year else null
    }

    /** The text of the first USLT frame with visible lyrics, or null. */
    fun lyrics(file: File): String? {
        var lyrics: String? = null
        forEachFrame(file, setOf("USLT"), MAX_LYRICS_FRAME) { _, body ->
            // Encoding, 3-byte language, description, lyrics
            lyrics = LyricsText.plain(strings(body, skip = 3, limit = 2).getOrNull(1))
            lyrics == null
        }
        return lyrics
    }

    /** The "MusicBrainz Album Id" TXXX frame (beets writes it), or null. */
    fun musicBrainzAlbumId(file: File): String? {
        var mbid: String? = null
        forEachFrame(file, setOf("TXXX"), MAX_TXXX_FRAME) { _, body ->
            val fields = strings(body)
            if (fields.size >= 2 && fields[0].equals("MusicBrainz Album Id", ignoreCase = true)) {
                mbid = fields[1].trim().takeIf { MBID.matches(it) }
            }
            mbid == null
        }
        return mbid
    }

    /**
     * Calls [visit] with the id and the body of each frame in [ids] of at most [maxSize] bytes, in tag order, until it
     * returns false. The body has no group id or data length prefix and no unsynchronisation; in a text frame, body[0]
     * is the encoding ([strings]). Errors stop the walk: also OutOfMemoryError, as the v2.3 unsynchronised path holds
     * the tag a few times over.
     */
    internal fun forEachFrame(file: File, ids: Set<String>, maxSize: Int, visit: (id: String, body: ByteArray) -> Boolean) {
        try {
            RandomAccessFile(file, "r").use { walk(it, ids, maxSize, visit) }
        } catch (_: Throwable) {
        }
    }

    /**
     * The strings of a text frame [body]: body[0] is the encoding, then [skip] bytes (3 for the language of USLT), then
     * strings that end with a NUL (two NULs in UTF-16). At most [limit] strings: the last one takes the rest. Each
     * string loses its own BOM; a UTF-16 string with no BOM keeps the byte order of the string before it.
     */
    internal fun strings(body: ByteArray, skip: Int = 0, limit: Int = Int.MAX_VALUE): List<String> {
        if (body.isEmpty()) return emptyList()
        val enc = body[0].toInt()
        val step = if (enc == 1 || enc == 2) 2 else 1
        val out = ArrayList<String>()
        var littleEndian = false
        var pos = 1 + skip
        while (pos < body.size) {
            var end = body.size
            var next = body.size
            if (out.size < limit - 1) {
                var i = pos
                while (i + step <= body.size) {
                    if (body[i].toInt() == 0 && body[i + step - 1].toInt() == 0) {
                        end = i
                        next = i + step
                        break
                    }
                    i += step
                }
            }
            var from = pos
            fun startsWith(vararg bom: Int) = end - from >= bom.size && bom.indices.all { body[from + it].toInt() and 255 == bom[it] }
            out.add(when (enc) {
                // 1 = UTF-16 with a BOM, 2 = UTF-16BE (some taggers add a BOM there too)
                1, 2 -> {
                    if (startsWith(0xFF, 0xFE)) { littleEndian = true; from += 2 }
                    else if (startsWith(0xFE, 0xFF)) { littleEndian = false; from += 2 }
                    String(body, from, end - from, if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE)
                }
                3 -> {
                    if (startsWith(0xEF, 0xBB, 0xBF)) from += 3
                    String(body, from, end - from, Charsets.UTF_8)
                }
                else -> String(body, from, end - from, Charsets.ISO_8859_1)
            })
            pos = next
        }
        return out
    }

    /** Where the frames are read from: the file, or the tag in memory after its unsynchronisation was undone. */
    private class Cursor(private val f: RandomAccessFile?, private val mem: ByteArray?) {
        private var p = 0
        var pos: Long
            get() = f?.filePointer ?: p.toLong()
            set(v) { if (f != null) f.seek(v) else p = v.toInt() }
        fun readFully(b: ByteArray) {
            if (f != null) f.readFully(b) else {
                if (p + b.size > mem!!.size) throw EOFException()
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
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun walk(f: RandomAccessFile, ids: Set<String>, maxSize: Int, visit: (String, ByteArray) -> Boolean) {
        val h = ByteArray(10)
        f.readFully(h)
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return
        val version = h[3].toInt()
        if (version != 3 && version != 4) return
        val v4 = version == 4
        val tagFlags = h[5].toInt()
        var end = 10L + syncsafe(h, 6)
        var c = Cursor(f, null)
        if (!v4 && tagFlags and 0x80 != 0) {
            // v2.3 unsynchronises the whole tag, and the sizes inside it are those of the original data. A tag that is
            // cut or larger than the limit: the complete frames of its start
            val n = minOf(end - 10, f.length() - 10, MAX_UNSYNC_TAG.toLong())
            if (n <= 0) return
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
            val size = if (v4) syncsafe(eh, 0).toLong() else be32(eh, 0) + 4L
            if (size < 4 || 10L + size > end) return
            c.pos = 10L + size
        }
        val fh = ByteArray(10)
        while (c.pos + 10 <= end) {
            c.readFully(fh)
            if (fh[0].toInt() == 0) return // padding
            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            val size = if (v4) syncsafe(fh, 4) else be32(fh, 4)
            if (size < 0 || c.pos + size > end) return
            if (id !in ids || size > maxSize) {
                c.pos = c.pos + size
                continue
            }
            val data = ByteArray(size)
            c.readFully(data)
            val fmt = fh[9].toInt() and 255
            if (if (v4) fmt and 0x0C != 0 else fmt and 0xC0 != 0) continue // compressed or encrypted
            var skip = 0
            if (if (v4) fmt and 0x40 != 0 else fmt and 0x20 != 0) skip += 1 // group identifier
            if (v4 && fmt and 0x01 != 0) skip += 4 // data length indicator
            if (skip > size) continue
            var body = data.copyOfRange(skip, size)
            // v2.4 unsynchronisation: the frame flag, or the tag flag (some taggers set only the tag flag)
            if (v4 && (fmt and 0x02 != 0 || tagFlags and 0x80 != 0)) body = deUnsync(body)
            if (!visit(id, body)) return
        }
    }
}
