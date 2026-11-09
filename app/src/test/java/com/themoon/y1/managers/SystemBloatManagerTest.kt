package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBloatManagerTest {

    @Test
    fun lists_keepTheModemPathAndAppAnimations() {
        assertFalse("com.android.phone" in SystemBloatManager.DISABLED)
        assertFalse("com.android.providers.telephony" in SystemBloatManager.DISABLED)
        assertFalse("ril-daemon" in SystemBloatManager.SERVICES)
        assertFalse("gsm0710muxd" in SystemBloatManager.SERVICES)
        assertFalse(SystemBloatManager.SETTINGS.any { it.name == "animator_duration_scale" })
    }

    @Test
    fun lists_keepTheSystemUiPackageForTheWallpaper() {
        // Without ImageWallpaper (in SystemUI), Android 4.4 enables input only after its 30 s boot timeout
        assertFalse("com.android.systemui" in SystemBloatManager.DISABLED)
        assertTrue("com.android.systemui" in SystemBloatManager.ENABLED)
        assertTrue("com.android.systemui/com.android.systemui.SystemUIService" in SystemBloatManager.DISABLED)
    }

    @Test
    fun applyCommand_enablesFirstAndOnlyHasTheGivenItems() {
        val command = SystemBloatManager.applyCommand(
            listOf("com.android.systemui"),
            listOf("com.android.systemui/com.android.systemui.SystemUIService"),
            listOf(SystemBloatManager.Setting("secure", "lockscreen.disabled", "1"))
        )
        assertEquals(
            "pm enable com.android.systemui; pm disable com.android.systemui/com.android.systemui.SystemUIService; " +
                "settings put secure lockscreen.disabled 1",
            command
        )
    }

    @Test
    fun stopCommand_stopsRunningAndRestartingServicesOnly() {
        val states = mapOf("matv" to "running", "agpsd" to "restarting", "netdiag" to "stopped")
        assertEquals("setprop ctl.stop matv; setprop ctl.stop agpsd", SystemBloatManager.stopCommand { states[it] })
    }

    @Test
    fun stopCommand_isNullWhenNothingRuns() {
        assertNull(SystemBloatManager.stopCommand { "stopped" })
        assertNull(SystemBloatManager.stopCommand { null })
    }

    @Test
    fun settingMatches_comparesNumbers() {
        assertTrue(SystemBloatManager.settingMatches("0", "0"))
        assertTrue(SystemBloatManager.settingMatches("0.0", "0"))
        assertTrue(SystemBloatManager.settingMatches("1", "1"))
        assertFalse(SystemBloatManager.settingMatches("1.0", "0"))
        assertFalse(SystemBloatManager.settingMatches(null, "0"))
        assertFalse(SystemBloatManager.settingMatches("", "0"))
    }
}
