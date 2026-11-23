package com.themoon.y1.books

import org.json.JSONArray
import org.json.JSONObject

/** A place in a book: a section (EPUB spine item, TXT part) and a char offset in its text. */
data class BookPosition(val section: Int, val offset: Int) : Comparable<BookPosition> {
    override fun compareTo(other: BookPosition): Int =
        if (section != other.section) section.compareTo(other.section) else offset.compareTo(other.offset)

    companion object {
        val START = BookPosition(0, 0)
    }
}

/** The reading place of a book as stored in AppPrefs (`ebook_pos_<path>`): "section:offset:permille". */
class SavedPosition(val position: BookPosition, val permille: Int) {
    fun encode(): String = "${position.section}:${position.offset}:$permille"

    companion object {
        fun decode(value: String?): SavedPosition? {
            val parts = value?.split(':') ?: return null
            if (parts.size < 2) return null
            val section = parts[0].toIntOrNull() ?: return null
            val offset = parts[1].toIntOrNull() ?: return null
            if (section < 0 || offset < 0) return null
            val permille = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 1000) ?: 0
            return SavedPosition(BookPosition(section, offset), permille)
        }
    }
}

object Progress {
    /**
     * How far [position] is in a book whose sections weigh [weights] (bytes), as 0..1000. [length] is the char
     * length of the section of [position] (0: its start counts).
     */
    fun permille(weights: LongArray, position: BookPosition, length: Int): Int {
        if (weights.isEmpty()) return 0
        val total = weights.sum().toDouble()
        if (total <= 0) return 0
        val s = position.section.coerceIn(0, weights.size - 1)
        var before = 0L
        for (i in 0 until s) before += weights[i]
        val inSection = if (length > 0) weights[s] * (position.offset.coerceIn(0, length).toDouble() / length) else 0.0
        return ((before + inSection) / total * 1000).toInt().coerceIn(0, 1000)
    }

    /** The place at [permille] of the book: its section and the fraction in it (0..1). */
    fun at(weights: LongArray, permille: Int): Pair<Int, Double> {
        if (weights.isEmpty()) return 0 to 0.0
        val total = weights.sum().toDouble()
        val target = total * permille.coerceIn(0, 1000) / 1000.0
        var before = 0.0
        for (i in weights.indices) {
            val w = weights[i].toDouble()
            if (target < before + w || i == weights.size - 1) {
                return i to if (w > 0) ((target - before) / w).coerceIn(0.0, 1.0) else 0.0
            }
            before += w
        }
        return weights.size - 1 to 0.0
    }

    /** "42%" from permille; 1000 shows as 100%, and 995..999 as 99% (not finished yet). */
    fun percentText(permille: Int): String = "${permille.coerceIn(0, 1000) / 10}%"
}

/** A saved place with the start of its page text, for the Bookmarks list. */
class Bookmark(val position: BookPosition, val permille: Int, val snippet: String, val chapter: String, val time: Long) {
    companion object {
        fun toJson(marks: List<Bookmark>): String = JSONArray().apply {
            for (m in marks) put(JSONObject()
                .put("s", m.position.section)
                .put("o", m.position.offset)
                .put("p", m.permille)
                .put("t", m.snippet)
                .put("c", m.chapter)
                .put("at", m.time))
        }.toString()

        /** The bookmarks in [json] (a broken value: none), in book order. */
        fun fromJson(json: String?): List<Bookmark> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val a = JSONArray(json)
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val s = o.optInt("s", -1)
                    val off = o.optInt("o", -1)
                    if (s < 0 || off < 0) null
                    else Bookmark(BookPosition(s, off), o.optInt("p"), o.optString("t"), o.optString("c"), o.optLong("at"))
                }.sortedBy { it.position }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** [marks] with [mark] added (one per place: a mark at the same place is replaced), in book order. */
        fun add(marks: List<Bookmark>, mark: Bookmark): List<Bookmark> =
            (marks.filter { it.position != mark.position } + mark).sortedBy { it.position }

        fun remove(marks: List<Bookmark>, position: BookPosition): List<Bookmark> = marks.filter { it.position != position }

        /** The first words of a page for a bookmark row: one line, at most [max] chars. */
        fun snippet(text: CharSequence, max: Int = 60): String {
            val sb = StringBuilder()
            var space = false
            for (c in text) {
                if (c == StyledText.IMAGE_CHAR) continue
                if (c.isWhitespace()) {
                    space = sb.isNotEmpty()
                    continue
                }
                if (space) sb.append(' ')
                space = false
                sb.append(c)
                if (sb.length >= max) {
                    sb.append('…')
                    break
                }
            }
            return sb.toString()
        }
    }
}

/**
 * The chapters (TOC entries) around a place. An entry's place is its section and the offset of its anchor; an
 * anchor of a section that is not read yet counts as the section start.
 */
object ChapterNav {

    /** The places of [toc]: [anchors] gives the anchor offsets of a section when it is known (null: not read yet). */
    fun places(toc: List<TocEntry>, anchors: (Int) -> Map<String, Int>?): List<BookPosition> = toc.map { e ->
        val a = e.anchor
        val offset = if (a == null) e.offset else anchors(e.section)?.get(a) ?: 0
        BookPosition(e.section, offset)
    }

    /** The entry that [position] is in: the last one that starts at or before it (-1: before the first). */
    fun current(places: List<BookPosition>, position: BookPosition): Int {
        var best = -1
        for (i in places.indices) {
            val p = places[i]
            if (p <= position && (best < 0 || p >= places[best])) best = i
        }
        return best
    }

    /** The first entry that starts after [position], or -1. */
    fun next(places: List<BookPosition>, position: BookPosition): Int {
        var best = -1
        for (i in places.indices) {
            val p = places[i]
            if (p > position && (best < 0 || p < places[best])) best = i
        }
        return best
    }

    /**
     * Previous Chapter: the start of the chapter that [position] is in when the page is past it, else the chapter
     * before ([pageStart]: the place at the top of the page).
     */
    fun previous(places: List<BookPosition>, pageStart: BookPosition): Int {
        var best = -1
        for (i in places.indices) {
            val p = places[i]
            if (p < pageStart && (best < 0 || p >= places[best])) best = i
        }
        return best
    }
}
