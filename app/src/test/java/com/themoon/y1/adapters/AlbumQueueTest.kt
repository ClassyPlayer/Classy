package com.themoon.y1.adapters

import com.themoon.y1.managers.AudioPlayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlbumQueueTest {
    private fun f(name: String) = File("/music/$name")

    @Test
    fun albumGoesInTrackOrderAndEqualNumbersGoByTitle() {
        val tracks = listOf(f("b"), f("c"), f("a"), f("d"))
        val numbers = mapOf("/music/a" to 1, "/music/b" to 2, "/music/c" to 2) // d has no number: first
        val titles = mapOf("/music/b" to "Zebra", "/music/c" to "apple", "/music/a" to "Mid", "/music/d" to "Last")
        // b and c share track 2: "apple" before "Zebra" (ignoring case), as opening the album lists them
        assertEquals(listOf(f("d"), f("a"), f("c"), f("b")), inTrackOrder(tracks, numbers) { titles.getValue(it.absolutePath) })
    }

    @Test
    fun noQueueOrABadIndexIsEmpty() {
        val apm = AudioPlayerManager.getInstance()
        assertTrue(apm.queueIsEmpty(emptyList(), 0))
        assertTrue(apm.queueIsEmpty(listOf(f("a")), 3))
        assertFalse(apm.queueIsEmpty(listOf(f("a"), f("b")), 1))
    }

    @Test
    fun aPodcastStreamAloneIsAnEmptyQueue() {
        val apm = AudioPlayerManager.getInstance()
        val stream = File("/PODCAST_STREAM/Chan/Episode")
        assertTrue(apm.queueIsEmpty(listOf(stream), 0))
        // A stream with other tracks is a real queue
        assertFalse(apm.queueIsEmpty(listOf(stream, f("a")), 0))
    }
}
