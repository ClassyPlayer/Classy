package com.themoon.y1.adapters

import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SongHoldTest {
    private fun song(path: String) = SongItem(File(path), "T", "A", "B")

    @Test
    fun theOccurrenceCountsTheRowsBeforeWithTheSameFile() {
        val items = listOf(song("/m/a.mp3"), song("/m/b.mp3"), song("/m/a.mp3"), song("/m/a.mp3"))
        assertEquals(0, occurrenceOf(items, 0))
        assertEquals(0, occurrenceOf(items, 1))
        assertEquals(1, occurrenceOf(items, 2))
        assertEquals(2, occurrenceOf(items, 3))
    }
}
