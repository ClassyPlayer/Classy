package com.themoon.y1.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArtistNamesTest {

    @Test
    fun split_onSemicolonsAndNullBytes() {
        assertEquals(listOf("Beau Black", "Alex Cartana"), ArtistNames.split("Beau Black;Alex Cartana"))
        assertEquals(listOf("A", "B", "C"), ArtistNames.split("A ; B\u0000C;;"))
    }

    @Test
    fun split_keepsCommasAmpersandsAndSlashesInNames() {
        assertEquals(listOf("Earth, Wind & Fire"), ArtistNames.split("Earth, Wind & Fire"))
        assertEquals(listOf("AC/DC"), ArtistNames.split("AC/DC"))
    }

    @Test
    fun split_dropsRepeatsIgnoringCase() {
        assertEquals(listOf("Patrick Stump", "Disney Junior"), ArtistNames.split("Patrick Stump;Disney Junior;disney junior"))
    }

    @Test
    fun split_blankGivesNoNames() {
        assertEquals(emptyList<String>(), ArtistNames.split(""))
        assertEquals(emptyList<String>(), ArtistNames.split(null))
    }

    @Test
    fun display_joinsWithCommas() {
        assertEquals("Mark Hoppus, Disney Junior", ArtistNames.display("Mark Hoppus;Disney Junior"))
        assertEquals("Queen", ArtistNames.display("Queen"))
    }

    @Test
    fun contains_matchesOneNameIgnoringCase() {
        assertTrue(ArtistNames.contains("Beau Black;Alex Cartana", "alex cartana"))
        assertTrue(ArtistNames.contains("Queen", "Queen"))
        assertFalse(ArtistNames.contains("Beau Black;Alex Cartana", "Beau"))
        assertFalse(ArtistNames.contains("Queen", ""))
    }

    @Test
    fun contains_matchesTheWholeTagValue() {
        // Now Playing → Browse Artist and old saved queries can give the full "A;B" value
        assertTrue(ArtistNames.contains("Beau Black;Alex Cartana", "Beau Black;Alex Cartana"))
        assertTrue(ArtistNames.contains("Beau Black;Alex Cartana", "beau black;alex cartana"))
        assertFalse(ArtistNames.contains(null, "Queen"))
    }

    @Test
    fun distinctIgnoringCase_keepsTheFirstSpelling() {
        assertEquals(listOf("Queen", "ABBA"), ArtistNames.distinctIgnoringCase(listOf("Queen", "ABBA", "queen", "Abba")))
        assertEquals(emptyList<String>(), ArtistNames.distinctIgnoringCase(emptyList()))
    }

    @Test
    fun songItem_matchesTrackOrAlbumArtist() {
        val song = SongItem(File("/storage/sdcard1/Music/a.mp3"), "T", "Beau Black;Alex Cartana", "Album", null, null, "Disney;Disney Junior")
        assertTrue(song.matchesArtist("Alex Cartana", trackArtist = true))
        assertFalse(song.matchesArtist("Disney Junior", trackArtist = true))
        assertTrue(song.matchesArtist("Disney Junior", trackArtist = false))
        assertEquals(listOf("Beau Black", "Alex Cartana"), song.getArtists())
        assertEquals(listOf("Disney", "Disney Junior"), song.getAlbumArtists())
    }
}
