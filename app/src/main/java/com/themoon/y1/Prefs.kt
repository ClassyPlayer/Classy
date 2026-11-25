package com.themoon.y1

import android.content.SharedPreferences
import com.themoon.y1.controllers.player.AlbumTiltSetting
import com.themoon.y1.controllers.player.LyricsKeepScreenOn
import com.themoon.y1.controllers.player.LyricsScript
import com.themoon.y1.controllers.player.NowPlayingSpectrum
import com.themoon.y1.controllers.player.NowPlayingTime
import com.themoon.y1.controllers.settings.StatusBarSettings
import com.themoon.y1.managers.AutoThemeManager
import com.themoon.y1.managers.BacklightTimer
import com.themoon.y1.managers.ButtonVibration
import com.themoon.y1.managers.ClickVolume
import com.themoon.y1.managers.UpdateChannel
import com.themoon.y1.managers.UsbDrive
import com.themoon.y1.managers.VolumeSteps
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.AlbumYear
import com.themoon.y1.navigation.LetterJump
import com.themoon.y1.views.CoverPane
import com.themoon.y1.views.ListEdges
import com.themoon.y1.views.ToggleStyle

/**
 * The group of a key. The setting groups have the titles of the Settings groups ([isSetting] = true);
 * the other groups are data and state that the app keeps in the same file.
 */
enum class PrefGroup(val isSetting: Boolean) {
    PLAYBACK(true), DISPLAY(true), NOW_PLAYING(true), STATUS_BAR(true), APPEARANCE(true), MENUS(true),
    CONTROLS(true), CONNECTIVITY(true), DATE_TIME(true), GENERAL(true),

    /** Playback state that the app saves while it plays. */
    STATE(false),
    /** Per-song data (ratings, positions, cached tags). */
    DATA(false),
    /** Install and one-time state. */
    INSTALL(false),
    /** Login data. */
    ACCOUNT(false),
    /** Keys that no code reads now. Reset still removes them. */
    LEGACY(false),
}

/**
 * One key of [AppPrefs] with its rules.
 * - [resettable]: Settings → Reset All Settings removes the key.
 * - [inBackup]: Export Settings writes the key (and Import Settings sets it).
 */
open class PrefKey(
    val key: String,
    val group: PrefGroup,
    val resettable: Boolean = false,
    val inBackup: Boolean = true,
) {
    fun remove(prefs: SharedPreferences): Boolean = prefs.edit().remove(key).commit()
    override fun toString(): String = key
}

/** A key with a type and a default value. [set] writes at once (commit), [setAsync] in the background (apply). */
abstract class Pref<T>(
    key: String, val default: T, group: PrefGroup, resettable: Boolean = false, inBackup: Boolean = true,
) : PrefKey(key, group, resettable, inBackup) {
    abstract fun get(prefs: SharedPreferences): T
    abstract fun put(editor: SharedPreferences.Editor, value: T): SharedPreferences.Editor
    fun set(prefs: SharedPreferences, value: T): Boolean = put(prefs.edit(), value).commit()
    fun setAsync(prefs: SharedPreferences, value: T) = put(prefs.edit(), value).apply()
}

class BoolPref(key: String, default: Boolean, group: PrefGroup, resettable: Boolean = false) :
    Pref<Boolean>(key, default, group, resettable) {
    override fun get(prefs: SharedPreferences) = prefs.getBoolean(key, default)
    override fun put(editor: SharedPreferences.Editor, value: Boolean): SharedPreferences.Editor = editor.putBoolean(key, value)
    /** Writes the opposite value and returns it. */
    fun toggle(prefs: SharedPreferences): Boolean = (!get(prefs)).also { set(prefs, it) }
}

class IntPref(key: String, default: Int, group: PrefGroup, resettable: Boolean = false) :
    Pref<Int>(key, default, group, resettable) {
    override fun get(prefs: SharedPreferences) = prefs.getInt(key, default)
    override fun put(editor: SharedPreferences.Editor, value: Int): SharedPreferences.Editor = editor.putInt(key, value)
}

class StringPref(key: String, default: String, group: PrefGroup, resettable: Boolean = false, inBackup: Boolean = true) :
    Pref<String>(key, default, group, resettable, inBackup) {
    override fun get(prefs: SharedPreferences): String = prefs.getString(key, default) ?: default
    override fun put(editor: SharedPreferences.Editor, value: String): SharedPreferences.Editor = editor.putString(key, value)
}

