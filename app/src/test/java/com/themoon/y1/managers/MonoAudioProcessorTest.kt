package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MonoAudioProcessorTest {

    private val stereo16 = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun pcm16(vararg samples: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) b.putShort(s.toShort())
        b.flip()
        return b
    }

    private fun samples(b: ByteBuffer): IntArray {
        val view = b.duplicate().order(ByteOrder.nativeOrder())
        return IntArray(view.remaining() / 2) { view.short.toInt() }
    }

    private fun mix(vararg samples: Int): IntArray {
        val input = pcm16(*samples)
        val output = ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.nativeOrder())
        MonoAudioProcessor.mixToMono(input, output)
        assertEquals(0, input.remaining())
        output.flip()
        return samples(output)
    }

    private fun process(processor: MonoAudioProcessor, vararg samples: Int): IntArray {
        processor.queueInput(pcm16(*samples))
        return samples(processor.output)
    }

    @Test
    fun mix_givesTheAverageOnBothChannels() {
        assertArrayEquals(intArrayOf(150, 150, -50, -50, 0, 0), mix(100, 200, -100, 0, 1000, -1000))
    }

    @Test
    fun mix_leftOnlySignal_playsOnBothSidesAtHalfLevel() {
        assertArrayEquals(intArrayOf(5000, 5000, -5000, -5000), mix(10000, 0, -10000, 0))
    }

    @Test
    fun mix_fullScaleSamples_doNotClip() {
        assertArrayEquals(
            intArrayOf(32767, 32767, -32768, -32768, 0, 0, 0, 0),
            mix(32767, 32767, -32768, -32768, 32767, -32767, -32768, 32767)
        )
    }

    @Test
    fun mix_oddSum_roundsTowardZero() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 1, 1, -1, -1), mix(1, 0, -1, 0, 1, 2, -1, -2))
    }

    @Test
    fun mix_copiesBytesAfterTheLastFullFrame() {
        val input = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder())
        input.putShort(300).putShort(100).put(7).put(9)
        input.flip()
        val output = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder())
        MonoAudioProcessor.mixToMono(input, output)
        output.flip()
        assertEquals(6, output.remaining())
        assertEquals(200.toShort(), output.short)
        assertEquals(200.toShort(), output.short)
        assertEquals(7.toByte(), output.get())
        assertEquals(9.toByte(), output.get())
    }

    @Test
    fun mix_startsAtTheInputPosition() {
        val input = pcm16(1, 1, 400, 0)
        input.position(4)
        val output = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        MonoAudioProcessor.mixToMono(input, output)
        output.flip()
        assertArrayEquals(intArrayOf(200, 200), samples(output))
    }

    @Test
    fun configure_16BitStereo_isActive() {
        val processor = MonoAudioProcessor()
        assertEquals(stereo16, processor.configure(stereo16))
        processor.flush()
        assertTrue(processor.isActive)
    }

    @Test
    fun configure_otherFormats_areNotActive() {
        for (format in listOf(
            AudioFormat(44100, 1, C.ENCODING_PCM_16BIT),
            AudioFormat(48000, 6, C.ENCODING_PCM_16BIT),
            AudioFormat(96000, 2, C.ENCODING_PCM_24BIT),
            AudioFormat(96000, 2, C.ENCODING_PCM_32BIT),
            AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        )) {
            val processor = MonoAudioProcessor()
            assertEquals(AudioFormat.NOT_SET, processor.configure(format))
            processor.flush()
            assertFalse("$format", processor.isActive)
        }
    }

    @Test
    fun disabled_copiesTheInput() {
        val processor = MonoAudioProcessor()
        processor.configure(stereo16)
        processor.flush()
        assertArrayEquals(intArrayOf(100, -200, 32767, -32768), process(processor, 100, -200, 32767, -32768))
    }

    @Test
    fun enabled_mixes_andAChangeTakesEffectOnTheNextBuffer() {
        val processor = MonoAudioProcessor()
        processor.configure(stereo16)
        processor.flush()
        processor.isEnabled = true
        assertArrayEquals(intArrayOf(150, 150), process(processor, 100, 200))
        processor.isEnabled = false
        assertArrayEquals(intArrayOf(100, 200), process(processor, 100, 200))
    }

    @Test
    fun endOfStream_endsAfterTheLastOutput() {
        val processor = MonoAudioProcessor()
        processor.configure(stereo16)
        processor.flush()
        processor.isEnabled = true
        processor.queueInput(pcm16(100, 200))
        processor.queueEndOfStream()
        assertFalse(processor.isEnded)
        assertArrayEquals(intArrayOf(150, 150), samples(processor.output))
        assertTrue(processor.isEnded)
    }
}
