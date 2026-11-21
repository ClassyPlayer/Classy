package com.themoon.y1

import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The two themes in the code, the theme selection and the main menu elements. */
class ThemeManagerTest {

    @Before
    fun setUp() {
        ThemeManager.selectTheme(FakePrefs(), ThemeManager.LIGHT_NAME)
    }

    @After
    fun tearDown() = setUp()

    @Test
    fun theListHasLightThenDark() {
        assertEquals(listOf("Classic Light", "Classic Dark"), ThemeManager.themes.map { it.name })
    }

    @Test
    fun theColoursAreTheOnesOfTheOldThemeFiles() {
        ThemeManager.selectTheme(FakePrefs(), "Classic Light")
        assertEquals(0xFF1C1C1E.toInt(), ThemeManager.getTextColorPrimary())
        assertEquals(0xFF666666.toInt(), ThemeManager.getTextColorSecondary())
        assertEquals(0xFFFFFFFF.toInt(), ThemeManager.getOverlayBackgroundColor())
        assertEquals(0xFFC8C9C8.toInt(), ThemeManager.getStatusBarBackgroundColor())
        assertTrue(ThemeManager.isClassicLightTheme())

        ThemeManager.selectTheme(FakePrefs(), "Classic Dark")
        assertEquals(0xFFF2F2F7.toInt(), ThemeManager.getTextColorPrimary())
        assertEquals(0xFF98989D.toInt(), ThemeManager.getTextColorSecondary())
        assertEquals(0xFF000000.toInt(), ThemeManager.getOverlayBackgroundColor())
        assertEquals(0xFF1C1C1E.toInt(), ThemeManager.getStatusBarBackgroundColor())
        assertFalse(ThemeManager.isClassicLightTheme())

        // The same in both themes
        assertEquals(0x00FFFFFF, ThemeManager.getListButtonNormalBg())
        assertEquals(0xFF2588DC.toInt(), ThemeManager.getListButtonFocusedBg())
        assertEquals(0xFFFFFFFF.toInt(), ThemeManager.getListButtonFocusedTextColor())
    }

    @Test
    fun selectThemeSavesTheName() {
        val prefs = FakePrefs()
        val generation = ThemeManager.themeGeneration

        assertTrue(ThemeManager.selectTheme(prefs, "Classic Dark"))

        assertSame(ThemeManager.CLASSIC_DARK, ThemeManager.getCurrentTheme())
        assertEquals("Classic Dark", prefs.getString("app_theme_name", null))
        assertEquals("Classic Dark", prefs.getString("current_theme_name", null))
        assertEquals(setOf("app_theme_name", "current_theme_name"), prefs.all.keys)
        assertEquals(generation + 1, ThemeManager.themeGeneration)
    }

    @Test
    fun selectThemeIsFalseForTheCurrentThemeAndForAnUnknownName() {
        val prefs = FakePrefs()
        assertTrue(ThemeManager.selectTheme(prefs, "Classic Dark"))
        val generation = ThemeManager.themeGeneration

        assertFalse(ThemeManager.selectTheme(prefs, "Classic Dark"))
        assertFalse(ThemeManager.selectTheme(prefs, "iPod Classic"))
        assertFalse(ThemeManager.selectTheme(prefs, ""))
        assertEquals("Classic Dark", ThemeManager.getCurrentTheme().name)
        assertEquals(generation, ThemeManager.themeGeneration)
    }

    @Test
    fun theSavedThemeIsTheCurrentThemeAfterAStart() {
        ThemeManager.applySaved(FakePrefs(mutableMapOf("app_theme_name" to "Classic Dark")))
        assertEquals("Classic Dark", ThemeManager.getCurrentTheme().name)

        // Without app_theme_name: current_theme_name, and its default is Classic Light
        ThemeManager.applySaved(FakePrefs(mutableMapOf()))
        assertEquals("Classic Light", ThemeManager.getCurrentTheme().name)
        ThemeManager.applySaved(FakePrefs(mutableMapOf("current_theme_name" to "Classic Dark")))
        assertEquals("Classic Dark", ThemeManager.getCurrentTheme().name)
    }

    @Test
    fun anOldThemeNameChangesNothing() {
        ThemeManager.applySaved(FakePrefs(mutableMapOf("app_theme_name" to "iPod Classic Dark")))
        assertEquals("Classic Light", ThemeManager.getCurrentTheme().name)
        assertNull(ThemeManager.find("iPod Classic"))
    }

    @Test
    fun theMainMenuHasTheRowsInTheDefaultOrder() {
        val rows = ThemeManager.menuElements.filter { it.type == "button" }
        assertEquals(
            listOf("btn_now", "btn_music", "btn_videos", "btn_photos", "btn_musicquiz", "btn_games", "btn_podcasts",
                "btn_audiobooks", "btn_books", "btn_radio", "btn_bt", "btn_wifi", "btn_play_on", "btn_set", "btn_web"),
            rows.sortedBy { it.focusIndex }.map { it.id }
        )
        assertEquals((0 until 15).toList(), rows.map { it.focusIndex })
        assertTrue(rows.all { it.parentId == "main_scroll_list" && it.action.startsWith("OPEN_") })
        assertEquals("OPEN_PHOTOS", rows.first { it.id == "btn_photos" }.action)
        assertEquals(
            listOf("list_box", "widget_animated_cover", "widget_photo_preview"),
            ThemeManager.menuElements.filter { it.type != "button" }.map { it.type }
        )
        assertEquals("btn_photos", ThemeManager.menuElements.first { it.type == "widget_photo_preview" }.visibleOnFocus)
    }
}
