package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.extractor.DefaultExtractorInput
import com.google.android.exoplayer2.extractor.Extractor
import com.google.android.exoplayer2.extractor.ExtractorOutput
import com.google.android.exoplayer2.extractor.PositionHolder
import com.google.android.exoplayer2.extractor.SeekMap
import com.google.android.exoplayer2.extractor.TrackOutput
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor
import com.google.android.exoplayer2.upstream.DataReader
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.util.ParsableByteArray
import com.themoon.y1.managers.AiffBytes.chunk
import com.themoon.y1.managers.AiffBytes.comm
import com.themoon.y1.managers.AiffBytes.form
import com.themoon.y1.managers.AiffBytes.ssnd
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class AiffExtractorTest {
    /** Reads [bytes] from [pos], as a data source opened at that position. */
    private class BytesReader(private val bytes: ByteArray, private var pos: Int) : DataReader {
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (pos >= bytes.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - pos)
            System.arraycopy(bytes, pos, target, offset, n)
            pos += n
            return n
        }
    }

    private class Track : TrackOutput {
        var format: Format? = null
        val data = ByteArrayOutputStream()
        val samples = ArrayList<Pair<Long, Int>>()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val b = ByteArray(length)
            val n = input.read(b, 0, length)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            data.write(b, 0, n)
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val b = ByteArray(length)
            data.readBytes(b, 0, length)
            this.data.write(b)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertEquals(C.BUFFER_FLAG_KEY_FRAME, flags)
            samples.add(timeUs to size)
        }
    }

    private class Output : ExtractorOutput {
        val track = Track()
        var seekMap: SeekMap? = null
        var tracks = 0
        var ended = false

        override fun track(id: Int, type: Int): TrackOutput {
            assertEquals(C.TRACK_TYPE_AUDIO, type)
            tracks++
            return track
        }

        override fun endTracks() {
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private fun input(bytes: ByteArray, position: Long = 0) =
        DefaultExtractorInput(BytesReader(bytes, position.toInt()), position, bytes.size.toLong())

    /** Runs the extractor over [bytes] as ExoPlayer's loader does, from [from], with a new input after each seek. */
    private fun extract(bytes: ByteArray, extractor: AiffExtractor = AiffExtractor(), output: Output = Output(),
                        from: Long = 0, fromTimeUs: Long = 0): Output {
        if (from == 0L) extractor.init(output)
        extractor.seek(from, fromTimeUs)
        var input = input(bytes, from)
        val holder = PositionHolder()
        repeat(100_000) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> return output
                Extractor.RESULT_SEEK -> input = input(bytes, holder.position)
            }
        }
        fail("The extractor did not end")
        return output
    }

    private fun samples(n: Int) = ByteArray(n) { (it * 7 + 1).toByte() }

    @Test
    fun sixteenBitBigEndianGoesOutAsItIs() {
        val pcm = samples(44_100 * 4 / 2) // half a second of stereo
        val tag = chunk("ID3 ", AiffBytes.id3v23(AiffBytes.text("TIT2", "After the samples")))
        val out = extract(form(false, comm(2, 22_050, 16, 44_100), ssnd(pcm), tag))
        val format = out.track.format!!
        assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
        assertEquals(C.ENCODING_PCM_16BIT_BIG_ENDIAN, format.pcmEncoding)
        assertEquals(2, format.channelCount)
        assertEquals(44_100, format.sampleRate)
        assertTrue(out.ended)
        assertEquals(1, out.tracks)
        // The ID3 chunk after SSND is not sound
        assertArrayEquals(pcm, out.track.data.toByteArray())
        // 100 ms samples, timed by their place
        assertEquals(0L to 17_640, out.track.samples[0])
        assertEquals(100_000L to 17_640, out.track.samples[1])
        assertEquals(500_000L, out.seekMap!!.durationUs)
    }

    @Test
    fun twentyFourBitBytesAreSwapped() {
        val out = extract(form(false, comm(1, 2, 24, 48_000), ssnd(byteArrayOf(1, 2, 3, 4, 5, 6))))
        assertEquals(C.ENCODING_PCM_24BIT, out.track.format!!.pcmEncoding)
        assertArrayEquals(byteArrayOf(3, 2, 1, 6, 5, 4), out.track.data.toByteArray())
    }

    @Test
    fun thirtyTwoBitAndFloatBytesAreSwapped() {
        val int32 = extract(form(false, comm(1, 1, 32, 48_000), ssnd(byteArrayOf(1, 2, 3, 4))))
        assertEquals(C.ENCODING_PCM_32BIT, int32.track.format!!.pcmEncoding)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), int32.track.data.toByteArray())
        val float = extract(form(true, comm(1, 1, 32, 48_000, "fl32"), ssnd(byteArrayOf(0x3F, 0x80.toByte(), 0, 0))))
        assertEquals(C.ENCODING_PCM_FLOAT, float.track.format!!.pcmEncoding)
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F), float.track.data.toByteArray())
    }

    @Test
    fun eightBitSignedBecomesUnsigned() {
        val out = extract(form(false, comm(1, 4, 8, 8_000), ssnd(byteArrayOf(0, 0x7F, 0x80.toByte(), 0xFF.toByte()))))
        assertEquals(C.ENCODING_PCM_8BIT, out.track.format!!.pcmEncoding)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0, 0x7F), out.track.data.toByteArray())
    }

    @Test
    fun sowtIsLittleEndian() {
        val pcm = samples(16)
        val out = extract(form(true, comm(2, 4, 16, 44_100, "sowt"), ssnd(pcm)))
        assertEquals(C.ENCODING_PCM_16BIT, out.track.format!!.pcmEncoding)
        assertArrayEquals(pcm, out.track.data.toByteArray())
    }

    @Test
    fun commAfterTheSamplesAndALargeTagBeforeThem() {
        val pcm = samples(4_000)
        val bigTag = chunk("ID3 ", ByteArray(200 * 1024))
        val out = extract(form(false, bigTag, ssnd(pcm, offset = 6), chunk("NAME", "x".toByteArray()), comm(2, 1_000, 16, 8_000)))
        assertEquals(C.ENCODING_PCM_16BIT_BIG_ENDIAN, out.track.format!!.pcmEncoding)
        assertArrayEquals(pcm, out.track.data.toByteArray())
    }

    @Test
    fun aFileCutInTheSamplesEndsAtTheLastWholeFrame() {
        val whole = form(false, comm(2, 1_000, 16, 8_000), ssnd(samples(4_000)))
        val cut = whole.copyOf(whole.size - 1_001)
        val out = extract(cut)
        assertEquals(2_996, out.track.data.size()) // 2999 bytes left: 749 whole frames
    }

    @Test
    fun seekGoesToAWholeFrameAndTimesTheSamplesFromThere() {
        val pcm = samples(44_100 * 4)
        val bytes = form(false, comm(2, 44_100, 16, 44_100), ssnd(pcm))
        val extractor = AiffExtractor()
        val out = extract(bytes, extractor)
        val seekMap = out.seekMap!!
        assertTrue(seekMap.isSeekable)
        assertEquals(1_000_000L, seekMap.durationUs)
        val point = seekMap.getSeekPoints(500_000).first
        val dataStart = 12L + 26 + 16
        assertEquals(dataStart + 22_050 * 4, point.position)
        assertEquals(500_000L, point.timeUs)

        // The loader seeks the same extractor, which gives its samples to the same track
        val samplesBefore = out.track.samples.size
        val bytesBefore = out.track.data.size()
        extract(bytes, extractor, out, from = point.position, fromTimeUs = point.timeUs)
        assertEquals(500_000L, out.track.samples[samplesBefore].first)
        val after = out.track.data.toByteArray()
        assertArrayEquals(pcm.copyOfRange(22_050 * 4, pcm.size), after.copyOfRange(bytesBefore, after.size))
    }

    @Test
    fun theFileCanBeLoadedAgainFromTheStart() {
        val pcm = samples(400)
        val bytes = form(false, comm(1, 200, 16, 8_000), ssnd(pcm))
        val extractor = AiffExtractor()
        val output = Output()
        extract(bytes, extractor, output)
        extractor.seek(0, 0)
        var input = input(bytes)
        val holder = PositionHolder()
        while (true) {
            val r = extractor.read(input, holder)
            if (r == Extractor.RESULT_END_OF_INPUT) break
            if (r == Extractor.RESULT_SEEK) input = input(bytes, holder.position)
        }
        // The format is given once; the samples come twice
        assertEquals(1, output.tracks)
        assertEquals(800, output.track.data.size())
    }

    @Test
    fun compressedAiffCIsRejected() {
        try {
            extract(form(true, comm(1, 4, 16, 8_000, "ulaw"), ssnd(samples(4))))
            fail("ulaw must not play")
        } catch (_: ParserException) {
        }
    }

    @Test
    fun noSamplesIsAnError() {
        try {
            extract(form(false, comm(1, 4, 16, 8_000)))
            fail("a file without SSND must not play")
        } catch (_: ParserException) {
        }
    }

    @Test
    fun sniffKnowsOnlyAiff() {
        assertTrue(AiffExtractor().sniff(input(form(false, comm(1, 0, 16, 8_000)))))
        assertTrue(AiffExtractor().sniff(input(form(true, comm(1, 0, 16, 8_000, "NONE")))))
        assertFalse(AiffExtractor().sniff(input("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + ByteArray(20))))
        assertFalse(AiffExtractor().sniff(input("ID3".toByteArray() + ByteArray(30))))
        assertFalse(AiffExtractor().sniff(input(ByteArray(4))))
    }

    @Test
    fun theAiffExtractorComesFirst() {
        val result = AudioPlayerManager.withAiffExtractor(arrayOf<Extractor>(Mp3Extractor()))
        assertEquals(2, result.size)
        assertTrue(result[0] is AiffExtractor)
        assertTrue(result[1] is Mp3Extractor)
        assertNotNull(AudioPlayerManager.withAiffExtractor(arrayOf()).single() as? AiffExtractor)
    }

    @Test
    fun conversions() {
        val b = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        AiffExtractor.convertSamples(b, 8, AiffExtractor.CONVERT_SWAP_4)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1, 8, 7, 6, 5), b)
        val c = byteArrayOf(1, 2, 3, 4, 5, 6)
        AiffExtractor.convertSamples(c, 6, AiffExtractor.CONVERT_SWAP_3)
        assertArrayEquals(byteArrayOf(3, 2, 1, 6, 5, 4), c)
        val d = byteArrayOf(1, 2)
        AiffExtractor.convertSamples(d, 2, AiffExtractor.CONVERT_NONE)
        assertArrayEquals(byteArrayOf(1, 2), d)
    }

    /**
     * Reads [bytes] from [pos], and fails once at [Fault.at]: a read that reaches it gets the bytes before it, the next
     * read throws, as a card with a read error. [fault] is shared by the inputs of one run, so it fails only once.
     */
    private class Fault(var at: Long, var failed: Boolean = false)

    private class FlakyReader(private val bytes: ByteArray, private var pos: Int, private val fault: Fault) : DataReader {
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!fault.failed && pos.toLong() == fault.at) {
                fault.failed = true
                throw IOException("read error at ${fault.at}")
            }
            if (pos >= bytes.size) return C.RESULT_END_OF_INPUT
            var n = minOf(length, bytes.size - pos)
            if (!fault.failed && pos < fault.at) n = minOf(n.toLong(), fault.at - pos).toInt()
            System.arraycopy(bytes, pos, target, offset, n)
            pos += n
            return n
        }
    }

    /**
     * Runs the extractor as ExoPlayer's loader does on a local file, with one read error at [failAt]: after the error the
     * loader keeps the extractor and goes on at the position that the input had read up to (ProgressiveMediaPeriod).
     */
    private fun extractWithAReadError(bytes: ByteArray, failAt: Long): Output {
        val extractor = AiffExtractor()
        val output = Output()
        val fault = Fault(failAt)
        extractor.init(output)
        extractor.seek(0, 0)
        var input = DefaultExtractorInput(FlakyReader(bytes, 0, fault), 0, bytes.size.toLong())
        val holder = PositionHolder()
        repeat(100_000) {
            val result = try {
                extractor.read(input, holder)
            } catch (e: IOException) {
                if (e is ParserException) throw e
                holder.position = input.position
                Extractor.RESULT_SEEK
            }
            when (result) {
                Extractor.RESULT_END_OF_INPUT -> {
                    assertTrue("the read error at $failAt did not happen", fault.failed)
                    return output
                }
                Extractor.RESULT_SEEK -> input = DefaultExtractorInput(FlakyReader(bytes, holder.position.toInt(), fault), holder.position, bytes.size.toLong())
            }
        }
        fail("The extractor did not end")
        return output
    }

    @Test
    fun aReadErrorInTheHeaderReadsTheChunkAgain() {
        val pcm = samples(4_000)
        val bytes = form(true, comm(2, 1_000, 16, 8_000, "NONE"), chunk("NAME", "Title".toByteArray()), ssnd(pcm, offset = 4))
        val comm = 12L
        val name = comm + 8 + 24
        val ssnd = name + 8 + 6
        // Inside the COMM body, inside the NAME body, inside the SSND offset and block size fields, inside its offset
        for (failAt in listOf(comm + 8 + 5, name + 8 + 2, ssnd + 8 + 3, ssnd + 8 + 8 + 2)) {
            val out = extractWithAReadError(bytes, failAt)
            assertEquals("format once, error at $failAt", 1, out.tracks)
            assertArrayEquals("error at $failAt", pcm, out.track.data.toByteArray())
        }
    }

    @Test
    fun aReadErrorAfterTheSamplesReadsTheChunksAgain() {
        // SSND before COMM: the extractor seeks past the samples to COMM and back
        val pcm = samples(4_000)
        val bytes = form(false, ssnd(pcm), comm(2, 1_000, 16, 8_000))
        val out = extractWithAReadError(bytes, bytes.size - 10L)
        assertArrayEquals(pcm, out.track.data.toByteArray())
    }

    @Test
    fun aReadErrorInTheSamplesLosesNoSound() {
        val pcm = samples(44_100 * 4)
        val bytes = form(false, comm(2, 44_100, 16, 44_100), ssnd(pcm))
        val dataStart = 12L + 26 + 16
        // In the middle of the third 100 ms block, and inside a frame
        val out = extractWithAReadError(bytes, dataStart + 2 * 17_640 + 1_001)
        assertArrayEquals(pcm, out.track.data.toByteArray())
        // Each block once, timed by its place
        assertEquals(10, out.track.samples.size)
        assertEquals(List(10) { it * 100_000L }, out.track.samples.map { it.first })
    }
}
