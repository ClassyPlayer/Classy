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

    @Test fun compressedFrameIsSkipped() =
        assertNull(Id3Year.read(tag(3, 0, ByteArray(0), "TYER".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0x80.toByte()) + byteArrayOf(0, 0, 0, 0, 1))))
}
