package com.themoon.y1.models

import com.themoon.y1.models.ReleaseTypes.Primary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ReleaseTypesTest {

    private fun track(album: String, type: String = "", n: Int = 1) =
        SongItem(File("/Music/$album/$n.mp3"), "T$n", "Artist", album).also { it.releaseType = type }

    private fun album(title: String, count: Int, type: String = "") = (1..count).map { track(title, type, it) }

    @Test fun normalizeLowerCasesAndJoinsWithSemicolons() {
        assertEquals("album;live", ReleaseTypes.normalize("Album/Live"))           // Picard ID3v2.3
        assertEquals("album;compilation", ReleaseTypes.normalize("album\u0000compilation")) // ID3v2.4
        assertEquals("single;remix", ReleaseTypes.normalize("Single, Remix"))      // Navidrome
        assertEquals("ep;soundtrack", ReleaseTypes.normalize(" EP ; soundtrack;"))  // Vorbis fields joined by the reader
    }

    @Test fun normalizeKeepsMixtapeStreetAndDropsDuplicates() {
        assertEquals("album;mixtape/street", ReleaseTypes.normalize("Album/Mixtape/Street"))
        assertEquals("single", ReleaseTypes.normalize("single;SINGLE;﻿single"))
    }

    @Test fun normalizeOfNothingIsEmpty() {
        assertEquals("", ReleaseTypes.normalize(null))
        assertEquals("", ReleaseTypes.normalize("  "))
        assertEquals("", ReleaseTypes.normalize(" / ;"))
    }

    @Test fun primaryIsTheFirstPrimaryToken() {
        assertEquals(Primary.SINGLE, ReleaseTypes.primaryOf("single;remix"))
        assertEquals(Primary.ALBUM, ReleaseTypes.primaryOf("compilation;album"))
        assertEquals(Primary.EP, ReleaseTypes.primaryOf("ep"))
        assertNull(ReleaseTypes.primaryOf("live"))
        assertNull(ReleaseTypes.primaryOf(""))
    }

    @Test fun titleSuffixOfTheItunesStore() {
        assertEquals(Primary.SINGLE, ReleaseTypes.primaryOfTitle("Midnight City - Single"))
        assertEquals(Primary.EP, ReleaseTypes.primaryOfTitle("Night Drive - EP"))
        assertEquals(Primary.EP, ReleaseTypes.primaryOfTitle("Night Drive – ep "))
        assertNull(ReleaseTypes.primaryOfTitle("Singles"))
        assertNull(ReleaseTypes.primaryOfTitle("The Single"))
        assertNull(ReleaseTypes.primaryOfTitle("Greatest-EP"))
    }

    @Test fun mostTaggedTracksWin() {
        val tracks = album("Mixed", 3, "single") + track("Mixed", "album", 4) + track("Mixed", "", 5)
        assertEquals(Primary.SINGLE, ReleaseTypes.primaryOfAlbum(tracks, 0))
    }

    @Test fun tagWinsOverTitleAndTrackCount() {
        assertEquals(Primary.ALBUM, ReleaseTypes.primaryOfAlbum(album("Hits - Single", 1, "album"), 5))
    }

    @Test fun titleWinsOverTrackCount() {
        assertEquals(Primary.EP, ReleaseTypes.primaryOfAlbum(album("Four - EP", 4), 5))
    }

    @Test fun trackCountOnlyForUntaggedAlbumsAndOnlyWhenOn() {
        assertNull(ReleaseTypes.primaryOfAlbum(album("Short", 2), 0))
        assertEquals(Primary.SINGLE, ReleaseTypes.primaryOfAlbum(album("Short", 2), 3))
        assertNull(ReleaseTypes.primaryOfAlbum(album("Long", 4), 3))
        // A tagged album keeps its type whatever its length
        assertEquals(Primary.ALBUM, ReleaseTypes.primaryOfAlbum(album("Short", 2, "album;live"), 3))
        // A secondary type alone is still a tag: no guess from the length or the title
        assertNull(ReleaseTypes.primaryOfAlbum(album("Short", 2, "live"), 3))
        assertNull(ReleaseTypes.primaryOfAlbum(album("Collection - Single", 1, "compilation"), 0))
    }

    @Test fun noTracksNoType() = assertNull(ReleaseTypes.primaryOfAlbum(emptyList(), 5))
}
