package com.themoon.y1.managers

import com.themoon.y1.managers.AiffBytes.comm
import com.themoon.y1.managers.AiffBytes.chunk
import com.themoon.y1.managers.AiffBytes.form
import com.themoon.y1.managers.AiffBytes.ssnd
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile

class AiffTest {
    private fun layout(bytes: ByteArray): Aiff.Layout? = RandomAccessFile(AiffBytes.file(bytes), "r").use { Aiff.locate(it) }

    @Test
    fun extendedRateOfACdFile() {
        // 44100 Hz as iTunes writes it
        val bytes = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
        assertEquals(44_100, Aiff.extendedToRate(bytes, 0))
    }

    @Test
    fun extendedRatesRoundTrip() {
        for (rate in listOf(8_000, 11_025, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) {
            assertEquals(rate, Aiff.extendedToRate(AiffBytes.extended(rate), 0))
        }
    }

    @Test
    fun extendedRateRejectsNegativeZeroAndInfinity() {
        assertEquals(0, Aiff.extendedToRate(byteArrayOf(0xC0.toByte(), 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0), 0))
        assertEquals(0, Aiff.extendedToRate(ByteArray(10), 0))
        assertEquals(0, Aiff.extendedToRate(byteArrayOf(0x7F, 0xFF.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0), 0))
        assertEquals(0, Aiff.extendedToRate(ByteArray(4), 0))
    }

    /** The body of a COMM chunk from [AiffBytes.comm] (18 or 24 bytes: no pad byte). */
    private fun commBody(chunk: ByteArray) = chunk.copyOfRange(8, chunk.size)

    @Test
    fun commOfAiffAndAiffC() {
        val aiff = Aiff.parseComm(commBody(comm(2, 1000, 16, 44_100)), aifc = false)!!
        assertEquals(2, aiff.channels)
        assertEquals(1000L, aiff.frames)
        assertEquals(16, aiff.bits)
        assertEquals(44_100, aiff.sampleRate)
        assertEquals(Aiff.Encoding.INT_BE, aiff.encoding)
        assertEquals(4, aiff.blockAlign)
        assertTrue(aiff.isPlayable)

        assertEquals(Aiff.Encoding.INT_BE, Aiff.parseComm(commBody(comm(2, 1, 16, 44_100, "NONE")), true)!!.encoding)
        assertEquals(Aiff.Encoding.INT_LE, Aiff.parseComm(commBody(comm(2, 1, 16, 44_100, "sowt")), true)!!.encoding)
        // One byte has no order: 8-bit sowt is the same as 8-bit AIFF
        assertEquals(Aiff.Encoding.INT_BE, Aiff.parseComm(commBody(comm(1, 1, 8, 8_000, "sowt")), true)!!.encoding)
        assertEquals(Aiff.Encoding.FLOAT_BE, Aiff.parseComm(commBody(comm(2, 1, 32, 48_000, "fl32")), true)!!.encoding)
        val ulaw = Aiff.parseComm(commBody(comm(1, 1, 16, 8_000, "ulaw")), true)!!
        assertEquals(Aiff.Encoding.UNSUPPORTED, ulaw.encoding)
        assertFalse(ulaw.isPlayable)
        assertFalse(Aiff.parseComm(commBody(comm(2, 1, 64, 48_000, "fl32")), true)!!.isPlayable)
    }

    @Test
    fun oddSampleSizesFillWholeBytes() {
        assertEquals(2, Aiff.parseComm(commBody(comm(1, 1, 12, 44_100)), false)!!.bytesPerSample)
        assertEquals(3, Aiff.parseComm(commBody(comm(2, 1, 20, 44_100)), false)!!.bytesPerSample)
        assertEquals(6, Aiff.parseComm(commBody(comm(2, 1, 20, 44_100)), false)!!.blockAlign)
    }

    @Test
    fun brokenCommGivesNull() {
        assertNull(Aiff.parseComm(ByteArray(10), false))
        assertNull(Aiff.parseComm(commBody(comm(0, 1, 16, 44_100)), false))
        assertNull(Aiff.parseComm(commBody(comm(2, 1, 16, 44_100)), aifc = true)) // AIFF-C needs the type
    }

    @Test
    fun layoutWithAnOddChunkAndATagAfterTheSamples() {
        val samples = ByteArray(400) { it.toByte() }
        val tag = AiffBytes.id3v23(AiffBytes.text("TIT2", "Song"))
        val bytes = form(false, comm(2, 100, 16, 44_100), chunk("NAME", "Odd".toByteArray()), ssnd(samples), chunk("ID3 ", tag))
        val l = layout(bytes)!!
        assertFalse(l.aifc)
        assertEquals("Odd", l.name)
        // FORM header 12, COMM 8 + 18, NAME 8 + 3 + pad, SSND header 8 + offset/block size 8
        assertEquals(12L + 26 + 12 + 16, l.dataStart)
        assertEquals(l.dataStart + 400, l.dataEnd)
        assertEquals(l.dataEnd + 8, l.id3Offset)
        assertEquals(tag.size.toLong(), l.id3Size)
        assertEquals(100L, l.frames)
        assertEquals(2L, l.durationMs) // 100 frames at 44.1 kHz
    }

    @Test
    fun samplesEndAtTheFramesOfComm() {
        // SSND longer than COMM says (a pad of silence): the samples end at frames * block size
        val l = layout(form(false, comm(1, 10, 16, 8_000), ssnd(ByteArray(64), offset = 4)))!!
        assertEquals(12L + 26 + 16 + 4, l.dataStart)
        assertEquals(l.dataStart + 20, l.dataEnd)
    }

    @Test
    fun commAfterTheSamplesAndAnUnclosedRecording() {
        // COMM after SSND, and COMM gives 0 frames: the frames come from the size of SSND
        val l = layout(form(false, ssnd(ByteArray(80)), comm(2, 0, 16, 8_000)))!!
        assertEquals(20L, l.frames)
        assertEquals(2L, l.durationMs)
        // An SSND of size 0: the samples go to the end of the file
        val open = layout(form(false, comm(1, 0, 16, 8_000), ssnd(ByteArray(16), size = 0)))!!
        assertEquals(open.dataStart + 16, open.dataEnd)
    }

    @Test
    fun durationOfAOneSecondFile() {
        val f = AiffBytes.file(form(false, comm(1, 8_000, 8, 8_000), ssnd(ByteArray(8_000))))
        assertEquals(1_000L, Aiff.durationMs(f))
        assertEquals(1_000L, AudioLength.fromHeaders(f))
    }

    @Test
    fun notAiff() {
        assertNull(layout("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + ByteArray(20)))
        assertNull(layout(ByteArray(8)))
        assertEquals(0L, Aiff.durationMs(AiffBytes.file(ByteArray(40))))
        assertEquals(0L, Aiff.durationMs(java.io.File("/does/not/exist.aif")))
    }

    @Test
    fun aiffNames() {
        for (name in listOf("a.aif", "a.aiff", "a.aifc", "A.AIFF", "Track 01.Aif")) assertTrue(name, Aiff.isAiffName(name))
        for (name in listOf("a.wav", "aiff", "a.aiff.mp3", "a.mp3")) assertFalse(name, Aiff.isAiffName(name))
    }

    @Test
    fun tagsFromTheId3ChunkAndTheLength() {
        val tag = AiffBytes.id3v23(AiffBytes.text("TIT2", "Title"), AiffBytes.text("TPE1", "Artist"))
        val f = AiffBytes.file(form(false, comm(2, 44_100, 16, 44_100), chunk("ID3 ", tag), ssnd(ByteArray(16))))
        val tags = Aiff.readTags(f, includeArt = false)
        assertEquals("Title", tags[0])
        assertEquals("Artist", tags[1])
        assertEquals(1_000L, tags[11])
    }

    @Test
    fun nameAndAuthorChunksWithoutATag() {
        val f = AiffBytes.file(form(false, comm(1, 8, 8, 8_000), chunk("NAME", "Old Title".toByteArray()),
            chunk("AUTH", "Old Artist\u0000".toByteArray()), ssnd(ByteArray(8))))
        val tags = Aiff.readTags(f, includeArt = true)
        assertEquals("Old Title", tags[0])
        assertEquals("Old Artist", tags[1])
        assertNull(tags[5])
    }

    @Test
    fun formHeader() {
        assertTrue(Aiff.isFormHeader(form(false)))
        assertTrue(Aiff.isFormHeader(form(true)))
        assertTrue(Aiff.isAifc(form(true)))
        assertFalse(Aiff.isFormHeader("FORM".toByteArray() + ByteArray(4) + "8SVX".toByteArray()))
        assertArrayEquals(byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0), AiffBytes.extended(44_100))
    }
}
