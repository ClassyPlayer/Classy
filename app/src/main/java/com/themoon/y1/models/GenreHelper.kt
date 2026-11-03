package com.themoon.y1.models

import java.util.Locale

/**
 * Utility for parsing and matching multi-genre tags in audio files.
 *
 * Supports common tag separators:
 * - Semicolons (";")
 * - Forward slashes ("/")
 * - Commas (",")
 * - Double-backslashes ("\\")
 * - Null bytes ("\0") and newlines
 *
 * Preserves compound genres like "Singer/Songwriter" without splitting them.
 */
object GenreHelper {
    private const val SINGER_SONGWRITER_PLACEHOLDER = "__SINGER_SONGWRITER__"
    private val SINGER_SONGWRITER_REGEX = Regex("(?i)\\bSinger\\s*/\\s*Songwriter\\b")
    private val DELIMITER_REGEX = Regex("[;,\\u0000\\r\\n]+|\\\\+|/")

    /**
     * Splits a raw genre string into individual distinct genre names.
     * Trims whitespace and preserves case of first occurrence, removing case-insensitive duplicates.
     */
    fun parseGenres(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return listOf("Unknown Genre")
        val trimmed = raw.trim()
        if (trimmed.equals("Unknown Genre", ignoreCase = true)) {
            return listOf("Unknown Genre")
        }

        // Protect "Singer/Songwriter" before splitting by forward slash
        val hasSingerSongwriter = SINGER_SONGWRITER_REGEX.containsMatchIn(trimmed)
        val prepared = if (hasSingerSongwriter) {
            SINGER_SONGWRITER_REGEX.replace(trimmed, SINGER_SONGWRITER_PLACEHOLDER)
        } else {
            trimmed
        }

        val tokens = prepared.split(DELIMITER_REGEX)
        val result = LinkedHashSet<String>()
        val seenLower = HashSet<String>()

        for (token in tokens) {
            var item = token.trim()
            if (item.isEmpty()) continue
            if (item == SINGER_SONGWRITER_PLACEHOLDER) {
                item = "Singer/Songwriter"
            }
            if (seenLower.add(item.lowercase(Locale.US))) {
                result.add(item)
            }
        }

        return if (result.isEmpty()) listOf(trimmed) else result.toList()
    }

    /**
     * Checks if [rawGenres] contains [targetGenre] (case-insensitive).
     */
    fun matches(rawGenres: String?, targetGenre: String?): Boolean {
        if (rawGenres.isNullOrBlank() || targetGenre.isNullOrBlank()) return false
        val target = targetGenre.trim()
        return parseGenres(rawGenres).any { it.equals(target, ignoreCase = true) }
    }
}
