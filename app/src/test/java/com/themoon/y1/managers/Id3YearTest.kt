package com.themoon.y1.managers

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Id3YearTest {
    private fun frame(id: String, text: String, v4: Boolean): ByteArray {
        val body = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8) // 3 = UTF-8
        val n = body.size
        val size = if (v4) byteArrayOf((n shr 21 and 127).toByte(), (n shr 14 and 127).toByte(), (n shr 7 and 127).toByte(), (n and 127).toByte())
        else byteArrayOf(0, 0, (n shr 8).toByte(), n.toByte())
        return id.toByteArray() + size + byteArrayOf(0, 0) + body
    }

    private fun mp3(version: Int, vararg frames: ByteArray): File {
        val all = ByteArrayOutputStream().also { o -> frames.forEach { o.write(it) } }.toByteArray()
        val n = all.size
        val head = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0,
            (n shr 21 and 127).toByte(), (n shr 14 and 127).toByte(), (n shr 7 and 127).toByte(), (n and 127).toByte())
        return File.createTempFile("id3", ".mp3").apply { writeBytes(head + all); deleteOnExit() }
    }

    @Test fun v24DateGivesTheYear() =
        assertEquals("2024", Id3Year.read(mp3(4, frame("TIT2", "Song", true), frame("TDRC", "2024-09-27", true))))

    @Test fun v23Tyer() = assertEquals("1999", Id3Year.read(mp3(3, frame("TYER", "1999", false))))
    @Test fun noYearFrame() = assertNull(Id3Year.read(mp3(4, frame("TIT2", "Song", true))))
    @Test fun notId3() = assertNull(Id3Year.read(File.createTempFile("none", ".mp3").apply { writeBytes(ByteArray(40)); deleteOnExit() }))

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 127).toByte(), (n shr 14 and 127).toByte(), (n shr 7 and 127).toByte(), (n and 127).toByte())

    /** A v2.4 frame with raw [flags] (status, format) and a raw [body]. */
    private fun rawFrame(id: String, flags: ByteArray, body: ByteArray) = id.toByteArray() + syncsafe(body.size) + flags + body

    private fun tag(version: Int, tagFlags: Int, ext: ByteArray, vararg frames: ByteArray): File {
        val all = ext + frames.fold(ByteArray(0)) { a, b -> a + b }
        val head = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, tagFlags.toByte()) + syncsafe(all.size)
        return File.createTempFile("id3", ".mp3").apply { writeBytes(head + all); deleteOnExit() }
    }

    @Test fun v24ExtendedHeaderIsSkipped() {
        val ext = byteArrayOf(0, 0, 0, 6, 1, 0) // size 6 with its own field, one flag byte
        assertEquals("2024", Id3Year.read(tag(4, 0x40, ext, frame("TDRC", "2024-09-27", true))))
    }

    @Test fun v23ExtendedHeaderIsSkipped() {
        val ext = byteArrayOf(0, 0, 0, 6, 0, 0, 0, 0, 0, 0) // size 6 without its own field
        assertEquals("1999", Id3Year.read(tag(3, 0x40, ext, frame("TYER", "1999", false))))
    }

    @Test fun dataLengthIndicatorIsSkipped() {
        val utf16 = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + "2024".toByteArray(Charsets.UTF_16LE)
        val body = syncsafe(utf16.size) + utf16
        assertEquals("2024", Id3Year.read(tag(4, 0, ByteArray(0), rawFrame("TDRC", byteArrayOf(0, 1), body))))
    }

    @Test fun unsynchronisedFrameIsRestored() {
        // UTF-16 BOM FF FE, unsynchronised to FF 00 FE
        val body = byteArrayOf(1, 0xFF.toByte(), 0, 0xFE.toByte()) + "2024".toByteArray(Charsets.UTF_16LE)
        assertEquals("2024", Id3Year.read(tag(4, 0, ByteArray(0), rawFrame("TDRC", byteArrayOf(0, 2), body))))
    }

    @Test fun v24TagUnsynchronisationWithoutFrameFlagIsRestored() {
        // Only the tag flag (0x80) is set, not the frame flag: the UTF-16 BOM FF FE is stored as FF 00 FE
        val body = byteArrayOf(1, 0xFF.toByte(), 0, 0xFE.toByte()) + "2024".toByteArray(Charsets.UTF_16LE)
        assertEquals("2024", Id3Year.read(tag(4, 0x80, ByteArray(0), rawFrame("TDRC", byteArrayOf(0, 0), body))))
    }

    @Test fun utf8ByteOrderMarkIsRemoved() {
        val body = byteArrayOf(3, 0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "2024-09-27".toByteArray()
        assertEquals("2024", Id3Year.read(tag(4, 0, ByteArray(0), rawFrame("TDRC", byteArrayOf(0, 0), body))))
    }

    @Test fun compressedFrameIsSkipped() =
        assertNull(Id3Year.read(tag(3, 0, ByteArray(0), "TYER".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0x80.toByte()) + byteArrayOf(0, 0, 0, 0, 1))))

    @Test fun v23TagUnsynchronisationKeepsLaterFramesInPlace() {
        // The TIT2 body has FF FE, stored unsynchronised as FF 00 FE: its size stays 5, it takes 6 bytes in the file
        fun v3frame(id: String, body: ByteArray) = id.toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        val tyer = v3frame("TYER", byteArrayOf(3) + "1999".toByteArray())
        val stored = v3frame("TIT2", byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte(), 0x41, 0)).let {
            it.copyOfRange(0, 11) + byteArrayOf(0xFF.toByte(), 0, 0xFE.toByte(), 0x41, 0) // size 5, stored with the extra 00
        } + tyer
        val head = "ID3".toByteArray() + byteArrayOf(3, 0, 0x80.toByte()) + syncsafe(stored.size)
        val f = File.createTempFile("id3", ".mp3").apply { writeBytes(head + stored); deleteOnExit() }
        assertEquals("1999", Id3Year.read(f))
    }

    private fun file(vararg bytes: ByteArray) = File.createTempFile("id3", ".mp3").apply { writeBytes(bytes.fold(ByteArray(0)) { a, b -> a + b }); deleteOnExit() }
    private fun head(version: Int, size: Int, flags: Int = 0) = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags.toByte()) + syncsafe(size)

    @Test fun fileShorterThanAHeaderGivesNull() {
        assertNull(Id3Year.read(file("ID3".toByteArray())))
        assertNull(Id3Year.read(file(ByteArray(0))))
    }

    @Test fun hugeTagOnATinyFileGivesNull() {
        assertNull(Id3Year.read(file(head(4, 0x0FFFFFFF), ByteArray(20))))
        assertNull(Id3Year.read(file(head(3, 3 * 1024 * 1024, 0x80), ByteArray(20)))) // unsynchronised, under the 4 MB cap
        assertNull(Id3Year.read(file(head(4, 100, 0x40), ByteArray(2)))) // extended header cut short
    }

    @Test fun frameSizeFfFfFfFfGivesNull() {
        val bad = "TDRC".toByteArray() + byteArrayOf(-1, -1, -1, -1, 0, 0) + ByteArray(20)
        assertNull(Id3Year.read(file(head(4, 40), bad)))
        assertNull(Id3Year.read(file(head(3, 40), bad)))
    }

    @Test fun fileTruncatedMidFrameGivesNull() {
        val full = frame("TDRC", "2024-09-27", true)
        assertNull(Id3Year.read(file(head(4, full.size), full.copyOf(full.size - 6))))
    }

    @Test fun truncationKeepsATyerYearFoundBefore() {
        val tyer = frame("TYER", "1999", false)
        val cut = frame("TIT2", "A long title", false).let { it.copyOf(it.size - 5) }
        assertEquals("1999", Id3Year.read(file(head(3, tyer.size + 100), tyer, cut)))
    }

    @Test fun nonAsciiDigitsAreNotAYear() =
        assertNull(Id3Year.read(mp3(4, frame("TDRC", "\uFF12\uFF10\uFF12\uFF14", true))))
}
