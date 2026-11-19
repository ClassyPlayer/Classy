package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration011BootScreenTest {

    @Test
    fun testVersionAndId() {
        val migration = Migration011BootScreen()
        assertEquals(11, migration.version)
        assertEquals("011_boot_screen", migration.id)
        assertEquals("Install Classy boot screen and update boot strings", migration.description)
    }

    @Test
    fun bootScreenOnlyOnTheY2WithAndroid44() {
        assertTrue(BootScreenMigrator.isSupported(19, "Y2"))
        // Y1: Android 4.2, model Y1
        assertFalse(BootScreenMigrator.isSupported(17, "Y1"))
        assertFalse(BootScreenMigrator.isSupported(17, "Y2"))
        assertFalse(BootScreenMigrator.isSupported(19, "Y1"))
        assertFalse(BootScreenMigrator.isSupported(19, null))
    }
}
