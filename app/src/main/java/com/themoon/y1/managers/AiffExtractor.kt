package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.extractor.ConstantBitrateSeekMap
import com.google.android.exoplayer2.extractor.Extractor
import com.google.android.exoplayer2.extractor.ExtractorInput
import com.google.android.exoplayer2.extractor.ExtractorOutput
import com.google.android.exoplayer2.extractor.PositionHolder
import com.google.android.exoplayer2.extractor.SeekMap
import com.google.android.exoplayer2.extractor.TrackOutput
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.util.ParsableByteArray

/**
 * Plays AIFF and AIFF-C files ([Aiff]) in ExoPlayer 2.13.3, which has no extractor for them. The samples go out as
 * raw PCM, like the WAV extractor's: DefaultAudioSink turns 8-bit, 16-bit big-endian, 24-bit, 32-bit and float PCM
 * into 16-bit itself (ResamplingAudioProcessor), so only the byte orders that ExoPlayer has no encoding for are
 * changed here:
 * - 16-bit big-endian (AIFF) and little-endian (AIFF-C "sowt"): as they are
 * - 24-bit and 32-bit big-endian: bytes swapped
 * - 8-bit: AIFF has signed bytes, ExoPlayer unsigned ones
 * - 32-bit big-endian floats (AIFF-C "fl32"): bytes swapped
 * Compressed AIFF-C (ulaw, alaw, ima4) and 64-bit floats throw a [ParserException]: the player skips the track.
 *
 * The header chunks may come in any order. When SSND comes before COMM, the extractor seeks past it and back.
 */
class AiffExtractor : Extractor {
    private var output: ExtractorOutput? = null
    private var track: TrackOutput? = null
    private var state = STATE_FORM

    private var aifc = false
    private var comm: Aiff.Comm? = null
    private var dataStart = -1L
    private var dataEnd = -1L
    private var sampleBytes = 0
    private var convert = CONVERT_NONE

    private var buffer = ByteArray(0)
    private val scratch = ParsableByteArray()

    /**
     * Where the chunk that [readChunks] reads starts. After a read error the loader goes on with this extractor where
     * the input stopped, which can be inside the chunk (its header was read): [readChunks] then seeks back here.
     */
    private var chunkStart = -1L

