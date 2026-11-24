package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration015BooksMenuItemTest {
    private fun with(order: String) = NewMenuItem.insertAfter(order, Migration015BooksMenuItem.ITEM, Migration015BooksMenuItem.AFTER)

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
        assertFalse(NewMenuItem.startsHidden(hasFolder = true, isSdCardMounted = true))
        assertTrue(NewMenuItem.startsHidden(hasFolder = false, isSdCardMounted = true))
        assertFalse(NewMenuItem.startsHidden(hasFolder = false, isSdCardMounted = false))
    }
}
