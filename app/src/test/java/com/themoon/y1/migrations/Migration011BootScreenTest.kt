package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Test

class Migration011BootScreenTest {

    @Test
    fun testVersionAndId() {
        val migration = Migration011BootScreen()
        assertEquals(11, migration.version)
        assertEquals("011_boot_screen", migration.id)
        assertEquals("Install Classy boot screen and update boot strings", migration.description)
    }
}