    override fun sniff(input: ExtractorInput): Boolean {
        val head = ByteArray(12)
        return try {
            input.peekFully(head, 0, 12, true) && Aiff.isFormHeader(head)
        } catch (_: java.io.EOFException) {
            false // shorter than a FORM header
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (state == STATE_FORM) {
            val head = ByteArray(12)
            input.readFully(head, 0, 12)
            if (!Aiff.isFormHeader(head)) throw ParserException("Not an AIFF file")
            aifc = Aiff.isAifc(head)
            state = STATE_CHUNKS
        }
        if (state == STATE_CHUNKS) return readChunks(input, seekPosition)
        return readSamples(input)
    }

    /** Reads the chunk headers up to the samples: COMM and SSND, the other chunks are skipped. */
    private fun readChunks(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (chunkStart >= 0 && input.position != chunkStart) return seekTo(seekPosition, chunkStart)
        val ch = ByteArray(8)
        while (true) {
            chunkStart = input.position
            // The end of the file before the samples: SSND came before COMM, or a chunk is missing
            if (!input.readFully(ch, 0, 8, true)) {
                if (comm != null && dataStart >= 0) return startSamples(input, seekPosition)
                throw ParserException("AIFF file without COMM or SSND")
            }
            val id = String(ch, 0, 4, Charsets.ISO_8859_1)
            val size = Aiff.be32(ch, 4)
            val body = input.position
            val next = body + size + (size and 1L)
            when (id) {
                "COMM" -> {
                    if (size < 18 || size > MAX_COMM) throw ParserException("AIFF COMM chunk of $size bytes")
                    val b = ByteArray(size.toInt())
                    input.readFully(b, 0, b.size)
                    val c = Aiff.parseComm(b, aifc) ?: throw ParserException("Broken AIFF COMM chunk")
                    if (!c.isPlayable) throw ParserException("AIFF format not supported: ${c.bits}-bit ${c.encoding}")
                    comm = c
                    if (size and 1L != 0L) input.skipFully(1, true)
                    if (dataStart >= 0) return startSamples(input, seekPosition)
                }
                "SSND" -> {
                    if (size < 8 && size != 0L) throw ParserException("AIFF SSND chunk of $size bytes")
                    val fields = ByteArray(8)
                    input.readFully(fields, 0, 8)
                    dataStart = body + 8 + Aiff.be32(fields, 0)
                    val length = input.length
                    // A size of 0 (a recording that was not closed): the samples go to the end of the file
                    dataEnd = when {
                        size >= 8 -> body + size
                        length != C.LENGTH_UNSET.toLong() -> length
                        else -> Long.MAX_VALUE
                    }
                    if (length != C.LENGTH_UNSET.toLong()) dataEnd = minOf(dataEnd, length)
                    if (comm != null) return startSamples(input, seekPosition)
                    // COMM comes later: on past the samples, then back to them
                    if (size < 8) throw ParserException("AIFF COMM chunk after an SSND chunk of no size")
                    return seekToChunk(seekPosition, next)
                }
                else -> {
                    if (size <= MAX_SKIP) {
                        if (!input.skipFully((next - body).toInt(), true)) {
                            if (comm != null && dataStart >= 0) return startSamples(input, seekPosition)
                            throw ParserException("AIFF file without COMM or SSND")
                        }
                    } else {
                        // A large chunk (an ID3 tag with a cover): seek over it in place of reading it
                        return seekToChunk(seekPosition, next)
                    }
                }
            }
        }
    }

    private fun seekTo(seekPosition: PositionHolder, position: Long): Int {
        seekPosition.position = position
        return Extractor.RESULT_SEEK
    }

    /** Seeks to the chunk at [position], which [readChunks] reads next. */
    private fun seekToChunk(seekPosition: PositionHolder, position: Long): Int {
        chunkStart = position
        return seekTo(seekPosition, position)
    }

    /** Gives the format and the seek map, then goes to the first sample: in place, or with a seek after a late COMM. */
    private fun startSamples(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val c = comm!!
        val blockAlign = c.blockAlign
        if (c.frames > 0) dataEnd = minOf(dataEnd, dataStart + c.frames * blockAlign)
        if (dataEnd < dataStart) dataEnd = dataStart
        sampleBytes = blockAlign * maxOf(1, minOf(c.sampleRate / 10, MAX_SAMPLE_BYTES / blockAlign))
        if (buffer.size < sampleBytes) buffer = ByteArray(sampleBytes)
        val encoding: Int
        when (c.encoding) {
            Aiff.Encoding.FLOAT_BE -> { encoding = C.ENCODING_PCM_FLOAT; convert = CONVERT_SWAP_4 }
            Aiff.Encoding.INT_LE -> when (c.bytesPerSample) {
                2 -> { encoding = C.ENCODING_PCM_16BIT; convert = CONVERT_NONE }
                3 -> { encoding = C.ENCODING_PCM_24BIT; convert = CONVERT_NONE }
                4 -> { encoding = C.ENCODING_PCM_32BIT; convert = CONVERT_NONE }
                else -> throw ParserException("AIFF format not supported: ${c.bits}-bit sowt")
            }
            else -> when (c.bytesPerSample) {
                1 -> { encoding = C.ENCODING_PCM_8BIT; convert = CONVERT_SIGNED_8 }
                2 -> { encoding = C.ENCODING_PCM_16BIT_BIG_ENDIAN; convert = CONVERT_NONE }
                3 -> { encoding = C.ENCODING_PCM_24BIT; convert = CONVERT_SWAP_3 }
                else -> { encoding = C.ENCODING_PCM_32BIT; convert = CONVERT_SWAP_4 }
            }
        }
        val out = output!!
        if (track == null) {
            val bitrate = c.sampleRate * blockAlign * 8
            val t = out.track(0, C.TRACK_TYPE_AUDIO)
            t.format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setAverageBitrate(bitrate)
                    .setPeakBitrate(bitrate)
                    .setMaxInputSize(sampleBytes)
                    .setChannelCount(c.channels)
                    .setSampleRate(c.sampleRate)
                    .setPcmEncoding(encoding)
                    .build()
            )
            track = t
            out.seekMap(
                if (dataEnd == Long.MAX_VALUE) SeekMap.Unseekable(C.TIME_UNSET)
                else ConstantBitrateSeekMap(dataEnd, dataStart, bitrate, blockAlign)
            )
            out.endTracks()
        }
        // The state changes only after the skip: a read error in it reads the chunk again ([chunkStart])
        val gap = dataStart - input.position
        val inPlace = gap in 0L..MAX_SKIP && input.skipFully(gap.toInt(), true)
        state = STATE_SAMPLES
        return if (inPlace) Extractor.RESULT_CONTINUE else seekTo(seekPosition, dataStart)
    }

