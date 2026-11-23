package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClassyListsTest {
    @Test
    fun splitArrowTakesTheArrowMarkFromTheValue() {
        assertEquals("" to true, ClassyLists.splitArrow("〉 "))
        assertEquals("" to true, ClassyLists.splitArrow("〉"))
        assertEquals("Edit" to true, ClassyLists.splitArrow("Edit 〉"))
        assertEquals("Normal" to true, ClassyLists.splitArrow("Normal ›"))
        assertEquals("On" to false, ClassyLists.splitArrow("On"))
        assertEquals("" to false, ClassyLists.splitArrow(""))
        assertFalse(ClassyLists.splitArrow("v1.0").second)
    }
}
