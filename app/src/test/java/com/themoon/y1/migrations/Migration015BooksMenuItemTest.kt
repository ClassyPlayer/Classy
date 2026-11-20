package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration015BooksMenuItemTest {
    private fun with(order: String) = Migration015BooksMenuItem.withBooks(order)

    @Test
    fun goesAfterAudiobooks() {
        assertEquals("btn_now,btn_audiobooks,btn_books,btn_set", with("btn_now,btn_audiobooks,btn_set"))
    }

    @Test
    fun goesAtTheEndWithoutAudiobooks() {
        assertEquals("btn_now,btn_set,btn_books", with("btn_now,btn_set"))
    }

    @Test
    fun keepsAnOrderThatHasIt() {
        assertEquals("btn_books,btn_now,btn_audiobooks", with("btn_books,btn_now,btn_audiobooks"))
        assertEquals("", with(""))
    }

    @Test
    fun hiddenOnlyWithoutABooksFolder() {
        assertFalse(Migration015BooksMenuItem.startsHidden(hasBooksFolder = true, isSdCardMounted = true))
        assertTrue(Migration015BooksMenuItem.startsHidden(hasBooksFolder = false, isSdCardMounted = true))
        assertFalse(Migration015BooksMenuItem.startsHidden(hasBooksFolder = false, isSdCardMounted = false))
    }
}
