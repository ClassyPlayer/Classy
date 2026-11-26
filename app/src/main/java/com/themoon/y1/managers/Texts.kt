package com.themoon.y1.managers

import java.util.IllegalFormatException
import java.util.Locale

/**
 * Translated texts with values in them ("Round %d Complete!"). One key holds the whole sentence, so that each
 * language can put the values where its word order needs them (for example "%2$d年%3$d月").
 */
object Texts {

    /** The translation of [key] with [args] in it; with a wrong placeholder in a language file, the English text. */
    fun format(localize: (String) -> String, key: String, vararg args: Any): String = try {
        String.format(Locale.ROOT, localize(key), *args)
    } catch (_: IllegalFormatException) {
        try {
            String.format(Locale.ROOT, key, *args)
        } catch (_: IllegalFormatException) {
            key
        }
    }
}
