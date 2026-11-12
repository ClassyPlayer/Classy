package com.themoon.y1.models

import java.util.concurrent.ConcurrentHashMap

/**
 * The artists of a multi-artist tag. A tag with several values (Vorbis ARTIST twice, ID3v2.4 null-separated) is
 * read as one string with ";" or a null byte between the names, e.g. "Beau Black;Alex Cartana".
 *
 * Only those separators split: "," "&" and "/" are part of names such as "Earth, Wind & Fire" and "AC/DC".
 */
object ArtistNames {
    private val SEPARATORS = Regex("[;\\u0000]+")
    private val cache = ConcurrentHashMap<String, List<String>>()

    /** The names in [raw], trimmed, without empty ones or case-insensitive repeats. A blank [raw] gives no names. */
    fun split(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        cache[raw]?.let { return it }
        val seen = ignoringCase()
        val names = raw.split(SEPARATORS).map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it) }
        cache[raw] = names
        return names
    }

    /** [names] with one entry for each name ignoring case: the first spelling stays. */
    fun distinctIgnoringCase(names: Iterable<String>): List<String> {
        val seen = ignoringCase()
        return names.filter { seen.add(it) }
    }

    /**
     * A set that treats names as equal by the same rule as `equals(ignoreCase = true)` in [contains] (character by
     * character), so a name that matches the same songs is never listed twice (also "Σ" and "ς", which differ after
     * lowercase()).
     */
    private fun ignoringCase() = java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER)

    /** The names of [raw] for a label: "Beau Black, Alex Cartana". */
    fun display(raw: String?): String = split(raw).joinToString(", ")

    /** True if [target] is one of the names in [raw], or all of [raw] (ignoring case). */
    fun contains(raw: String?, target: String?): Boolean {
        val t = target?.trim()
        if (t.isNullOrEmpty() || raw == null) return false
        return raw.trim().equals(t, ignoreCase = true) || split(raw).any { it.equals(t, ignoreCase = true) }
    }
}
