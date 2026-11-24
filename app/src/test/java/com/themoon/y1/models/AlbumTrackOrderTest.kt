package com.themoon.y1.models

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AlbumTrackOrderTest {
    private fun f(name: String) = File("/music/$name")

    // d has no number (first); b and c share track 2: "apple" before "Zebra" (ignoring case); e and a are 10 and 1
    private val numbers = mapOf("/music/a" to 1, "/music/b" to 2, "/music/c" to 2, "/music/e" to 10)
    private val titles = mapOf("/music/a" to "Mid", "/music/b" to "Zebra", "/music/c" to "apple", "/music/d" to "Last", "/music/e" to "Ant")
    private val expected = listOf("d", "a", "c", "b", "e")

    @Test
    fun filesGoByTrackNumberThenTitle() {
        val tracks = listOf("e", "b", "c", "a", "d").map(::f)
        val order = tracks.sortedWith(AlbumTrackOrder.files(numbers) { titles.getValue(it.absolutePath) })
        assertEquals(expected.map(::f), order)
    }

    @Test
    fun songsGoInTheSameOrderAsFiles() {
        // The album screen sorts songs, the album hold menu sorts files: the two orders must be the same
        val songs = listOf("b", "e", "d", "a", "c").map { SongItem(f(it), titles.getValue(f(it).absolutePath), "", "") }
        val order = songs.sortedWith(AlbumTrackOrder.songs(numbers)).map { it.file }
        assertEquals(expected.map(::f), order)
    }

    @Test
    fun noNumbersGoByTitle() {
        val songs = listOf("b", "A", "c").map { SongItem(f(it), it, "", "") }
        assertEquals(listOf("A", "b", "c"), songs.sortedWith(AlbumTrackOrder.songs(emptyMap())).map { it.title })
    }
}
