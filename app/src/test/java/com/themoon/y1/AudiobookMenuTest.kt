package com.themoon.y1

import com.themoon.y1.controllers.player.NowPlayingContextMenu
import com.themoon.y1.managers.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookMenuTest {
    @Test fun sleepTimerNeverPassesTwoHours() {
        assertEquals(120, SleepTimer.clamp(500))
        assertEquals(1, SleepTimer.clamp(0))
        assertEquals(45, SleepTimer.clamp(45))
        assertEquals(120, SleepTimer.CHOICES.last())
    }

    @Test fun chapterIsTheLastOneThatStartedAtOrBeforeThePosition() {
        val starts = listOf(0, 60_000, 120_000)
        assertEquals(0, NowPlayingContextMenu.chapterIndexAt(starts, 59_999))
        assertEquals(1, NowPlayingContextMenu.chapterIndexAt(starts, 60_000))
        assertEquals(2, NowPlayingContextMenu.chapterIndexAt(starts, 999_999))
        assertEquals(0, NowPlayingContextMenu.chapterIndexAt(emptyList(), 5))
    }
}
