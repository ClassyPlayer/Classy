package com.themoon.y1

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

/**
 * The two themes of the app: Classic Light and Classic Dark (Settings → Theme; Auto selects one of them, see
 * [com.themoon.y1.managers.AutoThemeManager]). Both use the NimbusSans fonts in assets/fonts and the same main menu
 * ([menuElements]). Only the colours of [ThemeData] are different.
 */
object ThemeManager {

    /** One element of the main menu (MainMenuController): the list box, a row of the list or a pane widget. */
    class MenuElement(
        val id: String,
        val type: String,
        val parentId: String = "",
        val visibleOnFocus: String = "",
        val x: Int = 0,
        val y: Int = 0,
        val width: Int = 200,
        val height: Int = 50,
        val textNormal: String = "",
        val textFocused: String = "",
        val textRight: String = "",
        val textRightColor: String = "",
        val textRightFocusedColor: String = "",
        val action: String = "NONE",
        val gravity: String = "top|left",
        val radius: Int = -1,
        val focusIndex: Int = -1,
        val textSize: Int = -1,
        val textSecondarySize: Int = -1,
        val textPosition: String = "bottom",
        val textAlign: String = "center",
        val bgColor: String = "",
        val padding: Int = 0,
        val focusOffsetX: Int = 0,
        val focusOffsetY: Int = 0,
        val focusScale: Float = 1.0f
    )

    class ThemeData(
        val name: String,
        val textPrimary: Int,
        val textSecondary: Int,
        val bgOverlay: Int,
        val statusBarBg: Int
    )

    const val LIGHT_NAME = "Classic Light"
    const val DARK_NAME = "Classic Dark"

    val CLASSIC_LIGHT = ThemeData(LIGHT_NAME, 0xFF1C1C1E.toInt(), 0xFF666666.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8C9C8.toInt())
    val CLASSIC_DARK = ThemeData(DARK_NAME, 0xFFF2F2F7.toInt(), 0xFF98989D.toInt(), 0xFF000000.toInt(), 0xFF1C1C1E.toInt())

    /** The themes in the order of the Settings → Theme list. */
    val themes: List<ThemeData> = listOf(CLASSIC_LIGHT, CLASSIC_DARK)

    // The same in both themes
    private const val BUTTON_NORMAL_BG = 0x00FFFFFF
    private const val BUTTON_FOCUSED_BG = 0xFF2588DC.toInt()
    private const val BUTTON_FOCUSED_TEXT = Color.WHITE

    private fun row(id: String, label: String, action: String, index: Int) = MenuElement(
        id, "button", parentId = LIST_BOX, width = -1, height = 33, textNormal = label, textRight = "›",
        action = action, focusIndex = index, textSize = 21, textAlign = "left"
    )

    private const val LIST_BOX = "main_scroll_list"

    /**
     * The main menu: the list box, its rows in the default order (Settings → Main Menu Items can change the order and
     * hide rows; they use the ids) and the art pane. ClassyLists.menuElements gives them the Classy metrics.
     */
    val menuElements: List<MenuElement> = listOf(
        MenuElement(LIST_BOX, "list_box", width = 242, height = 325, focusIndex = 1),
        row("btn_now", "Now Playing", "OPEN_PLAYER", 0),
        row("btn_music", "Music", "OPEN_BROWSER", 1),
        row("btn_videos", "Videos", "OPEN_VIDEOS", 2),
        row("btn_photos", "Photos", "OPEN_PHOTOS", 3),
        row("btn_musicquiz", "Music Quiz", "OPEN_MUSIC_QUIZ", 4),
        row("btn_games", "Games", "OPEN_GAMES", 5),
        row("btn_podcasts", "Podcasts", "OPEN_PODCASTS", 6),
        row("btn_audiobooks", "Audiobooks", "OPEN_AUDIOBOOKS", 7),
        row("btn_books", "Books", "OPEN_BOOKS", 8),
        row("btn_radio", "FM Radio", "OPEN_RADIO", 9),
        row("btn_bt", "Bluetooth", "OPEN_BLUETOOTH", 10),
        row("btn_wifi", "Wi-Fi", "OPEN_WIFI", 11),
        row("btn_play_on", "Play On", "OPEN_PLAY_ON", 12),
        row("btn_set", "Settings", "OPEN_SETTINGS", 13),
        row("btn_web", "Web Server", "OPEN_WEBSERVER", 14),
        MenuElement("widget_cover", "widget_animated_cover", x = 242, width = 238, height = 325),
        // While Photos is selected: random photos in the art pane (PhotoPreviewView)
        MenuElement("widget_photos", "widget_photo_preview", visibleOnFocus = "btn_photos", x = 242, width = 238, height = 325)
    )

