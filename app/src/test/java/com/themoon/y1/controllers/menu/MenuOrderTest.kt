package com.themoon.y1.controllers.menu

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuOrderTest {
    private data class Item(val id: String, val themeIndex: Int)

    private fun sorted(order: String, vararg items: Item): List<String> {
        val list = items.toMutableList()
        MenuOrder.sort(list, order, { it.id }, { it.themeIndex })
        return list.map { it.id }
    }

    @Test
    fun noOrderKeepsTheThemeOrder() {
        assertEquals(listOf("a", "b", "c"), sorted("", Item("c", 2), Item("a", 0), Item("b", 1)))
    }

    @Test
    fun orderedItemsComeFirstAndOthersFollowInThemeOrder() {
        assertEquals(listOf("c", "a", "b", "d"), sorted("c,a,", Item("a", 0), Item("b", 1), Item("c", 2), Item("d", 3)))
    }

    @Test
    fun idsThatTheThemeDoesNotHaveAreIgnored() {
        assertEquals(listOf("b", "a"), sorted("x,b,y,a", Item("a", 0), Item("b", 1)))
    }
}
