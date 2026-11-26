package com.themoon.y1.managers

import java.util.IllegalFormatException
import java.util.Locale

/**
 * Texts with a count ("1 Song", "12 Songs"). The text of a count key in a language file has one form for each plural
 * category of the language, separated by "|", in the order of [Rule]: for example english.json
 * `"%d Songs": "%d Song|%d Songs"`, russian.json `"%d песня|%d песни|%d песен"`, Japanese.json `"%d曲"`.
 * The "Language code" key of each file gives its [Rule] (CLDR rules, for whole numbers only).
 */
object Plurals {

    /** The plural categories of a language, in the order of the forms. */
    enum class Rule(val forms: Int) {
        /** One form: Chinese, Japanese, Korean and other languages without plural forms. */
        NONE(1),
        /** 1 | other: English, Spanish, German and most other languages; also a language with an unknown code. */
        ONE_OTHER(2),
        /** 0 and 1 | other: French, Portuguese. */
        ZERO_ONE_OTHER(2),
        /** one (1, 21, 31…) | few (2–4, 22–24…) | many (0, 5–20, 25–30…): Russian, Ukrainian, Belarusian. */
        EAST_SLAVIC(3),
        /** one (1) | few (2–4, 22–24…) | many (0, 5–21, 25–31…): Polish. */
        POLISH(3),
        /** one (1) | few (2–4) | other: Czech, Slovak. */
        CZECH(3),
    }

    /** The [Rule] of a language code ("en", "pt-BR"…). */
    fun rule(code: String): Rule = when (code.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
        "zh", "ja", "ko", "vi", "th", "id", "ms", "lo", "my", "km" -> Rule.NONE
        "fr", "pt" -> Rule.ZERO_ONE_OTHER
        "ru", "uk", "be" -> Rule.EAST_SLAVIC
        "pl" -> Rule.POLISH
        "cs", "sk" -> Rule.CZECH
        else -> Rule.ONE_OTHER
    }

    /** The index of the form for the count [n] (negative counts count as positive). */
    fun index(rule: Rule, n: Long): Int {
        val a = Math.abs(n)
        val mod10 = a % 10
        val mod100 = a % 100
        val few = mod10 in 2..4 && mod100 !in 12..14
        return when (rule) {
            Rule.NONE -> 0
            Rule.ONE_OTHER -> if (a == 1L) 0 else 1
            Rule.ZERO_ONE_OTHER -> if (a <= 1L) 0 else 1
            Rule.EAST_SLAVIC -> if (mod10 == 1L && mod100 != 11L) 0 else if (few) 1 else 2
            Rule.POLISH -> if (a == 1L) 0 else if (few) 1 else 2
            Rule.CZECH -> if (a == 1L) 0 else if (a in 2..4) 1 else 2
        }
    }

    /**
     * The rule for [text] in a language with the rule [language]. A text with another number of forms is the English
     * text (english.json is under each language, so a key that a language file does not have shows its English text):
     * then the English rule, so that "%d Song|%d Songs" does not show "21 Song" with the Russian rule.
     */
    fun ruleFor(text: String, language: Rule): Rule =
        if (text.split('|').size == language.forms) language else Rule.ONE_OTHER

    /** The form of [text] ("form|form|…") for [n]; the last form when the text has fewer forms than the rule. */
    fun choose(text: String, rule: Rule, n: Long): String {
        val forms = text.split('|')
        return forms[Math.min(index(rule, n), forms.size - 1)]
    }

    /**
     * The translated text of the count key [key] for [n]: the form for [n], with [n] for `%d` (or `%1$d`) and [args]
     * for the next placeholders.
     */
    fun format(localize: (String) -> String, key: String, n: Int, vararg args: Any): String {
        val text = localize(key)
        val form = choose(text, ruleFor(text, rule(localize("Language code"))), n.toLong())
        return try {
            String.format(Locale.ROOT, form, n, *args)
        } catch (_: IllegalFormatException) {
            // A language file with a wrong placeholder: show the text with the number, not an error
            form.replace("%1\$d", n.toString()).replace("%d", n.toString())
        }
    }
}
