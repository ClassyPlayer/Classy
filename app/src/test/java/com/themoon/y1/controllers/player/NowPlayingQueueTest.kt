package com.themoon.y1.controllers.player

import com.themoon.y1.managers.AudioPlayerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingQueueTest {
    @Test
    fun aShortQueueShowsWhole() = assertEquals(0..4, NowPlayingContextMenu.queueWindow(5, 3))

    @Test
    fun aLongQueueShowsAWindowAroundTheCenter() {
        assertEquals(1950 until 2050, NowPlayingContextMenu.queueWindow(5000, 2000))
        assertEquals(1 until 11, NowPlayingContextMenu.queueWindow(5000, 6, rows = 10))
    }

    @Test
    fun theWindowStaysInsideTheQueue() {
        assertEquals(0 until 100, NowPlayingContextMenu.queueWindow(5000, 10))
        assertEquals(4900 until 5000, NowPlayingContextMenu.queueWindow(5000, 4990))
        assertEquals(0 until 100, NowPlayingContextMenu.queueWindow(5000, -300))
        assertEquals(IntRange.EMPTY, NowPlayingContextMenu.queueWindow(0, 0))
    }

    @Test
    fun jumpToIgnoresAnIndexOutsideTheQueue() {
        // No activity and so no queue here: nothing plays and nothing throws
        val apm = AudioPlayerManager.getInstance()
        apm.jumpTo(-1)
        apm.jumpTo(7)
    }
}
