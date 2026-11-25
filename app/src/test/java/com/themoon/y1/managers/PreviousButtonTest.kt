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

    @Test
    fun onlyANewInstallGetsTheRestart() {
        assertFalse(PreviousButton.keepsOldBehaviour(startingVersion = 0, hasPlayed = false))
        // A player from before the migrations that played music
        assertTrue(PreviousButton.keepsOldBehaviour(startingVersion = 0, hasPlayed = true))
        assertTrue(PreviousButton.keepsOldBehaviour(startingVersion = 19, hasPlayed = false))
        assertTrue(PreviousButton.keepsOldBehaviour(startingVersion = 20, hasPlayed = true))
    }
}
