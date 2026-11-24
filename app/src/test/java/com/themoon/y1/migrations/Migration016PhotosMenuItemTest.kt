package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration016PhotosMenuItemTest {
    private fun with(order: String) = NewMenuItem.insertAfter(order, Migration016PhotosMenuItem.ITEM, Migration016PhotosMenuItem.AFTER)

    @Test
    fun goesAfterVideos() {
        assertEquals("btn_now,btn_videos,btn_photos,btn_set", with("btn_now,btn_videos,btn_set"))
    }

    @Test
    fun goesAtTheEndWithoutVideos() {
        assertEquals("btn_now,btn_set,btn_photos", with("btn_now,btn_set"))
    }

    @Test
    fun keepsAnOrderThatHasIt() {
        assertEquals("btn_photos,btn_now,btn_videos", with("btn_photos,btn_now,btn_videos"))
        assertEquals("", with(""))
    }

    @Test
    fun hiddenOnlyWithoutAPhotosFolder() {
        assertFalse(NewMenuItem.startsHidden(hasFolder = true, isSdCardMounted = true))
        assertTrue(NewMenuItem.startsHidden(hasFolder = false, isSdCardMounted = true))
        // The folder can be on a card that is not mounted yet: the row stays
        assertFalse(NewMenuItem.startsHidden(hasFolder = false, isSdCardMounted = false))
    }
}
