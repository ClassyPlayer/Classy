package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration019Mp3YearsTest {

    @Test
    fun migrationAsksForOneMediaRescan() {
        val prefs = MoveMisplacedPrefsTest.FakePrefs()
        val manager = MigrationManager(null, prefs)
        manager.registerMigration(Migration019Mp3Years())
        assertFalse(manager.isPendingMediaRescan())

        assertEquals(1, manager.applyPendingMigrations())
        assertTrue(manager.isPendingMediaRescan())

        manager.clearPendingMediaRescan()
        assertEquals(0, manager.applyPendingMigrations())
        assertFalse(manager.isPendingMediaRescan())
    }
}
