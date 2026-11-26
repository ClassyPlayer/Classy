package com.themoon.y1.migrations

import android.content.SharedPreferences
import com.themoon.y1.Prefs

/**
 * The step of a migration for a new main menu item (008 Play On). A saved order (Settings → Menus → Main Menu Items)
 * puts an item that it does not have at the end: the migration puts it after its neighbour once.
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
}
