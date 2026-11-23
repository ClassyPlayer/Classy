package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QueueIsEmptyTest {
    private fun f(name: String) = File("/music/$name")

    @Test
    fun noQueueOrABadIndexIsEmpty() {
        assertTrue(AudioPlayerManager.queueIsEmpty(emptyList(), 0))
        assertTrue(AudioPlayerManager.queueIsEmpty(listOf(f("a")), 3))
        assertFalse(AudioPlayerManager.queueIsEmpty(listOf(f("a"), f("b")), 1))
    }

    @Test
    fun aPodcastStreamAloneIsAnEmptyQueue() {
        val stream = File("/PODCAST_STREAM/Chan/Episode")
        assertTrue(AudioPlayerManager.queueIsEmpty(listOf(stream), 0))
        // A stream with other tracks is a real queue
        assertFalse(AudioPlayerManager.queueIsEmpty(listOf(stream, f("a")), 0))
    }
}