/** Keys that start with [prefix] and end with an id (a file path, a menu item). */
class PrefFamily(
    val prefix: String,
    val group: PrefGroup,
    val inBackup: Boolean = true,
) {
    fun key(id: String): String = prefix + id
    fun matches(key: String): Boolean = key.startsWith(prefix)
    override fun toString(): String = "$prefix*"
}

/**
 * The keys of the app preference file [AppPrefs.NAME], each defined once. Reset All Settings and the settings
 * backup read their key lists from here. Keep the key strings: the devices have them.
 * A feature object that already has the key as a constant (for example [ClockSettings]) keeps it; the entry here uses it.
 */
object Prefs {
    private val keys = mutableListOf<PrefKey>()
    private val families = mutableListOf<PrefFamily>()
    private fun <T : PrefKey> add(k: T): T = k.also { keys += it }
    private fun add(f: PrefFamily): PrefFamily = f.also { families += it }

    // --- Playback (and Equalizer & Audio Effects) ---
    val SHUFFLE = add(BoolPref("shuffle", false, PrefGroup.PLAYBACK))
    /** 0 = Off, 1 = One, 2 = All. */
    val REPEAT_MODE = add(IntPref("repeat_mode", 0, PrefGroup.PLAYBACK, resettable = true))
    /** "preset_N" (N = index in the preset list) or "custom_<name>". */
    val EQ_PROFILE = add(StringPref("eq_profile_id", "preset_0", PrefGroup.PLAYBACK, resettable = true))
    val SOFTWARE_EQ = add(BoolPref("software_eq_enabled", false, PrefGroup.PLAYBACK))
    val BASS_BOOST = add(IntPref("bass_boost_step", 0, PrefGroup.PLAYBACK))
    val VIRTUALIZER = add(IntPref("virtualizer_step", 0, PrefGroup.PLAYBACK))
    val CROSSFEED = add(IntPref("crossfeed_step", 0, PrefGroup.PLAYBACK))
    /** Names of the custom EQ profiles, comma-separated. */
    val CUSTOM_EQ_LIST = add(StringPref("custom_eq_list", "", PrefGroup.PLAYBACK))
    /** Band levels of the custom EQ profiles: "eq_custom_<name>_band_<n>" ([com.themoon.y1.managers.AudioEffectPlan.bandLevelKey]). */
    val EQ_BANDS = add(PrefFamily("eq_custom_", PrefGroup.PLAYBACK))
    val SOUND_CHECK = add(BoolPref("sound_check_enabled", false, PrefGroup.PLAYBACK, resettable = true))
    /** Both channels play the mix of left and right ([com.themoon.y1.managers.MonoAudioProcessor]). */
    val MONO_AUDIO = add(BoolPref("mono_audio", false, PrefGroup.PLAYBACK, resettable = true))
    /** A volume step (1..30); -1 = no limit. */
    val VOLUME_LIMIT = add(IntPref(VolumeSteps.PREF_LIMIT, -1, PrefGroup.PLAYBACK, resettable = true))
    /** An old EQ key. No code reads it; Reset removes it. */
    val EQ_PRESET_OLD = add(PrefKey("eq_preset", PrefGroup.LEGACY, resettable = true))

    // --- Display ---
    val BACKLIGHT_TIMER = add(IntPref(BacklightTimer.PREF_INDEX, BacklightTimer.DEFAULT_INDEX, PrefGroup.DISPLAY, resettable = true))

    // --- Photos (the slideshow: Settings → Photos, and Photos → Slideshow Settings) ---
    /** Index in [com.themoon.y1.controllers.photos.PhotoSettings.SLIDE_SECONDS] (the last = Manual). */
    val PHOTOS_SLIDE_TIME = add(IntPref("photos_slide_time", 1, PrefGroup.DISPLAY, resettable = true))
    val PHOTOS_REPEAT = add(BoolPref("photos_repeat", false, PrefGroup.DISPLAY, resettable = true))
    val PHOTOS_SHUFFLE = add(BoolPref("photos_shuffle", false, PrefGroup.DISPLAY, resettable = true))
    /** 0 = Off, 1 = Dissolve, 2 = Push, 3 = Random. */
    val PHOTOS_TRANSITION = add(IntPref("photos_transition", 1, PrefGroup.DISPLAY, resettable = true))

