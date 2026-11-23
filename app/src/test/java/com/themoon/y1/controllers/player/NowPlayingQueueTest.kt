package com.themoon.y1.controllers.player

import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.managers.AudioPlayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun laterTracksShowsTheTracksRightAfterThePageWithNoTrackAgain() {
        assertEquals(100 until 200, NowPlayingContextMenu.laterPage(0 until 100, 5000))
        // The end of the queue: a shorter page
        assertEquals(4900 until 4950, NowPlayingContextMenu.laterPage(4800 until 4900, 4950))
    }

    @Test
    fun earlierTracksShowsTheTracksRightBeforeThePage() {
        assertEquals(100 until 200, NowPlayingContextMenu.earlierPage(200 until 300))
        // The start of the queue: a shorter page
        assertEquals(0 until 37, NowPlayingContextMenu.earlierPage(37 until 137))
    }

    private val queue = List(300) { File("/music/track$it.flac") }

    private fun rows(window: IntRange, playing: Int = 150, jumps: MutableList<Int> = ArrayList(), pages: MutableList<IntRange> = ArrayList()) =
        NowPlayingContextMenu.queueRows(queue, playing, window, { it }, { it.nameWithoutExtension }, { jumps += it }, { pages += it })

    @Test
    fun thePageRowsSelectTheTrackNextToThePage() {
        val pages = ArrayList<IntRange>()
        val rows = rows(100 until 200, pages = pages)
        assertEquals(listOf("Earlier Tracks", "track100"), rows.take(2).map { it.label })
        assertEquals(listOf("track199", "Later Tracks"), rows.takeLast(2).map { it.label })
        // The Later row has the key of track 200, the first row of the next page: that row is selected then (keptRow)
        assertEquals(NowPlayingContextMenu.queueKey(200, queue[200]), rows.last().key)
        assertEquals(NowPlayingContextMenu.queueKey(99, queue[99]), rows.first().key)
        rows.last().run()
        rows.first().run()
        assertEquals(listOf(200 until 300, 0 until 100), pages)
        val next = rows(200 until 300)
        assertEquals(rows.last().key, next[1].key)
        assertEquals(NowPlayingContextMenu.queueKey(200, queue[200]), next[1].key)
        // The last page has no Later row; the first page has no Earlier row
        assertEquals("track299", next.last().label)
        assertEquals("track0", rows(0 until 100).first().label)
    }

    @Test
    fun aTrackRowPlaysItsTrackAndThePlayingTrackHasTheCheckMark() {
        val jumps = ArrayList<Int>()
        val rows = rows(100 until 200, playing = 150, jumps = jumps)
        assertEquals(listOf("track150"), rows.filter { it.checked == true }.map { it.label })
        rows.first { it.label == "track160" }.run()
        assertEquals(listOf(160), jumps)
    }

    @Test
    fun aNewPlayingTrackKeepsTheKeysOfThePage() {
        // Only the check mark moves: the selected row (by key) stays on the same track
        val before = ActionScreens.list(ActionScreens.Spec("Queue", actions = rows(200 until 300, playing = 150))) {}
        val after = ActionScreens.list(ActionScreens.Spec("Queue", actions = rows(200 until 300, playing = 151))) {}
        assertEquals(before.rows.map { it.key }, after.rows.map { it.key })
    }

    @Test
    fun aTrackAtTheSamePlaceInAnotherQueueHasAnotherKey() =
        assertNotEquals(NowPlayingContextMenu.queueKey(5, File("/a.flac")), NowPlayingContextMenu.queueKey(5, File("/b.flac")))

    @Test
    fun jumpToPlaysOnlyAnEntryOfTheQueue() {
        assertTrue(AudioPlayerManager.canJumpTo(0, 5))
        assertTrue(AudioPlayerManager.canJumpTo(4, 5))
        assertFalse(AudioPlayerManager.canJumpTo(-1, 5))
        assertFalse(AudioPlayerManager.canJumpTo(5, 5))
        assertFalse(AudioPlayerManager.canJumpTo(0, 0))
        // No activity and so no queue here: nothing plays and nothing throws
        AudioPlayerManager.getInstance().jumpTo(0)
    }
}
