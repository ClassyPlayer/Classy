package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.Allocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLoadControlTest {

    private val segment = C.DEFAULT_BUFFER_SEGMENT_SIZE

    @Test
    fun theBlockIsLargerThanTheTarget() {
        assertTrue(AudioPlayerManager.BUFFER_BLOCK_SEGMENTS * segment > AudioPlayerManager.BUFFER_TARGET_BYTES)
    }

    @Test
    fun allSegmentsOfTheBlockShareOneArray_alsoAfterAReset() {
        val loadControl = AudioPlayerManager.audioLoadControl()
        val allocator = loadControl.allocator
        repeat(2) {
            val allocations = List(AudioPlayerManager.BUFFER_BLOCK_SEGMENTS) { allocator.allocate() }
            // No new array for each segment: Dalvik makes no GC_FOR_ALLOC for them
            allocations.forEach { assertSame(allocations[0].data, it.data) }
            allocator.release(allocations.toTypedArray<Allocation>())
            // The end of a track (stop) resets the load control and trims the allocator
            loadControl.onStopped()
        }
    }

    @Test
    fun loadingStopsAtTheTarget() {
        val loadControl = AudioPlayerManager.audioLoadControl()
        val allocator = loadControl.allocator
        val bufferedUs = 3_000_000L // 3 s of 24-bit 96 kHz PCM are about 1.7 MB
        val below = List(AudioPlayerManager.BUFFER_TARGET_BYTES / segment - 1) { allocator.allocate() }
        assertTrue(loadControl.shouldContinueLoading(0, bufferedUs, 1f))
        val last = allocator.allocate()
        assertFalse(loadControl.shouldContinueLoading(0, bufferedUs, 1f))
        allocator.release((below + last).toTypedArray())
    }
}