    // --- Books (the reader: Settings → Books, and Reader Settings in a book) ---
    /** Page Colours: 0 = Follow System (the Classy theme), 1 = Light, 2 = Dark ([com.themoon.y1.controllers.books.ReaderSettings]). */
    val READER_COLORS = add(IntPref("reader_colors", 0, PrefGroup.DISPLAY, resettable = true))
    /** Index in ReaderSettings.FONT_SIZES. */
    val READER_FONT_SIZE = add(IntPref("reader_font_size", 2, PrefGroup.DISPLAY, resettable = true))
    /** Index in ReaderSettings.LINE_SPACINGS. */
    val READER_LINE_SPACING = add(IntPref("reader_line_spacing", 1, PrefGroup.DISPLAY, resettable = true))
    /** 0 = the theme font, 1 = Serif, 2 = Sans. */
    val READER_FONT = add(IntPref("reader_font", 0, PrefGroup.DISPLAY, resettable = true))
    /** 0 = the wheel turns pages, 1 = it moves the text a line. */
    val READER_WHEEL_MODE = add(IntPref("reader_wheel_mode", 0, PrefGroup.DISPLAY, resettable = true))
    /** Index in ReaderSettings.WHEEL_CLICKS: the wheel clicks of one page turn. */
    val READER_WHEEL_CLICKS = add(IntPref("reader_wheel_clicks", 1, PrefGroup.DISPLAY, resettable = true))
    val READER_KEEP_SCREEN_ON = add(BoolPref("reader_keep_screen_on", true, PrefGroup.DISPLAY, resettable = true))

    // --- Now Playing ---
    val SPECTRUM = add(BoolPref(NowPlayingSpectrum.PREF, false, PrefGroup.NOW_PLAYING, resettable = true))
    val TOTAL_TIME = add(BoolPref(NowPlayingTime.PREF_SHOW_TOTAL_TIME, false, PrefGroup.NOW_PLAYING, resettable = true))
    val LYRICS_SCRIPT = add(IntPref(LyricsScript.PREF, LyricsScript.ORIGINAL, PrefGroup.NOW_PLAYING, resettable = true))
    val LYRICS_KEEP_SCREEN_ON = add(BoolPref(LyricsKeepScreenOn.PREF, LyricsKeepScreenOn.DEFAULT, PrefGroup.NOW_PLAYING, resettable = true))
    val ALBUM_TILT = add(BoolPref(AlbumTiltSetting.KEY, true, PrefGroup.NOW_PLAYING, resettable = true))

    // --- Status Bar ---
    val TIME_IN_TITLE = add(BoolPref(ClockSettings.KEY_TIME_IN_TITLE, ClockSettings.DEFAULT_TIME_IN_TITLE, PrefGroup.STATUS_BAR, resettable = true))
    val SHOW_BATTERY = add(BoolPref(StatusBarSettings.KEY_SHOW_BATTERY, StatusBarSettings.DEFAULT_SHOW_BATTERY, PrefGroup.STATUS_BAR, resettable = true))
    val BATTERY_PERCENTAGE = add(BoolPref("show_battery_percentage", false, PrefGroup.STATUS_BAR, resettable = true))

    // --- Appearance ---
    /** The theme that the user selected; null before the first selection (then [CURRENT_THEME_NAME]). */
    val THEME_NAME = add(PrefKey("app_theme_name", PrefGroup.APPEARANCE))
    val CURRENT_THEME_NAME = add(StringPref("current_theme_name", "Classic Light", PrefGroup.APPEARANCE))
    /** Reset does not remove it: [AutoThemeManager.reset] turns Auto off and selects the default theme. */
    val AUTO_THEME = add(BoolPref(AutoThemeManager.PREF_KEY, false, PrefGroup.APPEARANCE))
    val TOGGLE_STYLE = add(StringPref(ToggleStyle.PREF_KEY, ToggleStyle.TEXT, PrefGroup.APPEARANCE, resettable = true))
    val LIST_EDGES_FADE = add(BoolPref(ListEdges.PREF_KEY, false, PrefGroup.APPEARANCE, resettable = true))
    val MENU_COVER_MOTION = add(BoolPref(CoverPane.PREF, CoverPane.DEFAULT_ON, PrefGroup.APPEARANCE, resettable = true))
    val VIDEO_FILL = add(BoolPref("video_stretch_fill", false, PrefGroup.APPEARANCE, resettable = true))

