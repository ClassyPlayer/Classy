package com.themoon.y1.managers

import java.util.Locale

/** The names in Settings → General → Language: each language in its own name, as on the iPod. */
object LanguageNames {

    /** The built-in language files (the name without .json, in lowercase) and the name of each language. */
    private val NAMES = mapOf(
        "english" to "English",
        "spanish" to "Español",
        "polish" to "Polski",
        "russian" to "Русский",
        "japanese" to "日本語",
        "chinese" to "简体中文",
        "korean" to "한국어",
    )

    /**
     * The name of the language file [fileName]: "russian.json" → "Русский"; the default and english.json → "English".
     * A file that a user added: its name without .json, with a capital first letter ("deutsch.json" → "Deutsch").
     */
    fun of(fileName: String): String {
        if (LanguageManager.isEnglish(fileName)) return "English"
        val base = if (fileName.lowercase(Locale.ROOT).endsWith(".json")) fileName.dropLast(5) else fileName
        NAMES[base.lowercase(Locale.ROOT)]?.let { return it }
        return base.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /**
     * The rows of the Language list for the language files [fileNames] (built-in and added): English first, then the
     * others in the order of their names. English is [LanguageManager.DEFAULT] (english.json is not a second row).
     */
    fun rows(fileNames: List<String>): List<String> =
        listOf(LanguageManager.DEFAULT) + fileNames.filterNot { LanguageManager.isEnglish(it) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { of(it) })

    /** True when the row [fileName] is the language [current] (Settings → General → Language). */
    fun isCurrent(fileName: String, current: String?): Boolean =
        if (LanguageManager.isEnglish(fileName)) LanguageManager.isEnglish(current) else fileName.equals(current, ignoreCase = true)
}