    @Volatile
    private var current: ThemeData = CLASSIC_LIGHT

    /** Goes up by one at each theme change (see [selectTheme]). */
    @Volatile
    var themeGeneration: Int = 0
        private set

    @Volatile
    private var font: Typeface? = null

    @Volatile
    private var fontBold: Typeface? = null

    private const val FONT = "fonts/NimbusSans-Regular.otf"
    private const val FONT_BOLD = "fonts/NimbusSans-Bold.otf"

    /** Loads the fonts (one time) and selects the saved theme, without a save. App start, before the views are made. */
    fun init(context: Context, prefs: SharedPreferences) {
        if (font == null) font = loadFont(context, FONT)
        if (fontBold == null) fontBold = loadFont(context, FONT_BOLD)
        applySaved(prefs)
    }

    /** Makes the saved theme the current theme. A name that is not a theme changes nothing. */
    internal fun applySaved(prefs: SharedPreferences) {
        find(savedName(prefs))?.let { current = it }
    }

    private fun loadFont(context: Context, path: String): Typeface? = try {
        Typeface.createFromAsset(context.assets, path)
    } catch (_: Exception) {
        null
    }

    private fun savedName(prefs: SharedPreferences): String =
        prefs.getString(Prefs.THEME_NAME.key, null) ?: Prefs.CURRENT_THEME_NAME.get(prefs)

    /** The theme with this name, or null. */
    fun find(name: String?): ThemeData? = themes.firstOrNull { it.name == name }

    /**
     * Makes [name] the current theme and saves it, as a tap in Settings → Theme does.
     * False if there is no theme with this name or it is already the current theme.
     */
    fun selectTheme(prefs: SharedPreferences, name: String): Boolean {
        val theme = find(name) ?: return false
        val changed = theme !== current || theme.name != prefs.getString(Prefs.THEME_NAME.key, null)
        current = theme
        try {
            prefs.edit()
                .putString(Prefs.THEME_NAME.key, theme.name)
                .putString(Prefs.CURRENT_THEME_NAME.key, theme.name)
                .commit()
        } catch (_: Exception) {
        }
        if (changed) themeGeneration++
        return changed
    }

    fun getCurrentTheme(): ThemeData = current

    fun getStatusBarBackgroundColor(): Int = current.statusBarBg

    fun createStatusBarBackground(): GradientDrawable {
        if (isClassicLightTheme()) {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFF7F7F7.toInt(), 0xFFDADADA.toInt(), 0xFFB6B6B6.toInt())
            )
        }
        val base = getStatusBarBackgroundColor()
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[2] = Math.min(1f, hsv[2] * 1.15f)
        val lighter = (base and 0xFF000000.toInt()) or (Color.HSVToColor(hsv) and 0x00FFFFFF)
        Color.colorToHSV(base, hsv)
        hsv[2] = Math.max(0f, hsv[2] * 0.85f)
        val darker = (base and 0xFF000000.toInt()) or (Color.HSVToColor(hsv) and 0x00FFFFFF)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighter, base, darker))
    }

    fun getCustomFont(): Typeface = font ?: Typeface.DEFAULT

    fun getCustomFontBold(): Typeface = fontBold ?: Typeface.DEFAULT_BOLD

    fun isClassicLightTheme(): Boolean = current === CLASSIC_LIGHT

    fun getTextColorPrimary(): Int = current.textPrimary

    fun getTextColorSecondary(): Int = current.textSecondary

    fun getOverlayBackgroundColor(): Int = current.bgOverlay

    fun getListButtonNormalBg(): Int = BUTTON_NORMAL_BG

    fun getListButtonFocusedBg(): Int = BUTTON_FOCUSED_BG

    fun getListButtonFocusedTextColor(): Int = BUTTON_FOCUSED_TEXT
}
