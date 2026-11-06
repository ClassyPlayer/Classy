package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Migration007StripSystemBloatTest {

    private lateinit var mockPrefs: MigrationManagerTest.MockSharedPreferences

    @Before
    fun setUp() {
        mockPrefs = MigrationManagerTest.MockSharedPreferences()
    }

    @Test
    fun testVersionAndId() {
        val m7 = Migration007StripSystemBloat()
        assertEquals(7, m7.version)
        assertEquals("007_strip_system_bloat", m7.id)
    }

    @Test
    fun testSkipsExecutionOnNonY2Model() {
        var executorCalled = false
        val m7 = Migration007StripSystemBloat(
            model = "Y1",
            rootExecutor = {
                executorCalled = true
                true
            }
        )

        val context = MigrationContext(null, mockPrefs, mockPrefs, 0)
        m7.migrate(context)

        assertFalse(executorCalled)
    }

    @Test
    fun testExecutesStripDownOnY2Model() {
        var capturedCommands: List<String>? = null
        val m7 = Migration007StripSystemBloat(
            model = "Y2",
            rootExecutor = { commands ->
                capturedCommands = commands
                true
            }
        )

        val context = MigrationContext(null, mockPrefs, mockPrefs, 0)
        m7.migrate(context)

        val commands = capturedCommands
        assertTrue("Commands must be executed on Y2", commands != null && commands.isNotEmpty())
        assertTrue("Must disable SystemUI", commands!!.any { it.contains("pm disable com.android.systemui") })
        assertTrue("Must disable Phone", commands.any { it.contains("pm disable com.android.phone") })
        assertTrue("Must disable LatinIME", commands.any { it.contains("pm disable com.android.inputmethod.latin") })
        assertTrue("Must disable Keyguard", commands.any { it.contains("pm disable com.android.keyguard") })
        assertTrue("Must zero window animations", commands.any { it.contains("settings put global window_animation_scale 0") })
        assertTrue("Must strip MTK telemetry daemons", commands.any { it.contains("chmod 000") && it.contains("matv") && it.contains("mobile_log_d") })
        assertTrue("Must set ro.radio.noril=1 in build.prop", commands.any { it.contains("ro.radio.noril=1") })
    }

    @Test
    fun testMigrationManagerAppliesMigration007() {
        val manager = MigrationManager(null, mockPrefs)
        var executed = false
        val m7 = Migration007StripSystemBloat(
            model = "Y2",
            rootExecutor = {
                executed = true
                true
            }
        )
        manager.registerMigration(m7)

        val applied = manager.applyPendingMigrations()
        assertEquals(1, applied)
        assertTrue(executed)
        assertEquals(7, manager.currentVersion)
        assertTrue(manager.isMigrationApplied("007_strip_system_bloat"))
    }
}
