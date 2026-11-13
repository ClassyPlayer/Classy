package com.themoon.y1.managers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class AudioLengthTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "classy_length_" + System.nanoTime()).also { it.mkdirs() }

    @After
    fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    @Test
    fun flacLengthIsTotalSamplesOverSampleRate() {
        assertEquals(10_000L, AudioLength.flacStreamInfoMs(streamInfo(rate = 44_100, samples = 441_000L)))
        assertEquals(1_500L, AudioLength.flacStreamInfoMs(streamInfo(rate = 96_000, samples = 144_000L)))
        // 36-bit sample count: more than 2^32 samples
        assertEquals(97_391_548L, AudioLength.flacStreamInfoMs(streamInfo(rate = 44_100, samples = 4_294_967_296L)))
        // Total samples 0 = not known
        assertEquals(0L, AudioLength.flacStreamInfoMs(streamInfo(rate = 44_100, samples = 0L)))
        assertEquals(0L, AudioLength.flacStreamInfoMs(ByteArray(10)))
    }

    @Test
    fun scanReadsTheFlacLengthFromStreamInfo() {
        val file = File(dir, "song.flac")
        file.writeBytes(flacFile(rate = 48_000, samples = 48_000L * 185))
        assertEquals(185_000L, MediaLibraryManager.getInstance().extractAudioMetadata(file, false).durationMs)
        assertEquals(185_000L, AudioLength.fromHeaders(file))
    }

    @Test
    fun vorbisLengthIsTheLastGranuleOverTheSampleRate() {
        val file = File(dir, "song.ogg")
        file.writeBytes(vorbisFile(rate = 44_100, lastGranule = 441_000L))
        assertEquals(10_000L, MediaLibraryManager.getInstance().extractAudioMetadata(file, false).durationMs)
        assertEquals(10_000L, AudioLength.fromHeaders(file))
    }

    @Test
    fun opusLengthCountsAt48kHzWithoutThePreSkip() {
        val bytes = opusFile(preSkip = 312, lastGranule = 312L + 48_000L * 5)
        val opus = File(dir, "song.opus").also { it.writeBytes(bytes) }
        // Opus in a .ogg file: the Vorbis reader gives it to the Opus reader
        val ogg = File(dir, "opus.ogg").also { it.writeBytes(bytes) }
        val mlm = MediaLibraryManager.getInstance()
        assertEquals(5_000L, mlm.extractAudioMetadata(opus, false).durationMs)
        assertEquals(5_000L, mlm.extractAudioMetadata(ogg, false).durationMs)
        assertEquals(5_000L, AudioLength.fromHeaders(opus))
        assertEquals(5_000L, AudioLength.opusMs(312L + 48_000L * 5, 312))
    }

    @Test
    fun lastGranuleSkipsPagesOfOtherStreamsAndPagesWithNoPacketEnd() {
        val out = ByteArrayOutputStream()
        out.write(oggPage(granule = 1_000L, serial = 7, seq = 0, body = ByteArray(40)))
        out.write(oggPage(granule = 2_000L, serial = 7, seq = 1, body = ByteArray(40)))
        // A page of another stream, and a page in which no packet ends (granule -1)
        out.write(oggPage(granule = 9_000L, serial = 8, seq = 0, body = ByteArray(40)))
        out.write(oggPage(granule = -1L, serial = 7, seq = 2, body = ByteArray(600)))
        val tail = out.toByteArray()
        assertEquals(2_000L, AudioLength.lastGranule(tail, 7))
        assertEquals(9_000L, AudioLength.lastGranule(tail, null))
        assertEquals(-1L, AudioLength.lastGranule(tail, 3))
        // A page cut off at the end of the data does not count
        assertEquals(9_000L, AudioLength.lastGranule(tail.copyOf(tail.size - 1) + oggPage(5_000L, 8, 1, ByteArray(10)).copyOf(30), null))
    }

    @Test
    fun mp4LengthIsDurationOverTimescale() {
        assertEquals(10_000L, AudioLength.mvhdMs(mvhd(version = 0, timescale = 44_100, duration = 441_000L)))
        assertEquals(3_600_000L, AudioLength.mvhdMs(mvhd(version = 1, timescale = 1_000, duration = 3_600_000L)))
        assertEquals(0L, AudioLength.mvhdMs(mvhd(version = 0, timescale = 0, duration = 441_000L)))

        val file = File(dir, "song.m4a")
        val moov = box("moov", box("mvhd", mvhd(version = 0, timescale = 600, duration = 600L * 42)))
        file.writeBytes(box("ftyp", "M4A ".toByteArray()) + moov + box("mdat", ByteArray(2048)))
        assertEquals(42_000L, AudioLength.fromHeaders(file))
        assertEquals(42_000L, MediaLibraryManager.getInstance().extractAudioMetadata(file, false).durationMs)
    }

    @Test
    fun otherTypesHaveNoHeaderReader() {
        assertNull(AudioLength.fromHeaders(File(dir, "song.mp3")))
        assertNull(AudioLength.fromHeaders(File(dir, "song.wav")))
    }

    // --- Small files with only the headers that the readers use ---

    private fun streamInfo(rate: Int, samples: Long, channels: Int = 2, bits: Int = 16): ByteArray {
        val info = ByteArray(34)
        val packed = (rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((bits - 1).toLong() shl 36) or samples
        for (i in 0 until 8) info[10 + i] = (packed ushr (56 - 8 * i)).toByte()
        return info
    }

    private fun flacFile(rate: Int, samples: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        out.write(streamInfo(rate, samples))
        out.write(ByteArray(2048))
        return out.toByteArray()
    }

    private fun vorbisFile(rate: Int, lastGranule: Long): ByteArray {
        val id = ByteArrayOutputStream()
        id.write(1)
        id.write("vorbis".toByteArray())
        id.write(le(0L, 4))
        id.write(2)
        id.write(le(rate.toLong(), 4))
        id.write(ByteArray(14))
        val out = ByteArrayOutputStream()
        out.write(oggPage(granule = 0L, serial = 0x1234, seq = 0, body = id.toByteArray(), type = 2))
        out.write(oggPage(granule = lastGranule / 2, serial = 0x1234, seq = 1, body = ByteArray(1500)))
        out.write(oggPage(granule = lastGranule, serial = 0x1234, seq = 2, body = ByteArray(700), type = 4))
        return out.toByteArray()
    }

    private fun opusFile(preSkip: Int, lastGranule: Long): ByteArray {
        val head = ByteArrayOutputStream()
        head.write("OpusHead".toByteArray())
        head.write(1)
        head.write(2)
        head.write(le(preSkip.toLong(), 2))
        head.write(le(48_000L, 4))
        head.write(ByteArray(3))
        val out = ByteArrayOutputStream()
        out.write(oggPage(granule = 0L, serial = 99, seq = 0, body = head.toByteArray(), type = 2))
        out.write(oggPage(granule = 0L, serial = 99, seq = 1, body = "OpusTags".toByteArray() + ByteArray(8)))
        out.write(oggPage(granule = lastGranule / 2, serial = 99, seq = 2, body = ByteArray(1500)))
        out.write(oggPage(granule = lastGranule, serial = 99, seq = 3, body = ByteArray(300), type = 4))
        return out.toByteArray()
    }

    private fun oggPage(granule: Long, serial: Int, seq: Int, body: ByteArray, type: Int = 0): ByteArray {
        val segments = ArrayList<Int>()
        var left = body.size
        while (left >= 255) {
            segments.add(255)
            left -= 255
        }
        segments.add(left)
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(0)
        out.write(type)
        out.write(le(granule, 8))
        out.write(le(serial.toLong(), 4))
        out.write(le(seq.toLong(), 4))
        out.write(ByteArray(4))
        out.write(segments.size)
        for (s in segments) out.write(s)
        out.write(body)
        return out.toByteArray()
    }

    private fun mvhd(version: Int, timescale: Long, duration: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(version.toByte(), 0, 0, 0))
        if (version == 1) {
            out.write(ByteArray(16))
            out.write(be(timescale, 4))
            out.write(be(duration, 8))
        } else {
            out.write(ByteArray(8))
            out.write(be(timescale, 4))
            out.write(be(duration, 4))
        }
        out.write(ByteArray(80))
        return out.toByteArray()
    }

    private fun box(type: String, content: ByteArray): ByteArray = be(8L + content.size, 4) + type.toByteArray() + content

    private fun le(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr (8 * it)).toByte() }

    private fun be(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() }
}
