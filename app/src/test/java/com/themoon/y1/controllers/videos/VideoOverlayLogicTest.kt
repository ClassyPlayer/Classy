package com.themoon.y1.controllers.videos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOverlayLogicTest {

    private val logic = VideoOverlayLogic(hideDelayMs = 3_000L)

    @Test
    fun overlayHidesAfterTheDelayWhilePlaying() {
        logic.show(1_000L)
        assertTrue(logic.update(1_000L, isPlaying = true))
        assertEquals(3_000L, logic.msUntilHide(1_000L))
        assertTrue(logic.update(3_999L, isPlaying = true))
        assertEquals(1L, logic.msUntilHide(3_999L))
        assertFalse(logic.update(4_000L, isPlaying = true))
        assertEquals(-1L, logic.msUntilHide(4_000L))
    }

    @Test
    fun buttonPushShowsTheOverlayAndStartsTheDelayAgain() {
        logic.show(0L)
        assertFalse(logic.update(3_000L, isPlaying = true))
        logic.show(5_000L)
        assertTrue(logic.visible)
        assertTrue(logic.update(7_000L, isPlaying = true))
        assertFalse(logic.update(8_000L, isPlaying = true))
    }

    @Test
    fun overlayStaysWhilePausedAndTheDelayStartsWhenPlayStarts() {
        logic.show(0L)
        assertTrue(logic.update(10_000L, isPlaying = false))
        assertEquals(-1L, logic.msUntilHide(10_000L))
        // Hidden while playing, then paused (or the end of the video): it shows again
        logic.show(20_000L)
        assertFalse(logic.update(23_000L, isPlaying = true))
        assertTrue(logic.update(24_000L, isPlaying = false))
        // Play again: the delay counts from the last check while paused
        assertTrue(logic.update(25_000L, isPlaying = true))
        assertTrue(logic.update(26_999L, isPlaying = true))
        assertFalse(logic.update(27_000L, isPlaying = true))
    }

    @Test
    fun overlayShowsUntilTheVideoStartsToPlay() {
        // LibVLC starts after a short time: the delay starts at the first check that plays
        logic.show(0L)
        assertTrue(logic.update(800L, isPlaying = false))
        assertTrue(logic.update(3_500L, isPlaying = true))
        assertFalse(logic.update(3_800L, isPlaying = true))
    }

    @Test
    fun defaultDelayIsThreeAndAHalfSeconds() {
        assertEquals(3_500L, VideoOverlayLogic.HIDE_DELAY_MS)
    }
}
