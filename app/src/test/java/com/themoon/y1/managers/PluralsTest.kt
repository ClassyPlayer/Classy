package com.themoon.y1.managers

import com.themoon.y1.LanguageFiles
import com.themoon.y1.managers.Plurals.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluralsTest {

    private fun forms(rule: Rule, vararg n: Long): List<Int> = n.map { Plurals.index(rule, it) }

    @Test
    fun rulesOfTheLanguageCodes() {
        assertEquals(Rule.ONE_OTHER, Plurals.rule("en"))
        assertEquals(Rule.ONE_OTHER, Plurals.rule("es"))
        assertEquals(Rule.POLISH, Plurals.rule("pl"))
        assertEquals(Rule.EAST_SLAVIC, Plurals.rule("ru"))
        assertEquals(Rule.EAST_SLAVIC, Plurals.rule(" UK "))
        assertEquals(Rule.ZERO_ONE_OTHER, Plurals.rule("pt-BR"))
        assertEquals(Rule.NONE, Plurals.rule("zh_CN"))
        assertEquals(Rule.NONE, Plurals.rule("ja"))
        assertEquals(Rule.NONE, Plurals.rule("ko"))
        // A file without a code (the key itself comes back) or with an unknown one: 1 | other
        assertEquals(Rule.ONE_OTHER, Plurals.rule("Language code"))
    }

    @Test
    fun englishAndSpanishHaveOneAndOther() {
        assertEquals(listOf(1, 0, 1, 1, 1), forms(Rule.ONE_OTHER, 0, 1, 2, 11, 21))
        assertEquals(listOf(0, 0, 1), forms(Rule.ZERO_ONE_OTHER, 0, 1, 2))
    }

    @Test
    fun russianUsesTheLastDigits() {
        // 1 песня, 2 песни, 5 песен, 11 песен, 12 песен, 21 песня, 22 песни, 25 песен, 101 песня, 111 песен, 112 песен
        assertEquals(
            listOf(2, 0, 1, 1, 2, 2, 2, 2, 0, 1, 2, 0, 2, 2, 1),
            forms(Rule.EAST_SLAVIC, 0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111, 112, 1022)
        )
    }

    @Test
    fun polishHasOneOnlyForOne() {
        // 1 utwór, 2 utwory, 5 utworów, 12 utworów, 21 utworów (not "one" as in Russian), 22 utwory, 112 utworów
        assertEquals(
            listOf(2, 0, 1, 1, 2, 2, 2, 2, 1, 2, 2, 1),
            forms(Rule.POLISH, 0, 1, 2, 4, 5, 12, 14, 21, 22, 101, 112, 1022)
        )
    }

    @Test
    fun czechAndNone() {
        assertEquals(listOf(2, 0, 1, 1, 2, 2), forms(Rule.CZECH, 0, 1, 2, 4, 5, 22))
        assertEquals(listOf(0, 0, 0), forms(Rule.NONE, 0, 1, 100))
    }

    @Test
    fun chooseUsesTheLastFormWhenFormsAreMissing() {
        assertEquals("%d песен", Plurals.choose("%d песня|%d песни|%d песен", Rule.EAST_SLAVIC, 5))
        assertEquals("%d songs", Plurals.choose("%d songs", Rule.ONE_OTHER, 1))
        assertEquals("b", Plurals.choose("a|b", Rule.EAST_SLAVIC, 5))
    }

    @Test
    fun formatPutsInTheNumberAndTheOtherValues() {
        val russian = mapOf("Language code" to "ru", "%d Songs" to "%d песня|%d песни|%d песен", "A %1\$d %2\$s" to "%2\$s: %1\$d")
        val localize = { k: String -> russian[k] ?: k }
        assertEquals("21 песня", Plurals.format(localize, "%d Songs", 21))
        assertEquals("3 песни", Plurals.format(localize, "%d Songs", 3))
        assertEquals("FM: 7", Plurals.format(localize, "A %1\$d %2\$s", 7, "FM"))
        // English without a language file: the key
        assertEquals("1 Songs", Plurals.format({ it }, "%d Songs", 1))
        // A key that the language file does not have: the English text, with the English rule
        val partial = { k: String -> if (k == "Language code") "ru" else if (k == "%d Songs") "%d Song|%d Songs" else k }
        assertEquals("21 Songs", Plurals.format(partial, "%d Songs", 21))
        assertEquals("1 Song", Plurals.format(partial, "%d Songs", 1))
        val japanese = { k: String -> if (k == "Language code") "ja" else if (k == "%d Songs") "%d Song|%d Songs" else k }
        assertEquals("5 Songs", Plurals.format(japanese, "%d Songs", 5))
        // A wrong placeholder in a language file: the text with the number, no exception
        assertEquals("4 %s", Plurals.format({ if (it == "k") "%d %s" else it }, "k", 4))
    }

    @Test
    fun eachLanguageFileHasTheFormsOfItsLanguage() {
        val keys = listOf(
            "%d Songs", "%d Files", "%d Seasons", "%d Episodes", "%d minutes",
            "Scan Complete!\nFound %1\$d channels.\nTuning to %2\$s"
        )
        for ((name, json) in LanguageFiles.all()) {
            val rule = Plurals.rule(json.getString("Language code"))
            for (key in keys) {
                val forms = json.getString(key).split('|')
                assertEquals("$name: \"$key\"", rule.forms, forms.size)
                for (form in forms) assertTrue("$name: \"$form\" has no number", form.contains("%d") || form.contains("%1\$d"))
            }
        }
    }
}
