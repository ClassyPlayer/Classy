package com.themoon.y1.managers

import com.themoon.y1.LanguageFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageNamesTest {

    @Test
    fun builtInLanguagesHaveTheirOwnNames() {
        assertEquals("English", LanguageNames.of(LanguageManager.DEFAULT))
        assertEquals("English", LanguageNames.of("english.json"))
        assertEquals("Español", LanguageNames.of("spanish.json"))
        assertEquals("Polski", LanguageNames.of("polish.json"))
        assertEquals("Русский", LanguageNames.of("russian.json"))
        assertEquals("日本語", LanguageNames.of("Japanese.json"))
        assertEquals("简体中文", LanguageNames.of("chinese.json"))
        assertEquals("한국어", LanguageNames.of("korean.json"))
    }

    @Test
    fun aFileThatAUserAddedShowsItsNameWithACapital() {
        assertEquals("Deutsch", LanguageNames.of("deutsch.json"))
        assertEquals("Français", LanguageNames.of("français.JSON"))
        assertEquals("My-spanish", LanguageNames.of("my-spanish.json"))
    }

    @Test
    fun eachBuiltInFileHasAName() {
        // Not only the file name with a capital: a new built-in file needs its name in LanguageNames
        for (name in LanguageFiles.all().keys.filterNot { LanguageManager.isEnglish(it) }) {
            assertNotEquals(name, name.removeSuffix(".json").replaceFirstChar { it.uppercase() }, LanguageNames.of(name))
        }
    }

    @Test
    fun englishIsFirstAndOnlyOnce() {
        assertEquals(
            listOf(LanguageManager.DEFAULT, "deutsch.json", "spanish.json", "polish.json", "russian.json", "Japanese.json", "chinese.json", "korean.json"),
            LanguageNames.rows(listOf("Japanese.json", "chinese.json", "english.json", "korean.json", "polish.json", "russian.json", "spanish.json", "deutsch.json"))
        )
    }

    @Test
    fun theCheckMarkIsOnTheChosenLanguage() {
        assertTrue(LanguageNames.isCurrent(LanguageManager.DEFAULT, "english.json"))
        assertTrue(LanguageNames.isCurrent(LanguageManager.DEFAULT, LanguageManager.DEFAULT))
        assertTrue(LanguageNames.isCurrent("Japanese.json", "japanese.json"))
        assertFalse(LanguageNames.isCurrent("spanish.json", LanguageManager.DEFAULT))
    }
}
