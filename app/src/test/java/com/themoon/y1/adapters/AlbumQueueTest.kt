package com.themoon.y1.adapters

import org.junit.Assert.assertEquals
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
}
