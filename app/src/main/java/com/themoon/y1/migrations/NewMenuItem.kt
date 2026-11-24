package com.themoon.y1.migrations

import android.content.SharedPreferences
import com.themoon.y1.Prefs
import com.themoon.y1.StoragePaths
import java.io.File

/**
 * The steps of a migration for a new main menu item (008 Play On, 012 Games, 015 Books, 016 Photos). A saved order
 * (Settings → Menus → Main Menu Items) puts an item that it does not have at the end: the migration puts it after its
 * neighbour once. An item for a folder starts hidden when the folder is not there.
 */
internal object NewMenuItem {
    /** Puts [item] after [after] in the saved main menu order of [prefs] ([insertAfter]). */
    fun putAfter(prefs: SharedPreferences, item: String, after: String) {
        val order = Prefs.MENU_ORDER.get(prefs)
        val updated = insertAfter(order, item, after)
        if (updated != order) Prefs.MENU_ORDER.set(prefs, updated)
    }

    /** [order] with [item] after [after] (or at the end without [after]); the same order if it has [item] or is empty. */
    fun insertAfter(order: String, item: String, after: String): String {
        val ids = order.split(",").filter { it.isNotEmpty() }.toMutableList()
        if (ids.isEmpty() || item in ids) return order
        val i = ids.indexOf(after)
        if (i >= 0) ids.add(i + 1, item) else ids.add(item)
        return ids.joinToString(",")
    }

    /** Hides [item] when none of its [folders] is there ([startsHidden]). A saved value (the user's choice) stays. */
    fun hideWithoutFolder(prefs: SharedPreferences, item: String, folders: () -> List<File>) {
        val hiddenKey = Prefs.MAIN_MENU_HIDDEN.key(item)
        if (prefs.contains(hiddenKey)) return
        val hasFolder = folders().any { it.isDirectory }
        if (startsHidden(hasFolder, StoragePaths.hasSecondaryStorage())) {
            prefs.edit().putBoolean(hiddenKey, true).apply()
        }
    }

    /**
     * Hide the item when no folder was found. When the microSD card is not mounted yet, the folder can be on it: the
     * row stays (the empty list says where to put the files).
     */
    fun startsHidden(hasFolder: Boolean, isSdCardMounted: Boolean): Boolean = !hasFolder && isSdCardMounted
}
