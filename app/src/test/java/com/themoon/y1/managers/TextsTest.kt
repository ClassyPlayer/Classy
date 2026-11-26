package com.themoon.y1.managers

import com.themoon.y1.LanguageFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextsTest {

    @Test
    fun theTranslationGetsTheValues() {
        val japanese = mapOf("%1\$s %2\$d (month and year)" to "%2\$d年%3\$d月", "Round %d" to "ラウンド %d")
        val localize = { k: String -> japanese[k] ?: k }
        assertEquals("2026年1月", Texts.format(localize, "%1\$s %2\$d (month and year)", "1月", 2026, 1))
        assertEquals("ラウンド 3", Texts.format(localize, "Round %d", 3))
    }

    @Test
    fun aWrongPlaceholderGivesTheEnglishText() {
        assertEquals("Round 3", Texts.format({ "Ronda %s %s" }, "Round %d", 3))
    }

    /** Each language file can format each sentence key with the values that the code gives. */
    @Test
    fun eachLanguageFileFormatsTheSentenceKeys() {
        val keys = mapOf(
            "Which artist released %s?" to arrayOf<Any>("“Song”"),
            "Which album is %s from?" to arrayOf<Any>("“Song”"),
            "What year was %s released?" to arrayOf<Any>("“Song”"),
            "Round %d" to arrayOf<Any>(3),
            "Round %d Complete!" to arrayOf<Any>(3),
            "Created Playlist %d successfully!" to arrayOf<Any>(2),
            "Season %d" to arrayOf<Any>(1),
            "Do you want to delete '%s'?" to arrayOf<Any>("Show"),
            "%1\$s %2\$d (month and year)" to arrayOf<Any>("May", 2026, 5),
        )
        for ((name, json) in LanguageFiles.all()) {
            for ((key, args) in keys) {
                val text = json.getString(key)
                // The English fallback would hide a wrong placeholder: format the file's own text
                val result = String.format(text, *args)
                assertFalse("$name: \"$key\" → \"$result\"", result.contains('%'))
            }
        }
    }
}
