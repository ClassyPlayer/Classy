package com.themoon.y1.managers

import com.themoon.y1.managers.LanguageManager.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageManagerTest {

    private val builtIn = listOf("Japanese.json", "chinese.json", "english.json", "spanish.json")

    @Test
    fun builtInLanguagesLoadFromTheAppAlsoWhenTheFolderHasACopy() {
        // An earlier version copied the built-in files to the Languages folder; those copies do not update
        assertEquals(Source.Asset("spanish.json"), LanguageManager.sourceOf("spanish.json", builtIn, listOf("spanish.json")))
        assertEquals(Source.Asset("Japanese.json"), LanguageManager.sourceOf("japanese.json", builtIn, emptyList()))
    }

    @Test
    fun aFileThatAUserAddedLoadsFromTheFolder() {
        assertEquals(Source.Storage("deutsch.json"), LanguageManager.sourceOf("deutsch.json", builtIn, listOf("deutsch.json")))
        assertNull(LanguageManager.sourceOf("deutsch.json", builtIn, emptyList()))
    }

    @Test
    fun englishIsTheBase() {
        assertNull(LanguageManager.sourceOf(LanguageManager.DEFAULT, builtIn, emptyList()))
        assertNull(LanguageManager.sourceOf("english.json", builtIn, listOf("english.json")))
        assertTrue(LanguageManager.isEnglish(null))
        assertTrue(LanguageManager.isEnglish("English.json"))
    }

    @Test
    fun userFilesAreTheFilesWithoutABuiltInName() {
        assertEquals(
            listOf("Deutsch.json", "français.JSON"),
            LanguageManager.userFiles(builtIn, listOf("SPANISH.json", "français.JSON", "notes.txt", "english.json", "Deutsch.json"))
        )
    }

    @Test
    fun parseKeepsTheTextsAndSkipsOtherValues() {
        assertEquals(mapOf("Songs" to "Canciones"), LanguageManager.parse("""{"Songs": "Canciones", "n": 3}"""))
        assertEquals(emptyMap<String, String>(), LanguageManager.parse("not json"))
    }
}
