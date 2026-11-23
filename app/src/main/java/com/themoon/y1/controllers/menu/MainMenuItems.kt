package com.themoon.y1.controllers.menu

import android.content.SharedPreferences
import com.themoon.y1.Prefs

/**
 * The main menu rows. Settings → Menus → Main Menu Items lists them, changes their order ([MenuOrder]) and hides them
 * (`hide_btn_<id>` in AppPrefs). The saved order and the hide keys use the ids: do not change them.
 */
object MainMenuItems {

    /** [label] is the English text (localized when shown); [action] goes to MainActivity.handleMainMenuAction. */
    class Item(val id: String, val label: String, val action: String)

    const val PHOTOS = "btn_photos"

    /** In the default order. */
    val ITEMS = listOf(
        Item("btn_now", "Now Playing", "OPEN_PLAYER"),
        Item("btn_music", "Music", "OPEN_BROWSER"),
        Item("btn_videos", "Videos", "OPEN_VIDEOS"),
        Item(PHOTOS, "Photos", "OPEN_PHOTOS"),
        Item("btn_musicquiz", "Music Quiz", "OPEN_MUSIC_QUIZ"),
        Item("btn_games", "Games", "OPEN_GAMES"),
        Item("btn_podcasts", "Podcasts", "OPEN_PODCASTS"),
        Item("btn_audiobooks", "Audiobooks", "OPEN_AUDIOBOOKS"),
        Item("btn_books", "Books", "OPEN_BOOKS"),
        Item("btn_radio", "FM Radio", "OPEN_RADIO"),
        Item("btn_bt", "Bluetooth", "OPEN_BLUETOOTH"),
        Item("btn_wifi", "Wi-Fi", "OPEN_WIFI"),
        Item("btn_play_on", "Play On", "OPEN_PLAY_ON"),
        Item("btn_set", "Settings", "OPEN_SETTINGS"),
        Item("btn_web", "Web Server", "OPEN_WEBSERVER")
    )

    /** All items in the saved order. */
    fun ordered(prefs: SharedPreferences): List<Item> = ordered(MenuOrder.load(prefs))

    internal fun ordered(order: String): List<Item> =
        ITEMS.toMutableList().also { MenuOrder.sort(it, order, { item -> item.id }, ITEMS::indexOf) }

    fun isHidden(prefs: SharedPreferences, id: String): Boolean = prefs.getBoolean(Prefs.MAIN_MENU_HIDDEN.key(id), false)

    /** The rows of the main menu: the items that are not hidden, in the saved order. */
    fun shown(prefs: SharedPreferences): List<Item> = ordered(prefs).filterNot { isHidden(prefs, it.id) }
}
