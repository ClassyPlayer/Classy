package com.themoon.y1.controllers.menu

import com.themoon.y1.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuItemsTest {

    private val defaultOrder = listOf(
        "btn_now", "btn_music", "btn_videos", "btn_photos", "btn_musicquiz", "btn_games", "btn_podcasts",
        "btn_audiobooks", "btn_books", "btn_radio", "btn_bt", "btn_wifi", "btn_play_on", "btn_set", "btn_web"
    )

    @Test
    fun theMainMenuItemsAreInTheDefaultOrder() {
        assertEquals(defaultOrder, MainMenuItems.items.map { it.id })
        assertTrue(MainMenuItems.items.all { it.action.startsWith("OPEN_") })
        assertEquals("OPEN_PHOTOS", MainMenuItems.items.first { it.id == MainMenuItems.PHOTOS }.action)
    }

    @Test
    fun theSavedOrderComesFirstThenTheOtherItemsInTheDefaultOrder() {
        val ids = MainMenuItems.ordered("btn_set,btn_unknown,btn_music,").map { it.id }
        assertEquals(listOf("btn_set", "btn_music") + (defaultOrder - setOf("btn_set", "btn_music")), ids)
        assertEquals(defaultOrder, MainMenuItems.ordered("").map { it.id })
    }

    @Test
    fun hiddenItemsDoNotShow() {
        val prefs = FakePrefs(mutableMapOf("custom_menu_order" to "btn_set,btn_now", "hide_btn_btn_photos" to true, "hide_btn_btn_now" to false))
        val shown = MainMenuItems.shown(prefs).map { it.id }
        assertEquals(listOf("btn_set", "btn_now"), shown.take(2))
        assertTrue("btn_photos" !in shown)
        assertEquals(defaultOrder.size - 1, shown.size)
    }

    @Test
    fun noItemIsHiddenByDefault() {
        val prefs = FakePrefs(mutableMapOf())
        assertEquals(defaultOrder, MainMenuItems.shown(prefs).map { it.id })
        assertEquals(MusicMenuItems.items.map { it.id }, MusicMenuItems.shown(prefs).map { it.id })
        assertTrue("artists" in MusicMenuItems.items.map { it.id })
    }

    @Test
    fun theMusicMenuHasItsOwnOrderAndHiddenItems() {
        val prefs = FakePrefs(mutableMapOf("music_menu_order" to "songs,albums", "hide_music_btn_genres" to true))
        val shown = MusicMenuItems.shown(prefs).map { it.id }
        assertEquals(listOf("songs", "albums", "coverflow", "playlists", "track_artists", "artists", "compilations"), shown.take(7))
        assertFalse("genres" in shown)
        MusicMenuItems.setHidden(prefs, "artists", true)
        assertFalse("artists" in MusicMenuItems.shown(prefs).map { it.id })
        MusicMenuItems.setHidden(prefs, "artists", false)
        assertTrue("artists" in MusicMenuItems.shown(prefs).map { it.id })
        // The main menu order does not change the Music menu
        assertEquals("coverflow", MusicMenuItems.ordered("btn_set").first().id)
    }

    @Test
    fun saveOrderWritesTheIds() {
        val prefs = FakePrefs(mutableMapOf())
        MusicMenuItems.saveOrder(prefs, listOf("search", "coverflow"))
        assertEquals("search,coverflow", prefs.getString("music_menu_order", null))
        assertEquals(listOf("search", "coverflow", "playlists"), MusicMenuItems.ordered(prefs).take(3).map { it.id })
    }
}
