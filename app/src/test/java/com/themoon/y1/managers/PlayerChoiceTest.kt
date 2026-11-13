package com.themoon.y1.managers

import com.themoon.y1.managers.PlayerChoice.Engine.EXO_PLAYER
import com.themoon.y1.managers.PlayerChoice.Engine.MEDIA_PLAYER
import com.themoon.y1.managers.PlayerChoice.StartCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChoiceTest {

    private val flac = "/storage/sdcard1/Music/A/01 Song.flac"
    private val mp3 = "/storage/sdcard1/Music/A/02 Song.mp3"

    @Test
    fun playback_flacGoesToMediaPlayerFirst_otherFilesToExoPlayerOnly() {
        assertEquals(listOf(MEDIA_PLAYER, EXO_PLAYER), PlayerChoice.forPlayback(flac))
        assertEquals(listOf(MEDIA_PLAYER, EXO_PLAYER), PlayerChoice.forPlayback("/x/SONG.FLAC"))
        assertEquals(listOf(EXO_PLAYER), PlayerChoice.forPlayback(mp3))
        assertEquals(listOf(EXO_PLAYER), PlayerChoice.forPlayback("/x/song.flac.m4a"))
    }

    @Test
    fun clip_mediaPlayerFirstForEachFile() {
        assertEquals(listOf(MEDIA_PLAYER, EXO_PLAYER), PlayerChoice.FOR_CLIP)
    }

    @Test
    fun fail_givesTheNextPlayer_andLaterPlaysUseIt() {
        val choice = PlayerChoice()
        val order = PlayerChoice.forPlayback(flac)
        assertEquals(MEDIA_PLAYER, choice.choose(flac, order))

        assertEquals(EXO_PLAYER, choice.fail(flac, MEDIA_PLAYER, order))
        assertTrue(choice.hasFailed(flac, MEDIA_PLAYER))
        assertEquals(EXO_PLAYER, choice.choose(flac, order))

        // Other files keep their first player
        val other = "/storage/sdcard1/Music/A/03 Other.flac"
        assertEquals(MEDIA_PLAYER, choice.choose(other, order))
        assertFalse(choice.hasFailed(other, MEDIA_PLAYER))
    }

    @Test
    fun fail_ofAllPlayers_givesNull_andTheLastPlayerIsTriedAgain() {
        val choice = PlayerChoice()
        val order = PlayerChoice.forPlayback(flac)
        choice.fail(flac, MEDIA_PLAYER, order)
        assertNull(choice.fail(flac, EXO_PLAYER, order))
        assertEquals(EXO_PLAYER, choice.choose(flac, order))

        // A file with one player: no next player
        val single = PlayerChoice.forPlayback(mp3)
        assertNull(choice.fail(mp3, EXO_PLAYER, single))
        assertEquals(EXO_PLAYER, choice.choose(mp3, single))
    }

    @Test
    fun fail_ofTheSecondPlayerFirst_givesTheFirstPlayer() {
        val choice = PlayerChoice()
        val order = PlayerChoice.FOR_CLIP
        assertEquals(MEDIA_PLAYER, choice.fail(flac, EXO_PLAYER, order))
        assertEquals(MEDIA_PLAYER, choice.choose(flac, order))
        assertNull(choice.fail(flac, MEDIA_PLAYER, order))
    }

    @Test
    fun checkStart_progressStallAndSeekBack() {
        assertEquals(StartCheck.PLAYS, PlayerChoice.checkStart(0L, 3_500L))
        assertEquals(StartCheck.PLAYS, PlayerChoice.checkStart(60_000L, 60_000L + PlayerChoice.MIN_PROGRESS_MS))
        assertEquals(StartCheck.STALLED, PlayerChoice.checkStart(0L, 0L))
        assertEquals(StartCheck.STALLED, PlayerChoice.checkStart(60_000L, 60_200L))
        assertEquals(StartCheck.STALLED, PlayerChoice.checkStart(60_000L, 59_800L))
        assertEquals(StartCheck.CHECK_AGAIN, PlayerChoice.checkStart(60_000L, 10_000L))
    }
}
