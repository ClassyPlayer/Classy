package com.themoon.y1.migrations

import com.themoon.y1.Prefs

/**
 * Migration 022: no main menu item is hidden by default now. Older builds hid Games, Books and Photos when their folder
 * was not there (the removed migrations 012, 015 and 016). The saved value does not tell a hide of a migration from a
 * hide of the user: the migration removes a saved hide of these items once, so they show again. Settings → Menus →
 * Main Menu Items can hide them again. A saved "not hidden" stays. A new install has no hide keys: nothing changes.
 */
class Migration022ShowMenuItems : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Show Games, Books and Photos again in the main menu"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val prefs = context.settingsPrefs ?: return
        val saved = prefs.all
        val hidden = KEYS.filter { saved[it] == true }
        if (hidden.isEmpty()) return
        val editor = prefs.edit()
        hidden.forEach { editor.remove(it) }
        check(editor.commit()) { "Cannot remove the hide keys $hidden" }
    }

    companion object {
        const val VERSION: Int = 22
        const val ID: String = "022_show_menu_items"

        /** The main menu items that the removed migrations 012, 015 and 016 hid without a folder. */
        val ITEMS: List<String> = listOf("btn_games", "btn_books", "btn_photos")

        /** Their hide keys. */
        val KEYS: List<String> = ITEMS.map { Prefs.MAIN_MENU_HIDDEN.key(it) }
    }
}
