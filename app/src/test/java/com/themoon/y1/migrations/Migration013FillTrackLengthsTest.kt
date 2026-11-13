package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration013FillTrackLengthsTest {

    @Test
    fun migrationAsksForOneLengthFill() {
        val prefs = MoveMisplacedPrefsTest.FakePrefs()
        val manager = MigrationManager(null, prefs)
        manager.registerMigration(Migration013FillTrackLengths())
        assertFalse(manager.isPendingLengthFill())

        assertEquals(1, manager.applyPendingMigrations())
        assertTrue(manager.isPendingLengthFill())

        // After the fill: it does not run again
        manager.clearPendingLengthFill()
        assertEquals(0, manager.applyPendingMigrations())
        assertFalse(manager.isPendingLengthFill())
    }
}
