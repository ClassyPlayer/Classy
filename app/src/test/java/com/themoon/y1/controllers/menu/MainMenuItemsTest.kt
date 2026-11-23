package com.themoon.y1.controllers.menu

import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMenuItemsTest {

    private val defaultOrder = listOf(
        "btn_now", "btn_music", "btn_videos", "btn_photos", "btn_musicquiz", "btn_games", "btn_podcasts",
        "btn_audiobooks", "btn_books", "btn_radio", "btn_bt", "btn_wifi", "btn_play_on", "btn_set", "btn_web"
    )

    @Test
    fun theRowsAreInTheDefaultOrder() {
        assertEquals(defaultOrder, MainMenuItems.ITEMS.map { it.id })
        assertTrue(MainMenuItems.ITEMS.all { it.action.startsWith("OPEN_") })
        assertEquals("OPEN_PHOTOS", MainMenuItems.ITEMS.first { it.id == MainMenuItems.PHOTOS }.action)
    }

    @Test
    fun theSavedOrderComesFirstThenTheOtherRowsInTheDefaultOrder() {
        val ids = MainMenuItems.ordered("btn_set,btn_unknown,btn_music").map { it.id }
        assertEquals(listOf("btn_set", "btn_music") + (defaultOrder - setOf("btn_set", "btn_music")), ids)
        assertEquals(defaultOrder, MainMenuItems.ordered("").map { it.id })
    }

    @Test
    fun hiddenRowsDoNotShow() {
        val prefs = FakePrefs(mutableMapOf(MenuOrder.KEY to "btn_set,btn_now", "hide_btn_btn_photos" to true, "hide_btn_btn_now" to false))
        val shown = MainMenuItems.shown(prefs).map { it.id }
        assertEquals(listOf("btn_set", "btn_now"), shown.take(2))
        assertTrue("btn_photos" !in shown)
        assertEquals(defaultOrder.size - 1, shown.size)
    }
}
