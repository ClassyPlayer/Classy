package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration012GamesMenuItemTest {
    private fun with(order: String) = NewMenuItem.insertAfter(order, Migration012GamesMenuItem.ITEM, Migration012GamesMenuItem.AFTER)

    @Test
    fun goesAfterMusicQuiz() {
        assertEquals("btn_now,btn_musicquiz,btn_games,btn_set", with("btn_now,btn_musicquiz,btn_set"))
    }

    @Test
    fun goesAtTheEndWithoutMusicQuiz() {
        assertEquals("btn_now,btn_set,btn_games", with("btn_now,btn_set"))
    }

    @Test
    fun keepsAnOrderThatHasIt() {
        assertEquals("btn_games,btn_now,btn_musicquiz", with("btn_games,btn_now,btn_musicquiz"))
        assertEquals("", with(""))
    }

    @Test
    fun hiddenOnlyWithoutAGamesFolder() {
        assertFalse(NewMenuItem.startsHidden(hasFolder = true, isSdCardMounted = true))
        assertTrue(NewMenuItem.startsHidden(hasFolder = false, isSdCardMounted = true))
        // The folder can be on a card that is not mounted yet: the row stays
        assertFalse(NewMenuItem.startsHidden(hasFolder = false, isSdCardMounted = false))
    }
}
