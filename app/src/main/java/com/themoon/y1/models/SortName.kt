package com.themoon.y1.models

import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/**
 * The A–Z order of artist names and album titles, as on the iPod: a leading "The " is ignored ("The Beatles" sorts
 * under B), case is ignored, and accented letters sort next to their base letters. The name on the screen does not change.
 * Cover Flow, the Music lists of artists and albums, and the letter jump use this rule.
 */
object SortName {

    /** [name] without a leading "The " ("The Beatles" → "Beatles"). "The" alone and "Them" do not change. */
    fun of(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.length > 4 && trimmed.startsWith("The ", ignoreCase = true)) trimmed.substring(4).trimStart() else trimmed
    }

    /**
     * A comparator of names by [of]. Names with the same sort name ("The Beatles" and "Beatles") compare as equal.
     * Make a new one for each sort: it keeps the collation key of each name, and it is not thread-safe.
     */
    fun order(): Comparator<String> {
        val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
        val keys = HashMap<String, CollationKey>()
        return Comparator { a, b ->
            keys.getOrPut(a) { collator.getCollationKey(of(a)) }.compareTo(keys.getOrPut(b) { collator.getCollationKey(of(b)) })
        }
    }

    /** [names] sorted by [order]. Names with the same sort name are in case-insensitive order of the full name. */
    fun sort(names: MutableList<String>) {
        names.sortWith(order().then(String.CASE_INSENSITIVE_ORDER))
    }
}
