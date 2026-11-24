package com.themoon.y1.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class M3uEditTest {

    private val lines = listOf(
        "#EXTM3U",
        "#EXTINF:120,Band - Intro",
        "/Music/A/01 Intro.mp3",
        "",
        "/Music/B/01 Intro.mp3", // the same file name in another album
        "#EXTINF:200,Band - Song",
        "/Music/A/02 Song.mp3",
        "/Music/A/02 Song.mp3", // the song a second time
    )

    @Test
    fun removesTheEntryOfThatFileAndItsExtinf() {
        val kept = M3uEdit.removeEntry(lines, 0) { it == "/Music/B/01 Intro.mp3" }
        assertEquals(lines - "/Music/B/01 Intro.mp3", kept)
        // The other "01 Intro.mp3", with its #EXTINF line
        assertEquals(listOf("#EXTM3U", "", "/Music/B/01 Intro.mp3", "#EXTINF:200,Band - Song", "/Music/A/02 Song.mp3", "/Music/A/02 Song.mp3"),
            M3uEdit.removeEntry(lines, 0) { it == "/Music/A/01 Intro.mp3" })
    }

    @Test
    fun theOccurrenceChoosesWhichCopyGoes() {
        // The second copy: the first keeps its #EXTINF line
        assertEquals(lines.dropLast(1), M3uEdit.removeEntry(lines, 1) { it == "/Music/A/02 Song.mp3" })
        assertNull(M3uEdit.removeEntry(lines, 2) { it == "/Music/A/02 Song.mp3" })
    }

    @Test
    fun commentsAndEmptyLinesAreNeverEntries() {
        assertEquals(lines.size - 2, M3uEdit.removeEntry(lines, 0) { true }!!.size) // the first entry and its #EXTINF
        assertNull(M3uEdit.removeEntry(listOf("#EXTM3U", "", "# a comment"), 0) { true })
    }

    @Test
    fun newSongsKeepTheOrderAndSkipWhatIsThere() {
        val a = File("/Music/a.mp3")
        val b = File("/Music/b.mp3")
        val c = File("/Music/c.mp3")
        assertEquals(listOf(c, a), M3uEdit.newSongs(listOf(b, c, a, c), setOf(b.absolutePath)))
        assertEquals(emptyList<File>(), M3uEdit.newSongs(listOf(a), setOf(a.absolutePath)))
    }
}
