package com.themoon.y1.migrations

import com.themoon.y1.Prefs
import com.themoon.y1.StoragePaths
import com.themoon.y1.controllers.menu.MenuOrder

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
        prefs.getString(MenuOrder.KEY, null)?.let { order ->
            val updated = withGames(order)
            if (updated != order) MenuOrder.save(prefs, updated)
        }
        val hiddenKey = Prefs.MAIN_MENU_HIDDEN.key(ITEM)
        if (!prefs.contains(hiddenKey)) {
            val hasFolder = StoragePaths.getGamesDirs().any { it.isDirectory }
            if (startsHidden(hasFolder, StoragePaths.hasSecondaryStorage())) {
                prefs.edit().putBoolean(hiddenKey, true).apply()
            }
        }
    }

    companion object {
        const val VERSION: Int = 12
        const val ID: String = "012_games_menu_item"
        const val ITEM = "btn_games"

        /** [order] with [ITEM] after btn_musicquiz (or at the end without it); the same order if it has [ITEM]. */
        fun withGames(order: String): String {
            val ids = order.split(",").filter { it.isNotEmpty() }.toMutableList()
            if (ids.isEmpty() || ITEM in ids) return order
            val quiz = ids.indexOf("btn_musicquiz")
            if (quiz >= 0) ids.add(quiz + 1, ITEM) else ids.add(ITEM)
            return ids.joinToString(",")
        }

        /**
         * Hide Games when no Games folder was found. When the microSD card is not mounted yet, the folder can be
         * on it: the row stays (an empty Games list says where to put the games).
         */
        fun startsHidden(hasGamesFolder: Boolean, isSdCardMounted: Boolean): Boolean =
            !hasGamesFolder && isSdCardMounted
    }
}
