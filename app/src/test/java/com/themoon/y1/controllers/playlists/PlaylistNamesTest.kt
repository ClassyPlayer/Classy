package com.themoon.y1.controllers.playlists

import com.themoon.y1.controllers.playlists.PlaylistNames.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistNamesTest {

    private val badCharacters = "A name cannot have these characters: / \\ : * ? \" < > |"
    private val taken = "A playlist with this name already exists."

    @Test
    fun trimsTheNameAndKeepsTheExtension() {
        assertEquals(Result.Ok("Road Trip.m3u8"), PlaylistNames.newFileName("  Road Trip ", "Playlist 1.m3u8", emptyList()))
        assertEquals(Result.Ok("Jazz.M3U"), PlaylistNames.newFileName("Jazz", "old.name.M3U", emptyList()))
        assertEquals(Result.Ok("Mr. Big.m3u"), PlaylistNames.newFileName("Mr. Big", "Rock.m3u", emptyList()))
    }

    @Test
    fun refusesAnEmptyName() {
        assertEquals(Result.Refused("Please enter a name."), PlaylistNames.newFileName("   ", "Rock.m3u8", emptyList()))
        assertEquals("Please enter a name.", PlaylistNames.problem(""))
    }

    @Test
    fun refusesAHiddenName() {
        assertEquals(Result.Refused("A playlist name cannot start with a dot."), PlaylistNames.newFileName("._Rock", "Rock.m3u8", emptyList()))
        assertEquals(Result.Refused("A playlist name cannot start with a dot."), PlaylistNames.newFileName(" .Rock", "Rock.m3u8", emptyList()))
    }

    @Test
    fun refusesCharactersThatFatDoesNotPermit() {
        for (c in listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|", "\u0000", "\n", "\t", "\u007F")) {
            assertEquals(c, Result.Refused(badCharacters), PlaylistNames.newFileName("Rock${c}Pop", "Rock.m3u8", emptyList()))
        }
        assertNull(PlaylistNames.problem("Rock & Pop (Live) [2024] #1 – Ação, 日本"))
    }

    @Test
    fun refusesTheNameOfAnotherPlaylistInAnyCase() {
        val others = listOf("Rock", "Jazz")
        assertEquals(Result.Refused(taken), PlaylistNames.newFileName("rock", "Pop.m3u8", others))
        assertEquals(Result.Refused(taken), PlaylistNames.newFileName("JAZZ ", "Pop.m3u", others))
        assertEquals(Result.Ok("Rock 2.m3u8"), PlaylistNames.newFileName("Rock 2", "Pop.m3u8", others))
    }

    @Test
    fun refusesTheOnTheGoName() {
        assertEquals(Result.Refused(taken), PlaylistNames.newFileName("on-the-go", "Pop.m3u8", emptyList()))
    }

    @Test
    fun aChangeOfCaseOnlyIsPermitted() {
        // The playlist itself is not in the other names: "rock" can become "Rock"
        assertEquals(Result.Ok("Rock.m3u8"), PlaylistNames.newFileName("Rock", "rock.m3u8", listOf("Jazz")))
    }
}
