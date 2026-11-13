package com.themoon.y1.managers

import com.themoon.y1.models.SongItem
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaLibraryLengthFillTest {
    private val library = MediaLibraryManager.getInstance()

    @After
    fun clear() {
        library.musicLibrary.clear()
        library.audiobookLibrary.clear()
    }

    private fun song(path: String, ms: Long) = SongItem(File(path), "t", "a", "b").also { it.durationMs = ms }

    @Test
    fun noLengthIsMissingWhenEachSongHasItsReadLength() {
        library.musicLibrary.add(song("/m/a.flac", 1000L))
        library.musicLibrary.add(song("/m/b.opus", 0L))
        // b.opus was read, but its length is not known: not missing
        assertFalse(library.hasMissingLengths(mapOf("/m/a.flac" to 1000L, "/m/b.opus" to 0L)))
    }

    @Test
    fun aCacheLoadThatReplacedTheSongsLosesTheirLengths() {
        // The new song object from a cache load has no length, but the fill read one for its path
        library.musicLibrary.add(song("/m/a.flac", 0L))
        assertTrue(library.hasMissingLengths(mapOf("/m/a.flac" to 1000L)))
    }

    @Test
    fun aSongThatWasNotReadIsMissing() {
        library.audiobookLibrary.add(song("/b/new.m4b", 0L))
        assertTrue(library.hasMissingLengths(emptyMap()))
    }
}
