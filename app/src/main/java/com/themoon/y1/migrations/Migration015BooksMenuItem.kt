package com.themoon.y1.migrations

import com.themoon.y1.Prefs
import com.themoon.y1.StoragePaths

/**
 * Migration 015: the main menu has a new row *Books* (the ebook reader, after *Audiobooks*).
 * - A saved order (Settings → Menus → Main Menu Items) gets Books after Audiobooks once.
 * - The row shows only when a Books folder is there; else it starts hidden, and Main Menu Items can show it.
 */
class Migration015BooksMenuItem : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Put Books after Audiobooks in the main menu; hide it without a Books folder"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val prefs = context.settingsPrefs ?: return
        val order = Prefs.MENU_ORDER.get(prefs)
        val updated = withBooks(order)
        if (updated != order) Prefs.MENU_ORDER.set(prefs, updated)
        val hiddenKey = Prefs.MAIN_MENU_HIDDEN.key(ITEM)
        if (!prefs.contains(hiddenKey)) {
            val hasFolder = StoragePaths.getBooksDirs().any { it.isDirectory }
            if (startsHidden(hasFolder, StoragePaths.hasSecondaryStorage())) {
                prefs.edit().putBoolean(hiddenKey, true).apply()
            }
        }
    }

    companion object {
        const val VERSION: Int = 15
        const val ID: String = "015_books_menu_item"
        const val ITEM = "btn_books"

        /** [order] with [ITEM] after btn_audiobooks (or at the end without it); the same order if it has [ITEM]. */
        fun withBooks(order: String): String {
            val ids = order.split(",").filter { it.isNotEmpty() }.toMutableList()
            if (ids.isEmpty() || ITEM in ids) return order
            val audiobooks = ids.indexOf("btn_audiobooks")
            if (audiobooks >= 0) ids.add(audiobooks + 1, ITEM) else ids.add(ITEM)
            return ids.joinToString(",")
        }

        /**
         * Hide Books when no Books folder was found. When the microSD card is not mounted yet, the folder can be
         * on it: the row stays (an empty Books list says where to put the books).
         */
        fun startsHidden(hasBooksFolder: Boolean, isSdCardMounted: Boolean): Boolean =
            !hasBooksFolder && isSdCardMounted
    }
}
