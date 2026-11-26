package com.themoon.y1.controllers.menu

import android.content.SharedPreferences
import com.themoon.y1.PrefFamily
import com.themoon.y1.StringPref

/** One item of a [MenuItems] menu. [label] is the English text (localized when shown). */
class MenuItem(val id: String, val label: String, val action: String = id)

/**
 * A menu that Settings → Menus edits ([MainMenuItems], [MusicMenuItems]): its items, their order and the hidden items.
 * The saved order and the hide keys use the item ids: do not change them.
 * - [order]: the ids, comma-separated. Ids that the menu does not have are ignored; items that are not in the order go
 *   after the ordered items, in the default order.
 * - [hidden]: "<prefix><id>" = true hides the item. With no key, the item shows: no item is hidden by default.
 */
open class MenuItems(
    /** In the default order. */
    val items: List<MenuItem>,
    private val order: StringPref,
    private val hidden: PrefFamily
) {
    /** All items in the saved order. */
    fun ordered(prefs: SharedPreferences): List<MenuItem> = ordered(order.get(prefs))

    internal fun ordered(order: String): List<MenuItem> {
        val ids = order.split(",").filter { it.isNotEmpty() }
        return items.sortedWith(compareBy { ids.indexOf(it.id).let { i -> if (i >= 0) i else ids.size + items.indexOf(it) } })
    }

    /** Saves the order of all items ([ids] in the new order). */
    fun saveOrder(prefs: SharedPreferences, ids: List<String>) {
        order.set(prefs, ids.joinToString(","))
    }

    fun isHidden(prefs: SharedPreferences, id: String): Boolean = prefs.getBoolean(hidden.key(id), false)

    fun setHidden(prefs: SharedPreferences, id: String, hide: Boolean) {
        prefs.edit().putBoolean(hidden.key(id), hide).commit()
    }

    /** The rows of the menu: the items that are not hidden, in the saved order. */
    fun shown(prefs: SharedPreferences): List<MenuItem> = ordered(prefs).filterNot { isHidden(prefs, it.id) }
}