    /**
     * Gives about 100 ms of whole sample frames, read into [buffer] and turned into an order that ExoPlayer has. The time
     * of a sample comes from its place in the file, as the seek map counts it, so a seek needs no state here.
     * A block is read whole or not at all: readFully moves the input only when it has all the bytes, so after a read
     * error the loader reads the same block again, and no sound is lost.
     */
    private fun readSamples(input: ExtractorInput): Int {
        val c = comm!!
        val blockAlign = c.blockAlign
        val position = input.position
        val left = dataEnd - position
        if (left < blockAlign) return Extractor.RESULT_END_OF_INPUT
        val want = minOf(sampleBytes.toLong(), left - left % blockAlign).toInt()
        val read = try {
            input.readFully(buffer, 0, want, true)
        } catch (_: java.io.EOFException) {
            // Shorter than its length: only when the length is not known (an SSND of no size, from a stream)
            false
        }
        if (!read) return Extractor.RESULT_END_OF_INPUT
        convertSamples(buffer, want, convert)
        scratch.reset(buffer, want)
        val t = track!!
        t.sampleData(scratch, want)
        val timeUs = (position - dataStart) / blockAlign * C.MICROS_PER_SECOND / c.sampleRate
        t.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, want, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    /**
     * A seek into the samples (from the seek map) needs nothing: [readSamples] times each sample by its place. The
     * loader seeks to 0 before the first read, and again when it loads the file from the start: the header again.
     */
    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) {
            state = STATE_FORM
            comm = null
            dataStart = -1L
            dataEnd = -1L
            chunkStart = -1L
        }
    }

    override fun release() {}

    internal companion object {
        private const val STATE_FORM = 0
        private const val STATE_CHUNKS = 1
        private const val STATE_SAMPLES = 2

        /** COMM is 18 bytes, AIFF-C adds a type and a Pascal string of up to 255 bytes. */
        private const val MAX_COMM = 512L

        /** Chunks up to this size are read past; larger ones are skipped with a seek. */
        private const val MAX_SKIP = 64L * 1024

        /** The largest sample: 64 KB, about 370 ms of CD audio (less than 100 ms only for many channels at a high rate). */
        private const val MAX_SAMPLE_BYTES = 64 * 1024

        const val CONVERT_NONE = 0
        const val CONVERT_SIGNED_8 = 1
        const val CONVERT_SWAP_3 = 2
        const val CONVERT_SWAP_4 = 3

        /** Turns the first [size] bytes of [b] into the byte order (and the 8-bit sign) of the ExoPlayer encoding. */
        fun convertSamples(b: ByteArray, size: Int, convert: Int) {
            when (convert) {
                CONVERT_SIGNED_8 -> for (i in 0 until size) b[i] = (b[i].toInt() xor 0x80).toByte()
                CONVERT_SWAP_3 -> {
                    var i = 0
                    while (i + 3 <= size) {
                        val t = b[i]; b[i] = b[i + 2]; b[i + 2] = t
                        i += 3
                    }
                }
                CONVERT_SWAP_4 -> {
                    var i = 0
                    while (i + 4 <= size) {
                        var t = b[i]; b[i] = b[i + 3]; b[i + 3] = t
                        t = b[i + 1]; b[i + 1] = b[i + 2]; b[i + 2] = t
                        i += 4
                    }
                }
            }
        }
    }
}
