package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration016PhotosMenuItemTest {
    private fun with(order: String) = Migration016PhotosMenuItem.withPhotos(order)

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
        assertFalse(Migration016PhotosMenuItem.startsHidden(hasPhotosFolder = true, isSdCardMounted = true))
        assertTrue(Migration016PhotosMenuItem.startsHidden(hasPhotosFolder = false, isSdCardMounted = true))
        // The folder can be on a card that is not mounted yet: the row stays
        assertFalse(Migration016PhotosMenuItem.startsHidden(hasPhotosFolder = false, isSdCardMounted = false))
    }
}
