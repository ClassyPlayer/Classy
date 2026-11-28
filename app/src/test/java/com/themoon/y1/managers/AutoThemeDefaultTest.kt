package com.themoon.y1.managers

import com.themoon.y1.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoThemeDefaultTest {

    @Test
    fun newPlayer_getsAutoAndKeepsItAfterAutoSavesItsTheme() {
        val prefs = FakePrefs()
        assertTrue(AutoThemeManager.isEnabled(prefs))
        AutoThemeManager.storeWhenMissing(prefs)
        // Auto saves the theme for the time now as the selected theme
        prefs.edit().putString("app_theme_name", "Classic Light").commit()
        assertTrue(AutoThemeManager.isEnabled(prefs))
    }

    @Test
    fun savedThemeWithoutAutoKey_staysAndIsStored() {
        // A theme chosen before Auto was the default, or an import without the Auto key
        val prefs = FakePrefs(mutableMapOf("app_theme_name" to "Classic Dark"))
        assertFalse(AutoThemeManager.isEnabled(prefs))
        AutoThemeManager.storeWhenMissing(prefs)
        assertEquals(false, prefs.all[AutoThemeManager.PREF_KEY])
    }

    @Test
    fun storedValue_wins() {
        val on = FakePrefs(mutableMapOf("app_theme_name" to "Classic Dark", AutoThemeManager.PREF_KEY to true))
        assertTrue(AutoThemeManager.isEnabled(on))
        val off = FakePrefs(mutableMapOf(AutoThemeManager.PREF_KEY to false))
        assertFalse(AutoThemeManager.isEnabled(off))
    }
}
