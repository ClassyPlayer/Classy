package com.themoon.y1.migrations

import com.themoon.y1.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration021PreviousRestartsSongTest {

    private fun migrate(startingVersion: Int, settings: MutableMap<String, Any?>): MoveMisplacedPrefsTest.FakePrefs {
        val prefs = MoveMisplacedPrefsTest.FakePrefs(settings)
        Migration021PreviousRestartsSong().migrate(MigrationContext(null, null, prefs, startingVersion))
        return prefs
    }

    @Test
    fun aNewInstallKeepsTheRestart() {
        val prefs = migrate(0, mutableMapOf())
        assertFalse(prefs.contains(Prefs.PREVIOUS_RESTARTS.key))
        assertTrue(Prefs.PREVIOUS_RESTARTS.get(prefs))
    }

    @Test
    fun anUpdateKeepsPreviousAsPreviousSong() {
        assertFalse(Prefs.PREVIOUS_RESTARTS.get(migrate(19, mutableMapOf())))
        assertFalse(Prefs.PREVIOUS_RESTARTS.get(migrate(0, mutableMapOf(Prefs.HAS_SAVED_PLAYBACK_STATE.key to true))))
    }

    @Test
    fun keepsAValueTheUserSet() {
        val prefs = migrate(19, mutableMapOf(Prefs.PREVIOUS_RESTARTS.key to true))
        assertTrue(Prefs.PREVIOUS_RESTARTS.get(prefs))
    }

    @Test
    fun isRegistered() {
        val manager = MigrationManager(null, MoveMisplacedPrefsTest.FakePrefs())
        manager.registerMigration(Migration021PreviousRestartsSong())
        assertEquals(Migration021PreviousRestartsSong.ID, manager.getRegisteredMigrations().single().id)
    }
}
