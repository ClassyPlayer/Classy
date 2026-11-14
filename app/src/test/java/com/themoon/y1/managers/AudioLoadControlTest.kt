package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.extractor.Extractor
import com.google.android.exoplayer2.extractor.flac.FlacExtractor
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.Allocation
import com.google.android.exoplayer2.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLoadControlTest {

    private val segment = C.DEFAULT_BUFFER_SEGMENT_SIZE

    @Test
    fun theBlockHoldsTheTargetAndOneLoaderCheck() {
        // The loader checks the buffer after each 1 MB of the file; the buffer holds the file data (FLAC frames)
        val most = AudioPlayerManager.BUFFER_TARGET_BYTES + ProgressiveMediaSource.DEFAULT_LOADING_CHECK_INTERVAL_BYTES + segment
        assertTrue(AudioPlayerManager.BUFFER_BLOCK_SEGMENTS * segment >= most)
    }

    @Test
    fun theSegmentsKeepTheSameArrayAfterEachStop() {
        val loadControl = AudioPlayerManager.audioLoadControl()
        val allocator = loadControl.allocator as DefaultAllocator
        var block: ByteArray? = null
        repeat(3) {
            // Tracks selected: the target is set, so that the stop trims down from a positive target
            allocator.setTargetBufferSize(AudioPlayerManager.BUFFER_TARGET_BYTES)
            val allocations = List(AudioPlayerManager.BUFFER_BLOCK_SEGMENTS) { allocator.allocate() }
            val array = block ?: allocations[0].data
            block = array
            // No new array for a segment, also after a stop: Dalvik makes no GC_FOR_ALLOC for them
            allocations.forEach { assertSame(array, it.data) }
            allocator.release(allocations.toTypedArray<Allocation>())
            loadControl.onStopped()
            assertEquals(0, allocator.totalBytesAllocated)
        }
    }

    @Test
    fun loadingStopsAtTheTarget() {
        val loadControl = AudioPlayerManager.audioLoadControl()
        val allocator = loadControl.allocator
        val bufferedUs = 3_000_000L
        val below = List(AudioPlayerManager.BUFFER_TARGET_BYTES / segment - 1) { allocator.allocate() }
        assertTrue(loadControl.shouldContinueLoading(0, bufferedUs, 1f))
        val last = allocator.allocate()
        assertFalse(loadControl.shouldContinueLoading(0, bufferedUs, 1f))
        allocator.release((below + last).toTypedArray())
    }

    @Test
    fun theFlacExtensionExtractorIsReplacedAndTheOthersStay() {
        val mp3 = Mp3Extractor()
        val extractors = arrayOf<Extractor>(mp3, com.google.android.exoplayer2.ext.flac.FlacExtractor())
        val result = AudioPlayerManager.withCoreFlacExtractor(extractors)
        assertEquals(2, result.size)
        assertSame(mp3, result[0])
        assertTrue(result[1] is FlacExtractor)
    }
}
