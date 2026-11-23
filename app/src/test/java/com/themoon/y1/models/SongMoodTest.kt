package com.themoon.y1.models

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SongMoodTest {
    private fun song(mood: String) = SongItem(File("/a.flac"), "t", "a", "b").also { it.mood = mood }

    @Test
    fun splitsSeveralMoods() {
        assertEquals(listOf("happy", "energetic"), song("happy; energetic").getMoods())
        assertEquals(listOf("happy", "party"), song("happy/party").getMoods())
    }

    @Test
    fun noMoodIsUnknownMood() {
        assertEquals(listOf("Unknown Mood"), song("").getMoods())
        assertTrue(song(" ").matchesMood("Unknown Mood"))
        assertFalse(song("happy").matchesMood("Unknown Mood"))
    }

    @Test
    fun splitsLikeTheGenres() {
        assertEquals(listOf("happy", "upbeat"), song("happy, upbeat").getMoods())
    }

    @Test
    fun matchIgnoresCase() {
        assertTrue(song("Happy; sad").matchesMood("happy"))
        assertFalse(song("Happy").matchesMood("sad"))
    }

    @Test
    fun changingTheMoodResetsTheList() {
        val s = song("happy")
        s.getMoods()
        s.mood = "sad"
        assertEquals(listOf("sad"), s.getMoods())
    }
}

class MoodCommentTest {
    @Test
    fun appendsEveryMoodComment() {
        val tags = arrayOfNulls<Any>(13)
        com.themoon.y1.managers.appendMood(tags, "MOOD=happy")
        com.themoon.y1.managers.appendMood(tags, "mood= upbeat ")
        assertEquals("happy; upbeat", tags[12])
    }

    @Test
    fun acousticBrainzMoodsKeepThePositiveOnes() {
        val tags = arrayOfNulls<Any>(13)
        for (c in listOf("AB:MOOD=Not acoustic", "AB:MOOD=Electronic", "AB:MOOD=Not happy", "AB:MOOD=Relaxed", "AB:MOOD=not sad"))
            com.themoon.y1.managers.appendMood(tags, c)
        assertEquals("Electronic; Relaxed", tags[12])
        // A plain MOOD tag keeps a value that starts with "not"
        com.themoon.y1.managers.appendMood(tags, "MOOD=Not now")
        assertEquals("Electronic; Relaxed; Not now", tags[12])
    }

    @Test
    fun emptyMoodIsIgnored() {
        val tags = arrayOfNulls<Any>(13)
        com.themoon.y1.managers.appendMood(tags, "MOOD=  ")
        assertNull(tags[12])
    }
}
