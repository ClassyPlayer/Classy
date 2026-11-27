package com.themoon.y1

import com.themoon.y1.controllers.player.NowPlayingContextMenu
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookMenuTest {
    @Test fun chapterIsTheLastOneThatStartedAtOrBeforeThePosition() {
        val starts = listOf(0, 60_000, 120_000)
        assertEquals(0, NowPlayingContextMenu.chapterIndexAt(starts, 59_999))
        assertEquals(1, NowPlayingContextMenu.chapterIndexAt(starts, 60_000))
        assertEquals(2, NowPlayingContextMenu.chapterIndexAt(starts, 999_999))
        assertEquals(0, NowPlayingContextMenu.chapterIndexAt(emptyList(), 5))
    }
}
