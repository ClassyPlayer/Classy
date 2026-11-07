package com.themoon.y1.controllers.videos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScrubTest {

    @Test
    fun wheelStepsMoveThePendingPositionFromThePlayPosition() {
        val scrub = VideoScrub()
        scrub.open()
        assertEquals(42_000L, scrub.shownMs(42_000L))
        scrub.step(forward = true, positionMs = 42_000L, durationMs = 60_000L)
        scrub.step(forward = true, positionMs = 43_000L, durationMs = 60_000L) // playback goes on: the step is from the pending position
        assertEquals(52_000L, scrub.shownMs(44_000L))
        scrub.step(forward = false, positionMs = 44_000L, durationMs = 60_000L)
        assertEquals(47_000L, scrub.commit())
        assertFalse(scrub.isActive)
    }

    @Test
    fun pendingPositionStaysInTheVideo() {
        val scrub = VideoScrub()
        scrub.open()
        scrub.step(forward = false, positionMs = 3_000L, durationMs = 60_000L)
        assertEquals(0L, scrub.pendingMs)
        scrub.cancel()
        scrub.open()
        scrub.step(forward = true, positionMs = 58_000L, durationMs = 60_000L)
        assertEquals(60_000L, scrub.pendingMs)
        // Duration not known yet: no upper limit
        scrub.cancel()
        scrub.open()
        scrub.step(forward = true, positionMs = 58_000L, durationMs = 0L)
        assertEquals(58_000L + VideoScrub.STEP_MS, scrub.pendingMs)
    }

    @Test
    fun centreWithNoWheelStepAndBackDoNotSeek() {
        val scrub = VideoScrub()
        scrub.open()
        assertTrue(scrub.isActive)
        assertNull(scrub.commit())

        scrub.open()
        scrub.step(forward = true, positionMs = 10_000L, durationMs = 60_000L)
        scrub.cancel()
        assertFalse(scrub.isActive)
        assertEquals(10_000L, scrub.shownMs(10_000L))
        assertNull(scrub.commit())
    }

    @Test
    fun wheelDoesNothingWhenScrubModeIsClosed() {
        val scrub = VideoScrub()
        scrub.step(forward = true, positionMs = 10_000L, durationMs = 60_000L)
        assertFalse(scrub.isChanged)
        assertEquals(10_000L, scrub.shownMs(10_000L))
    }
}
