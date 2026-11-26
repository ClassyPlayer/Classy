package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviousButtonTest {

    @Test
    fun offAlwaysGoesToThePreviousSong() {
        assertFalse(PreviousButton.restarts(enabled = false, positionMs = 0L))
        assertFalse(PreviousButton.restarts(enabled = false, positionMs = 120_000L))
    }

    @Test
    fun onRestartsASongThatPlayedMoreThanThreeSeconds() {
        assertFalse(PreviousButton.restarts(enabled = true, positionMs = 0L))
        assertFalse(PreviousButton.restarts(enabled = true, positionMs = PreviousButton.RESTART_AFTER_MS))
        assertTrue(PreviousButton.restarts(enabled = true, positionMs = PreviousButton.RESTART_AFTER_MS + 1))
        assertTrue(PreviousButton.restarts(enabled = true, positionMs = 120_000L))
    }
}
