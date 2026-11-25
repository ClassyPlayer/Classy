package com.themoon.y1.models

import com.themoon.y1.navigation.LetterJump
import org.junit.Assert.assertEquals
import org.junit.Test

class SortNameTest {

    private fun sorted(vararg names: String): List<String> = names.toMutableList().also { SortName.sort(it) }

    @Test
    fun of_dropsOnlyALeadingThe() {
        assertEquals("Beatles", SortName.of("The Beatles"))
        assertEquals("Who", SortName.of("  the  Who "))
        assertEquals("Theory of a Deadman", SortName.of("Theory of a Deadman"))
        assertEquals("The", SortName.of("The"))
        assertEquals("The", SortName.of("The "))
        assertEquals("Them", SortName.of("Them"))
        assertEquals("Beyond the Sea", SortName.of("Beyond the Sea"))
    }

    @Test
    fun sort_ignoresLeadingTheAndCase() {
        assertEquals(
            listOf("Abba", "The Beatles", "beck", "Coldplay", "The Theory", "Thin Lizzy", "The Who"),
            sorted("The Who", "Thin Lizzy", "Coldplay", "beck", "The Beatles", "Abba", "The Theory")
        )
    }

    @Test
    fun sort_putsAccentedLettersWithTheirBaseLetters() {
        assertEquals(listOf("Eagles", "Édith Piaf", "Ezra", "Zeca Afonso"), sorted("Zeca Afonso", "Ezra", "Édith Piaf", "Eagles"))
    }

    @Test
    fun sort_keepsAFixedOrderForTheSameSortName() {
        assertEquals(listOf("Beatles", "The Beatles"), sorted("The Beatles", "Beatles"))
        assertEquals(listOf("Beatles", "The Beatles"), sorted("Beatles", "The Beatles"))
    }

    @Test
    fun order_comparesTheSortNames() {
        val order = SortName.order()
        assertEquals(0, order.compare("The Beatles", "beatles"))
        assertEquals(true, order.compare("The Wall", "Animals") > 0)
        assertEquals(true, order.compare("The Wall", "Thriller") > 0)
    }

    @Test
    fun letterJumpFollowsTheSortOrder() {
        val rows = sorted("The Who", "Television", "The Beatles", "Abba", "Thin Lizzy", "Blur", "Wham!")
        assertEquals(listOf("Abba", "The Beatles", "Blur", "Television", "Thin Lizzy", "Wham!", "The Who"), rows)
        assertEquals(listOf("A", "B", "B", "T", "T", "W", "W"), LetterJump.lettersFor(rows))
    }
}
