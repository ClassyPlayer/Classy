package com.themoon.y1.migrations

import com.themoon.y1.controllers.menu.MainMenuItems
import com.themoon.y1.controllers.menu.MusicMenuItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration022ShowMenuItemsTest {

    private fun migrate(settings: MutableMap<String, Any?>): MoveMisplacedPrefsTest.FakePrefs {
        val prefs = MoveMisplacedPrefsTest.FakePrefs(settings)
        Migration022ShowMenuItems().migrate(MigrationContext(null, null, prefs, 21))
        return prefs
    }

    @Test
    fun showsGamesBooksAndPhotosAgain() {
        val prefs = migrate(mutableMapOf(
            "hide_btn_btn_games" to true,
            "hide_btn_btn_books" to true,
            "hide_btn_btn_photos" to true
        ))
        assertTrue(prefs.all.isEmpty())
        for (id in listOf("btn_games", "btn_books", "btn_photos")) assertFalse(MainMenuItems.isHidden(prefs, id))
    }

    @Test
    fun keepsTheOtherSettings() {
        // A saved "not hidden", the other hidden items, Album Artists (only the user saves its key) and the order stay
        val settings = mutableMapOf<String, Any?>(
            "hide_btn_btn_games" to false,
            "hide_btn_btn_videos" to true,
            "hide_music_btn_artists" to true,
            "custom_menu_order" to "btn_set,btn_now"
        )
        val prefs = migrate(HashMap(settings))
        assertEquals(settings, prefs.all)
        assertTrue(MainMenuItems.isHidden(prefs, "btn_videos"))
        assertTrue(MusicMenuItems.isHidden(prefs, "artists"))
    }

    @Test
    fun aNewInstallHasNothingToChange() {
        assertTrue(migrate(mutableMapOf()).all.isEmpty())
    }

    @Test
    fun runsTwiceWithTheSameResult() {
        val prefs = migrate(mutableMapOf("hide_btn_btn_games" to true, "hide_btn_btn_wifi" to true))
        Migration022ShowMenuItems().migrate(MigrationContext(null, null, prefs, 22))
        assertEquals(mapOf("hide_btn_btn_wifi" to true), prefs.all)
    }

    @Test
    fun theKeysAreTheHideKeysOfGamesBooksAndPhotos() {
        assertEquals(listOf("hide_btn_btn_games", "hide_btn_btn_books", "hide_btn_btn_photos"), Migration022ShowMenuItems.KEYS)
        assertTrue(MainMenuItems.items.map { it.id }.containsAll(Migration022ShowMenuItems.ITEMS))
    }

    @Test
    fun runsOnceOnAnUpdate() {
        val prefs = MoveMisplacedPrefsTest.FakePrefs(mutableMapOf(MigrationManager.KEY_CURRENT_VERSION to 21, "hide_btn_btn_books" to true))
        val manager = MigrationManager(null, prefs)
        manager.registerMigration(Migration022ShowMenuItems())
        assertEquals(1, manager.applyPendingMigrations())
        assertFalse(prefs.contains("hide_btn_btn_books"))
        assertEquals(22, manager.currentVersion)
        // A hide after the update stays
        MainMenuItems.setHidden(prefs, "btn_books", true)
        assertEquals(0, manager.applyPendingMigrations())
        assertTrue(MainMenuItems.isHidden(prefs, "btn_books"))
    }
}