    // --- Menus ---
    /** Main Menu Items: the item ids in the saved order ([com.themoon.y1.controllers.menu.MenuItems]); "" = the default order. */
    val MENU_ORDER = add(StringPref("custom_menu_order", "", PrefGroup.MENUS))
    /** Main Menu Items: "hide_btn_<button id>" = true hides the item. */
    val MAIN_MENU_HIDDEN = add(PrefFamily("hide_btn_", PrefGroup.MENUS))
    /** Music Menu Items: the item ids in the saved order; "" = the default order. */
    val MUSIC_MENU_ORDER = add(StringPref("music_menu_order", "", PrefGroup.MENUS))
    /** Music Menu Items: "hide_music_btn_<item id>" = true hides the item. */
    val MUSIC_MENU_HIDDEN = add(PrefFamily("hide_music_btn_", PrefGroup.MENUS))
    /** Settings → Library → No Duplicates in Playlists. */
    val PLAYLIST_NO_DUPLICATES = add(BoolPref("playlist_no_duplicates", false, PrefGroup.GENERAL, resettable = true))
    val GROUP_ALBUMS_BY_ARTIST = add(BoolPref(AlbumKey.PREF_BY_ARTIST, false, PrefGroup.MENUS, resettable = true))
    val ALBUMS_BY_YEAR = add(BoolPref(AlbumYear.PREF, false, PrefGroup.MENUS, resettable = true))

    // --- Controls ---
    val BUTTON_SOUND = add(BoolPref("sound", true, PrefGroup.CONTROLS))
    val CLICK_VOLUME = add(IntPref(ClickVolume.PREF, ClickVolume.DEFAULT, PrefGroup.CONTROLS, resettable = true))
    val VIBRATE = add(BoolPref("vibrate", true, PrefGroup.CONTROLS))
    val VIBRATE_STRENGTH = add(IntPref(ButtonVibration.PREF, ButtonVibration.DEFAULT, PrefGroup.CONTROLS))
    val LOOP_SCROLL = add(BoolPref("loop_scroll_on", true, PrefGroup.CONTROLS, resettable = true))
    /** List Glide: a wheel step glides the highlight and the rows ([com.themoon.y1.views.ListGlide]). */
    val LIST_GLIDE = add(BoolPref("list_glide", true, PrefGroup.CONTROLS, resettable = true))
    /** The iPod letter jump in the A–Z lists and Cover Flow ([LetterJump]). */
    val LETTER_JUMP = add(BoolPref("letter_jump", true, PrefGroup.CONTROLS, resettable = true))
    /** Letter Jump Sensitivity: the [LetterJump.Sensitivity] ordinal (0 = Low, 1 = Normal, 2 = High). */
    val LETTER_JUMP_SENSITIVITY = add(IntPref("letter_jump_sensitivity", LetterJump.Sensitivity.DEFAULT.ordinal, PrefGroup.CONTROLS, resettable = true))
    /** Wheel Mode on the Y2 ([com.themoon.y1.navigation.PadWheel.MODE_LABELS]; 0 = Classic, the wheel's step keys). */
    val WHEEL_MODE = add(IntPref("wheel_mode", com.themoon.y1.navigation.PadWheel.DEFAULT_MODE, PrefGroup.CONTROLS, resettable = true))
    val SCREEN_OFF_CONTROL = add(BoolPref("screen_off_control", false, PrefGroup.CONTROLS, resettable = true))
    val DOUBLE_CLICK_FAVORITE = add(BoolPref("double_click_favorite", true, PrefGroup.CONTROLS, resettable = true))
    val BACK_HOLD_NOW_PLAYING = add(BoolPref("back_hold_now_playing", true, PrefGroup.CONTROLS, resettable = true))

    // --- Connectivity ---
    /** USB Drive: Ask, Always or Never ([UsbDrive]). */
    val USB_DRIVE = add(IntPref(UsbDrive.PREF, UsbDrive.ASK, PrefGroup.CONNECTIVITY))

    // --- Date & Time ---
    val CLOCK_24_HOUR = add(BoolPref(ClockSettings.KEY_24_HOUR_FORMAT, ClockSettings.DEFAULT_24_HOUR_FORMAT, PrefGroup.DATE_TIME, resettable = true))

    // --- General ---
    /** The language file name. */
    val LANGUAGE = add(StringPref("app_language", "English (Default)", PrefGroup.GENERAL))

