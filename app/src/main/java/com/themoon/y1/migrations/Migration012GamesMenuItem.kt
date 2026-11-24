package com.themoon.y1.migrations

import com.themoon.y1.StoragePaths

/**
 * Migration 012: the main menu has a new row *Games* (iPod Click Wheel games, after *Music Quiz*).
 * - A saved order (Settings → Menus → Main Menu Items) gets Games after Music Quiz once.
 * - The row shows only when a Games folder is there; else it starts hidden, and Main Menu Items can show it.
 */
class Migration012GamesMenuItem : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Put Games after Music Quiz in the main menu; hide it without a Games folder"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val prefs = context.settingsPrefs ?: return
        NewMenuItem.putAfter(prefs, ITEM, AFTER)
        NewMenuItem.hideWithoutFolder(prefs, ITEM) { StoragePaths.getGamesDirs() }
    }

    companion object {
        const val VERSION: Int = 12
        const val ID: String = "012_games_menu_item"
        const val ITEM = "btn_games"
        const val AFTER = "btn_musicquiz"
    }
}
