package com.themoon.y1.migrations

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
        NewMenuItem.putAfter(prefs, ITEM, AFTER)
        NewMenuItem.hideWithoutFolder(prefs, ITEM) { StoragePaths.getBooksDirs() }
    }

    companion object {
        const val VERSION: Int = 15
        const val ID: String = "015_books_menu_item"
        const val ITEM = "btn_books"
        const val AFTER = "btn_audiobooks"
    }
}
