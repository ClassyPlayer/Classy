package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat
import com.google.android.exoplayer2.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Settings → Playback → Mono Audio: both channels play the mix of the left and the right channel, so that one
 * earbud gives all of the music.
 *
 * Only 16-bit PCM stereo is mixed. DefaultAudioSink changes each PCM encoding to 16 bit before the custom
 * processors, so this is the format that comes here. With other formats (mono, more than 2 channels) the
 * processor is not active and the audio goes through without a change.
 *
 * With 16-bit stereo the processor stays active also when Mono Audio is Off (it then only copies the buffer):
 * DefaultAudioSink reads [isActive] only when it configures or flushes, so [isEnabled] takes effect on the
 * next buffer, without a restart of the track.
 */
class MonoAudioProcessor : BaseAudioProcessor() {

    /** Mono Audio On: mix. Set on the main thread, read on the playback thread. */
    @Volatile
    var isEnabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (isSupported(inputAudioFormat)) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        if (isEnabled) mixToMono(inputBuffer, output) else output.put(inputBuffer)
        output.flip()
    }

    companion object {
        private const val FRAME_BYTES = 4

        /** True for the format that the processor mixes: 16-bit PCM with 2 channels. */
        fun isSupported(format: AudioFormat): Boolean =
            format.encoding == C.ENCODING_PCM_16BIT && format.channelCount == 2

        /**
         * Writes each 16-bit stereo frame of [input] to [output] with the average (L + R) / 2 on both channels.
         * The average of two 16-bit samples is always in the 16-bit range, so it cannot clip.
         * The samples are in the native byte order (the order of the PCM data that goes to the AudioTrack).
         * Bytes after the last full frame are copied without a change. Moves the position of [input] to its
         * limit and the position of [output] by the same number of bytes; does not flip [output].
         */
        fun mixToMono(input: ByteBuffer, output: ByteBuffer) {
            val source = input.duplicate().order(ByteOrder.nativeOrder())
            val target = output.duplicate().order(ByteOrder.nativeOrder())
            val start = source.position()
            val frames = source.remaining() / FRAME_BYTES
            for (i in 0 until frames) {
                val at = start + i * FRAME_BYTES
                val mixed = ((source.getShort(at) + source.getShort(at + 2)) / 2).toShort()
                target.putShort(mixed)
                target.putShort(mixed)
            }
            source.position(start + frames * FRAME_BYTES)
            target.put(source)
            output.position(target.position())
            input.position(input.limit())
        }
    }
}
