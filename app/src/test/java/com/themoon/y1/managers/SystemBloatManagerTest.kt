package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBloatManagerTest {

    @Test
    fun lists_keepTheModemPathAndAppAnimations() {
        assertFalse("com.android.phone" in SystemBloatManager.PACKAGES)
        assertFalse("com.android.providers.telephony" in SystemBloatManager.PACKAGES)
        assertFalse("ril-daemon" in SystemBloatManager.SERVICES)
        assertFalse("gsm0710muxd" in SystemBloatManager.SERVICES)
        assertFalse(SystemBloatManager.SETTINGS.any { it.name == "animator_duration_scale" })
    }

    @Test
    fun applyCommand_onlyHasTheGivenItems() {
        val command = SystemBloatManager.applyCommand(
            listOf("com.android.systemui"),
            listOf(SystemBloatManager.Setting("secure", "lockscreen.disabled", "1"))
        )
        assertEquals("pm disable com.android.systemui; settings put secure lockscreen.disabled 1", command)
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