    // --- State and data (not settings) ---
    /** last_played_song_path, _song_index, _position, _duration, _is_audiobook, _audiobook_path, _video_path. */
    val LAST_PLAYED = add(PrefFamily("last_played_", PrefGroup.STATE, inBackup = false))
    val HAS_SAVED_PLAYBACK_STATE = add(PrefKey("has_saved_playback_state", PrefGroup.STATE, inBackup = false))
    /** The photo viewer said once how to start a slideshow. */
    val PHOTOS_HINT_SHOWN = add(BoolPref("photos_hint_shown", false, PrefGroup.STATE))
    val BOOK_POSITION = add(PrefFamily("book_pos_", PrefGroup.DATA))
    val BOOK_DURATION = add(PrefFamily("book_dur_", PrefGroup.DATA))
    /** The reading place of an ebook: "ebook_pos_<path>" = "section:offset:permille" ([com.themoon.y1.books.SavedPosition]). */
    val EBOOK_POSITION = add(PrefFamily("ebook_pos_", PrefGroup.DATA))
    /** The bookmarks of an ebook: "ebook_marks_<path>" = a JSON list ([com.themoon.y1.books.Bookmark]). */
    val EBOOK_MARKS = add(PrefFamily("ebook_marks_", PrefGroup.DATA))
    /** The ebook that Books → Continue Reading opens. */
    val LAST_BOOK = add(StringPref("last_read_book_path", "", PrefGroup.STATE, inBackup = false))
    val VIDEO_POSITION = add(PrefFamily("video_pos_", PrefGroup.DATA))
    val RATING = add(PrefFamily("rating_", PrefGroup.DATA))
    val META_TITLE = add(PrefFamily("meta_title_", PrefGroup.DATA))
    val META_ARTIST = add(PrefFamily("meta_artist_", PrefGroup.DATA))
    /** A cache of cover file paths: not in the backup. */
    val ALBUM_ART = add(PrefFamily("album_art_", PrefGroup.DATA, inBackup = false))
    val LASTFM_USERNAME = add(PrefKey("lastfm_username", PrefGroup.ACCOUNT, inBackup = false))
    val LASTFM_PASSWORD = add(PrefKey("lastfm_password", PrefGroup.ACCOUNT, inBackup = false))
    val LAST_LANG_VERSION = add(PrefKey("last_lang_version", PrefGroup.INSTALL, inBackup = false))
    /**
     * Update Channel chosen in Settings ([UpdateChannel]); it holds only for the builds in [UPDATE_CHANNEL_BUILDS].
     * Not in the backup: a backup of another player must not move this one to another channel.
     */
    val UPDATE_CHANNEL = add(StringPref(UpdateChannel.PREF, "", PrefGroup.INSTALL, inBackup = false))
    /** The version names, comma-separated, that [UPDATE_CHANNEL] holds for. */
    val UPDATE_CHANNEL_BUILDS = add(StringPref(UpdateChannel.PREF_BUILDS, "", PrefGroup.INSTALL, inBackup = false))
    /** An old key. No code reads it. */
    val REBOOT_TO_THEME = add(PrefKey("reboot_to_theme", PrefGroup.LEGACY, inBackup = false))
    /** Old theme keys (before the two themes were in the code). No code reads them. */
    val THEME_INDEX = add(PrefKey("app_theme_index", PrefGroup.LEGACY))
    val LAST_THEME_VERSION = add(PrefKey("last_installed_theme_version", PrefGroup.LEGACY, inBackup = false))

    /** All keys, in the order above. */
    val ALL: List<PrefKey> get() = keys
    val FAMILIES: List<PrefFamily> get() = families

    /** The keys that Reset All Settings removes. */
    val RESETTABLE: List<PrefKey> get() = keys.filter { it.resettable }

    /** The keys of settings (not state or data). */
    val SETTINGS: List<PrefKey> get() = keys.filter { it.group.isSetting }

    private val byKey: Map<String, PrefKey> by lazy { keys.associateBy { it.key } }

    fun find(key: String): PrefKey? = byKey[key]
    fun familyOf(key: String): PrefFamily? = families.firstOrNull { it.matches(key) }

    fun isSetting(key: String): Boolean = find(key)?.group?.isSetting == true

    /** Keys that the settings backup does not have. */
    fun isOutOfBackup(key: String): Boolean = find(key)?.inBackup == false || familyOf(key)?.inBackup == false

    /** Removes the [RESETTABLE] keys in one commit. */
    fun resetAll(prefs: SharedPreferences): Boolean {
        val editor = prefs.edit()
        for (k in RESETTABLE) editor.remove(k.key)
        return editor.commit()
    }
}
