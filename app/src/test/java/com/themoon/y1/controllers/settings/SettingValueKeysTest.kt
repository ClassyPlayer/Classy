package com.themoon.y1.controllers.settings

import com.themoon.y1.LanguageFiles
import com.themoon.y1.managers.ButtonVibration
import com.themoon.y1.managers.ClickVolume
import com.themoon.y1.managers.UsbDrive
import com.themoon.y1.navigation.LetterJump
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The values of settings whose words agree with the setting (grammatical gender) have a key for each setting,
 * for example "Weak (Vibration)": each language file translates it, and english.json shows the word without the setting.
 */
class SettingValueKeysTest {

    private val keys = (ButtonVibration.LABELS + ClickVolume.LABELS + LetterJump.Sensitivity.LABELS + UsbDrive.LABELS).toList() +
        listOf("Bass Boost", "Virtualizer", "Crossfeed").flatMap { e -> listOf("Weak", "Normal", "Strong").map { "$it ($e)" } }

    @Test
    fun eachLanguageFileTranslatesTheValues() {
        LanguageFiles.assertEachFileTranslates(keys)
    }

    @Test
    fun englishShowsTheWordWithoutTheSetting() {
        val english = LanguageFiles.all().getValue("english.json")
        for (key in keys) assertEquals(key, key.substringBefore(" ("), english.getString(key))
    }
}
