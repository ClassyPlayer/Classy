package com.themoon.y1.migrations

import com.themoon.y1.Prefs
import com.themoon.y1.StoragePaths
import com.themoon.y1.controllers.menu.MenuOrder

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
        prefs.getString(MenuOrder.KEY, null)?.let { order ->
            val updated = withPhotos(order)
            if (updated != order) MenuOrder.save(prefs, updated)
        }
        val hiddenKey = Prefs.MAIN_MENU_HIDDEN.key(ITEM)
        if (!prefs.contains(hiddenKey)) {
            val hasFolder = StoragePaths.getPhotosDirs().any { it.isDirectory }
            if (startsHidden(hasFolder, StoragePaths.hasSecondaryStorage())) {
                prefs.edit().putBoolean(hiddenKey, true).apply()
            }
        }
    }

    companion object {
        const val VERSION: Int = 16
        const val ID: String = "016_photos_menu_item"
        const val ITEM = "btn_photos"

        /** [order] with [ITEM] after btn_videos (or at the end without it); the same order if it has [ITEM]. */
        fun withPhotos(order: String): String {
            val ids = order.split(",").filter { it.isNotEmpty() }.toMutableList()
            if (ids.isEmpty() || ITEM in ids) return order
            val videos = ids.indexOf("btn_videos")
            if (videos >= 0) ids.add(videos + 1, ITEM) else ids.add(ITEM)
            return ids.joinToString(",")
        }

        /**
         * Hide Photos when no Photos folder was found. When the microSD card is not mounted yet, the folder can be
         * on it: the row stays (an empty Photos list says where to put the photos).
         */
        fun startsHidden(hasPhotosFolder: Boolean, isSdCardMounted: Boolean): Boolean =
            !hasPhotosFolder && isSdCardMounted
    }
}
