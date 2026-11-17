package com.themoon.y1.controllers.books

import android.content.SharedPreferences
import android.graphics.Typeface
import com.themoon.y1.Prefs
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.player.NowPlayingTheme
import com.themoon.y1.controllers.settings.SettingRow
import com.themoon.y1.controllers.settings.SettingRows

/**
 * The reader settings: one list of rows for Settings → Books and for Reader Settings in a book ([rows]), and the
 * values that the reader reads from them ([style]).
 */
object ReaderSettings {

    val FONT_SIZES = intArrayOf(16, 18, 20, 22, 24, 28)
    val LINE_SPACINGS = floatArrayOf(1.0f, 1.15f, 1.3f)
    val WHEEL_CLICKS = intArrayOf(1, 2, 3)

    const val COLORS_SYSTEM = 0
    const val COLORS_LIGHT = 1
    const val COLORS_DARK = 2

    const val WHEEL_PAGES = 0
    const val WHEEL_LINES = 1

    private val COLOR_LABELS = arrayOf("Follow System", "Light", "Dark")
    private val SPACING_LABELS = arrayOf("1.0", "1.15", "1.3")
    private val FONT_LABELS = arrayOf("Theme", "Serif", "Sans")
    private val WHEEL_LABELS = arrayOf("Pages", "Lines")

    /** The rows, in the order of both lists. Wheel Clicks per Page shows only while the wheel turns pages. */
    fun rows(r: SettingRows, prefs: SharedPreferences): List<SettingRow> = listOf(
        r.cycle("Page Colours", Prefs.READER_COLORS, COLOR_LABELS),
        r.cycle("Font Size", { index(Prefs.READER_FONT_SIZE.get(prefs), FONT_SIZES.size, 2) }, { Prefs.READER_FONT_SIZE.set(prefs, it) },
            Array(FONT_SIZES.size) { FONT_SIZES[it].toString() }),
        r.cycle("Line Spacing", { index(Prefs.READER_LINE_SPACING.get(prefs), LINE_SPACINGS.size, 1) }, { Prefs.READER_LINE_SPACING.set(prefs, it) },
            SPACING_LABELS),
        r.cycle("Font", Prefs.READER_FONT, FONT_LABELS),
        r.cycle("Wheel", Prefs.READER_WHEEL_MODE, WHEEL_LABELS),
        r.cycle("Wheel Clicks per Page", { index(Prefs.READER_WHEEL_CLICKS.get(prefs), WHEEL_CLICKS.size, 1) },
            { Prefs.READER_WHEEL_CLICKS.set(prefs, it) }, Array(WHEEL_CLICKS.size) { WHEEL_CLICKS[it].toString() })
            .let { row -> SettingRow(row.label, { Prefs.READER_WHEEL_MODE.get(prefs) == WHEEL_PAGES }, row.make) },
        r.toggle("Keep Screen On", Prefs.READER_KEEP_SCREEN_ON),
    )

    private fun index(value: Int, size: Int, default: Int): Int = if (value in 0 until size) value else default

    fun fontSizePx(prefs: SharedPreferences, density: Float): Float =
        FONT_SIZES[index(Prefs.READER_FONT_SIZE.get(prefs), FONT_SIZES.size, 2)] * density

    fun lineSpacing(prefs: SharedPreferences): Float = LINE_SPACINGS[index(Prefs.READER_LINE_SPACING.get(prefs), LINE_SPACINGS.size, 1)]

    fun typeface(prefs: SharedPreferences): Typeface = when (Prefs.READER_FONT.get(prefs)) {
        1 -> Typeface.SERIF
        2 -> Typeface.SANS_SERIF
        else -> ThemeManager.getCustomFont() ?: Typeface.DEFAULT
    }

    fun wheelLines(prefs: SharedPreferences): Boolean = Prefs.READER_WHEEL_MODE.get(prefs) == WHEEL_LINES

    fun wheelClicks(prefs: SharedPreferences): Int = WHEEL_CLICKS[index(Prefs.READER_WHEEL_CLICKS.get(prefs), WHEEL_CLICKS.size, 1)]

    fun keepScreenOn(prefs: SharedPreferences): Boolean = Prefs.READER_KEEP_SCREEN_ON.get(prefs)

    /** True for a dark page with the setting and the theme of now. */
    fun isDarkPage(prefs: SharedPreferences): Boolean = ReaderColors.isDark(
        Prefs.READER_COLORS.get(prefs), ThemeManager.isClassicTheme(), ThemeManager.isClassicLightTheme(),
        ThemeManager.getOverlayBackgroundColor()
    )
}

/**
 * The page colours. "Follow System" follows the Classy theme (Android 4.4 has no dark mode): Classic Light gives a
 * light page, Classic Dark a dark one, and another theme a dark page when its background is dark.
 */
object ReaderColors {
    const val LIGHT_BG = 0xFFFFFFFF.toInt()
    const val LIGHT_TEXT = 0xFF1C1C1E.toInt()
    const val LIGHT_DIM = 0xFF8E8E93.toInt()
    /** Not pure white on black: less glare in a dark room. */
    const val DARK_BG = 0xFF000000.toInt()
    const val DARK_TEXT = 0xFFD8D8D8.toInt()
    const val DARK_DIM = 0xFF7C7C80.toInt()

    fun isDark(mode: Int, classic: Boolean, classicLight: Boolean, themeBackground: Int): Boolean = when (mode) {
        ReaderSettings.COLORS_LIGHT -> false
        ReaderSettings.COLORS_DARK -> true
        else -> if (classic) !classicLight else NowPlayingTheme.isDark(themeBackground)
    }
}
