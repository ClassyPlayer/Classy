package com.themoon.y1.controllers.menu

import android.content.SharedPreferences

/**
 * Settings → Main Menu Items order: the ids in one key, the same for both themes. Ids that the menu does not have are
 * ignored; items that are not in the order go after the ordered items, in the default order.
 */
object MenuOrder {
    const val KEY = "custom_menu_order"

    fun load(prefs: SharedPreferences): String = prefs.getString(KEY, null) ?: ""

    fun save(prefs: SharedPreferences, order: String) {
        prefs.edit().putString(KEY, order).commit()
    }

    fun <T> sort(items: MutableList<T>, order: String, id: (T) -> String, defaultIndex: (T) -> Int) {
        val ids = order.split(",").filter { it.isNotEmpty() }
        items.sortWith { e1, e2 ->
            val idx1 = ids.indexOf(id(e1))
            val idx2 = ids.indexOf(id(e2))
            if (idx1 != -1 && idx2 != -1) idx1 - idx2
            else if (idx1 != -1) -1
            else if (idx2 != -1) 1
            else defaultIndex(e1) - defaultIndex(e2)
        }
    }
}
