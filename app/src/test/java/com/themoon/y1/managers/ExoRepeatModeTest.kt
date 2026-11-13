package com.themoon.y1.managers

import com.google.android.exoplayer2.Player
import com.themoon.y1.managers.AudioPlayerManager.Companion.exoRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ExoRepeatModeTest {

    @Test
    fun repeatOffIsOffInBothQueues() {
        assertEquals(Player.REPEAT_MODE_OFF, exoRepeatMode(0, gapless = true))
        assertEquals(Player.REPEAT_MODE_OFF, exoRepeatMode(0, gapless = false))
    }

    @Test
    fun repeatOneRepeatsTheTrackInBothQueues() {
        assertEquals(Player.REPEAT_MODE_ONE, exoRepeatMode(1, gapless = true))
        assertEquals(Player.REPEAT_MODE_ONE, exoRepeatMode(1, gapless = false))
    }

    @Test
    fun repeatAllRepeatsTheGaplessQueue() {
        assertEquals(Player.REPEAT_MODE_ALL, exoRepeatMode(2, gapless = true))
    }

    @Test
    fun repeatAllLetsASingleTrackEnd() {
        // Only the current track is in ExoPlayer: the track must end, so that the next track starts.
        assertEquals(Player.REPEAT_MODE_OFF, exoRepeatMode(2, gapless = false))
    }

    @Test
    fun anUnknownValueIsOff() {
        for (gapless in listOf(true, false)) {
            assertEquals(Player.REPEAT_MODE_OFF, exoRepeatMode(-1, gapless))
            assertEquals(Player.REPEAT_MODE_OFF, exoRepeatMode(3, gapless))
        }
    }
}
