package com.themoon.y1.migrations

import com.themoon.y1.StoragePaths

/**
 * Migration 016: the main menu has a new row *Photos* (after *Videos*).
 * - A saved order (Settings → Menus → Main Menu Items) gets Photos after Videos once.
 * - The row shows only when a Photos folder is there; else it starts hidden, and Main Menu Items can show it.
 *
 * Version 16, not 15: the Books branch (ebook reader) has Migration 015.
 */
class Migration016PhotosMenuItem : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Put Photos after Videos in the main menu; hide it without a Photos folder"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val prefs = context.settingsPrefs ?: return
        NewMenuItem.putAfter(prefs, ITEM, AFTER)
        NewMenuItem.hideWithoutFolder(prefs, ITEM) { StoragePaths.getPhotosDirs() }
    }

    companion object {
        const val VERSION: Int = 16
        const val ID: String = "016_photos_menu_item"
        const val ITEM = "btn_photos"
        const val AFTER = "btn_videos"
    }
}
