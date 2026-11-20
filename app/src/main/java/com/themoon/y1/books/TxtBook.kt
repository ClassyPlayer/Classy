package com.themoon.y1.books

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** The text encoding of a .txt file. */
object TextEncoding {

    /** A byte order mark at the start of [bytes]: its charset name and length, or null. */
    fun bom(bytes: ByteArray): Pair<String, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> "UTF-8" to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE" to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE" to 2
        else -> null
    }

    /**
     * The charset of [sample] (the start of the file): its BOM, UTF-8 when it decodes as UTF-8, else what
     * juniversalchardet finds, else the usual codepage of the UI [language] (a language file name, "polish.json").
     * juniversalchardet has no Central European model: a Polish or Czech file reads as windows-1252 there. A Latin-1
     * answer becomes windows-1250 with a Central European UI language, or when the text reads better that way
     * ([looksCentralEuropean]).
     */
    fun detect(sample: ByteArray, language: String?, complete: Boolean = false): String {
        bom(sample)?.let { return it.first }
        if (isUtf8(sample, complete)) return "UTF-8"
        val lang = language.orEmpty().lowercase(Locale.US)
        val fallback = fallbackFor(lang)
        val found = try {
            val d = UniversalDetector(null)
            d.handleData(sample, 0, sample.size)
            d.dataEnd()
            d.detectedCharset
        } catch (_: Throwable) {
            null
        }
        val latin = found == null || found.equals("WINDOWS-1252", true) || found.equals("ISO-8859-1", true)
        val name = when {
            // Lower-case Cyrillic is the same in both: a .txt file is almost never Mac Cyrillic
            found.equals("MACCYRILLIC", true) -> "windows-1251"
            !latin -> found!!
            fallback == "windows-1250" || looksCentralEuropean(sample) -> "windows-1250"
            else -> found ?: fallback
        }
        return if (isSupported(name)) name else "windows-1252"
    }

    /**
     * True when [sample] reads as words in windows-1250 but not in windows-1252. Polish as windows-1252 has symbols
     * inside words ("Za¿ó³æ" for "Zażółć": ż, ł, ą are ¿, ³, ¹ there); French or Spanish has none.
     */
    fun looksCentralEuropean(sample: ByteArray): Boolean {
        val western = inWordSymbols(String(sample, Charset.forName("windows-1252")))
        if (western < 3) return false
        return inWordSymbols(String(sample, Charset.forName("windows-1250"))) * 4 < western
    }

    /** Non-ASCII characters that are not letters, between two letters. */
    internal fun inWordSymbols(text: String): Int {
        var n = 0
        for (i in 1 until text.length - 1) {
            val c = text[i]
            if (c.code < 0x80 || Character.isLetter(c)) continue
            if (Character.isLetter(text[i - 1]) && Character.isLetter(text[i + 1])) n++
        }
        return n
    }

    /** The legacy codepage of text written in the UI language. */
    fun fallbackFor(language: String): String = when {
        language.startsWith("polish") || language.startsWith("czech") || language.startsWith("slovak") ||
            language.startsWith("hungarian") || language.startsWith("croatian") || language.startsWith("slovenian") -> "windows-1250"
        language.startsWith("russian") || language.startsWith("ukrainian") || language.startsWith("bulgarian") ||
            language.startsWith("serbian") -> "windows-1251"
        language.startsWith("chinese") -> "GBK"
        language.startsWith("japanese") -> "Shift_JIS"
        language.startsWith("korean") -> "EUC-KR"
        else -> "windows-1252"
    }

    /**
     * True when [bytes] are valid UTF-8. When the sample is not the [complete] file, a character that the end of
     * the sample cuts counts as valid: a lead byte and fewer continuation bytes than it needs.
     */
    fun isUtf8(bytes: ByteArray, complete: Boolean = false): Boolean {
        var end = bytes.size
        if (!complete) {
            var back = 0
            while (back < 3 && end - back - 1 >= 0 && (bytes[end - back - 1].toInt() and 0xC0) == 0x80) back++
            val leadAt = end - back - 1
            if (leadAt >= 0) {
                val lead = bytes[leadAt].toInt() and 0xFF
                val needs = when {
                    lead in 0xC2..0xDF -> 1
                    lead in 0xE0..0xEF -> 2
                    lead in 0xF0..0xF4 -> 3
                    else -> 0
                }
                if (needs > back) end = leadAt
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, end))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun isSupported(name: String): Boolean = try {
        Charset.isSupported(name)
    } catch (_: Exception) {
        false
    }
}

/**
 * A .txt book cut into sections of about [TARGET_BYTES] at line ends (a blank line when one is near), so a large file
 * is laid out a part at a time. [starts] has the byte offset of each section and the file size at the end.
 * [headings] are the chapter lines that [TxtFormatter] found, in all sections.
 */
class TxtIndex(
    val charset: String,
    val starts: LongArray,
    val headings: List<TocEntry>,
    val fileSize: Long,
    val modified: Long
) {
    val sectionCount: Int get() = starts.size - 1

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("charset", charset)
        put("size", fileSize)
        put("modified", modified)
        put("starts", JSONArray().apply { for (s in starts) put(s) })
        put("headings", JSONArray().apply {
            for (h in headings) put(JSONObject().put("t", h.title).put("s", h.section).put("o", h.offset))
        })
    }

    companion object {
        /** 3: cuts at whole characters in long lines (GBK, UTF-16); 2: Central European text with a Western UI. */
        const val VERSION = 3
        const val TARGET_BYTES = 24 * 1024

        /** The index in [json] if it is for this [file] (same size and time) and this format version. */
        fun fromJson(json: JSONObject, file: File): TxtIndex? = try {
            if (json.optInt("v") != VERSION || json.getLong("size") != file.length() || json.getLong("modified") != file.lastModified()) null
            else {
                val s = json.getJSONArray("starts")
                val h = json.getJSONArray("headings")
                TxtIndex(
                    json.getString("charset"),
                    LongArray(s.length()) { s.getLong(it) },
                    (0 until h.length()).map { i ->
                        val o = h.getJSONObject(i)
                        TocEntry(o.getString("t"), 0, o.getInt("s"), null, o.getInt("o"))
                    },
                    file.length(), file.lastModified()
                )
            }
        } catch (_: Exception) {
            null
        }

        /**
         * Reads [file] once: finds its charset ([language]: the UI language file name), the section cuts and the
         * chapter headings. [cancelled] stops the work (the book closed).
         */
        fun build(file: File, language: String?, cancelled: () -> Boolean = { false }): TxtIndex {
            val size = file.length()
            val sample = ByteArray(minOf(size, 64 * 1024L).toInt())
            FileInputStream(file).use { input ->
                var n = 0
                while (n < sample.size) {
                    val r = input.read(sample, n, sample.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            val charset = TextEncoding.detect(sample, language, complete = sample.size.toLong() == size)
            val bomLength = TextEncoding.bom(sample)?.second ?: 0
            val unit = if (charset.startsWith("UTF-16", ignoreCase = true)) 2 else 1
            val bigEndian = charset.equals("UTF-16BE", ignoreCase = true) || charset.equals("UTF-16", ignoreCase = true)
            val cs = Charset.forName(charset)
            val starts = cuts(file, bomLength.toLong(), size, unit, bigEndian, cs)
            val headings = ArrayList<TocEntry>()
            for (i in 0 until starts.size - 1) {
                if (cancelled()) break
                val text = TxtFormatter.format(readSection(file, starts[i], starts[i + 1], cs))
                for (h in text.headings) headings += TocEntry(h.title, 0, i, null, h.offset)
            }
            return TxtIndex(charset, starts, headings, size, file.lastModified())
        }

        /** The section starts: after a newline once a section has [TARGET_BYTES], a blank line when one comes soon. */
        internal fun cuts(file: File, from: Long, size: Long, unit: Int, bigEndian: Boolean, charset: Charset): LongArray {
            val out = ArrayList<Long>()
            out += from
            RandomAccessFile(file, "r").use { raf ->
                var start = from
                val buf = ByteArray(TARGET_BYTES + SEARCH_BYTES)
                while (size - start > TARGET_BYTES + SEARCH_BYTES / 2) {
                    raf.seek(start)
                    var n = 0
                    while (n < buf.size) {
                        val r = raf.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    val cut = cutIn(buf, n, unit, bigEndian, charset)
                    start += cut
                    out += start
                }
            }
            out += size
            return out.toLongArray()
        }

        /** Where to end a section in [buf] (its first [n] bytes): an offset after a newline, at a character start. */
        internal fun cutIn(buf: ByteArray, n: Int, unit: Int, bigEndian: Boolean, charset: Charset): Int {
            fun isNewline(i: Int): Boolean = if (unit == 1) buf[i] == NL else {
                if (bigEndian) buf[i] == 0.toByte() && buf[i + 1] == NL else buf[i] == NL && buf[i + 1] == 0.toByte()
            }
            val limit = n - unit
            var firstNewline = -1
            var i = TARGET_BYTES - TARGET_BYTES % unit
            while (i <= limit) {
                if (isNewline(i)) {
                    if (firstNewline < 0) firstNewline = i + unit
                    // A blank line: \n\n or \n\r\n
                    var j = i + unit
                    if (j <= limit && unit == 1 && buf[j] == CR) j++
                    if (j <= limit && isNewline(j)) return j + unit
                    if (i - TARGET_BYTES > SEARCH_BYTES / 2 && firstNewline > 0) return firstNewline
                }
                i += unit
            }
            if (firstNewline > 0) return firstNewline
            return characterCut(buf, TARGET_BYTES - TARGET_BYTES % unit, unit, bigEndian, charset)
        }

        /**
         * One very long line: a cut at or before [at] after a whole character of [charset] (a GBK or Shift_JIS
         * character has two bytes, a UTF-16 emoji two code units).
         */
        internal fun characterCut(buf: ByteArray, at: Int, unit: Int, bigEndian: Boolean, charset: Charset): Int {
            if (charset == Charsets.UTF_8) {
                var cut = at
                while (cut < buf.size && (buf[cut].toInt() and 0xC0) == 0x80) cut++
                return cut
            }
            var cut = at
            if (unit == 1) {
                // The decoder takes whole characters only, and leaves a character that [at] cuts
                val input = ByteBuffer.wrap(buf, 0, at)
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                decoder.decode(input, java.nio.CharBuffer.allocate(at), false)
                if (input.position() > 0) cut = input.position()
            } else if (cut >= 2) {
                // Not between the two halves of a surrogate pair
                val unitBefore = if (bigEndian) ((buf[cut - 2].toInt() and 0xFF) shl 8) or (buf[cut - 1].toInt() and 0xFF)
                else ((buf[cut - 1].toInt() and 0xFF) shl 8) or (buf[cut - 2].toInt() and 0xFF)
                if (Character.isHighSurrogate(unitBefore.toChar())) cut += 2
            }
            return cut
        }

        fun readSection(file: File, from: Long, to: Long, charset: Charset): String {
            val bytes = ByteArray((to - from).toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                raf.readFully(bytes)
            }
            return String(bytes, charset)
        }

        private const val SEARCH_BYTES = 8 * 1024
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}

/**
 * Plain text as [StyledText]: paragraphs, and chapter lines as headings. Hard-wrapped text (Project Gutenberg:
 * lines of about 70 characters, blank lines between paragraphs) is joined back into paragraphs.
 */
object TxtFormatter {

    fun format(raw: String): StyledText {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val wrap = wrapWidth(lines)
        val sb = StringBuilder()
        val runs = ArrayList<StyledText.Run>()
        val headings = ArrayList<StyledText.Heading>()
        val para = StringBuilder()
        var blankRun = 0

        fun endParagraph() {
            val text = para.toString().trim()
            para.setLength(0)
            if (text.isEmpty()) return
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            sb.append(text)
            if (isHeading(text)) {
                runs += StyledText.Run(StyledText.Run.HEADING, start, sb.length, 2)
                headings += StyledText.Heading(text, start)
            } else {
                runs += StyledText.Run(StyledText.Run.PARAGRAPH, start, sb.length)
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                endParagraph()
                blankRun++
                continue
            }
            // Two or more blank lines: a scene break shows as an empty line
            if (blankRun >= 2 && sb.isNotEmpty()) sb.append('\n')
            blankRun = 0
            if (wrap == 0) {
                para.append(trimmed)
                endParagraph()
                continue
            }
            val indented = line.startsWith("  ") || line.startsWith("\t") || line.startsWith("　")
            if (para.isNotEmpty() && (indented || isHeading(trimmed))) endParagraph()
            if (para.isNotEmpty()) para.append(' ')
            para.append(trimmed)
            // A line much shorter than the wrap width ends its paragraph
            if (line.trimEnd().length < wrap * 6 / 10 || isHeading(trimmed)) endParagraph()
        }
        endParagraph()
        return StyledText(sb.toString(), runs, headings = headings)
    }

    /**
     * The wrap width of hard-wrapped text (most lines end near one width), or 0 when each line is a paragraph.
     */
    internal fun wrapWidth(lines: List<String>): Int {
        val lengths = lines.asSequence().map { it.trimEnd().length }.filter { it > 0 }.toList()
        if (lengths.size < 8) return 0
        val sorted = lengths.sorted()
        val p90 = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
        if (p90 !in 40..120) return 0
        val near = lengths.count { it >= p90 * 7 / 10 && it <= p90 + 2 }
        return if (near * 10 >= lengths.size * 5) p90 else 0
    }

    /**
     * A short line that names a chapter: a chapter word and a number ("Chapter 12", "ROZDZIAŁ II. Powrót",
     * "Глава 3"), the word alone ("Prologue"), a roman numeral alone ("IV."), or a CJK chapter ("第三章").
     * "Part of the plan" is not one: after the word comes a number, then the end, punctuation or a capital.
     */
    fun isHeading(line: String): Boolean {
        if (line.length > 60) return false
        if (CJK.containsMatchIn(line) || ROMAN.matches(line)) return true
        val m = KEYWORD.find(line.lowercase(Locale.ROOT)) ?: return false
        val rest = line.substring(m.range.last + 1).trim()
        if (rest.isEmpty() || rest == "." || rest == ":") return true
        val num = NUMBER.find(rest) ?: return false
        val after = rest.substring(num.range.last + 1).trim()
        if (after.isEmpty()) return true
        val c = after[0]
        return c in ".:)-—–\"“«„" || c.isUpperCase() || c.isDigit()
    }

    private val KEYWORD = Regex(
        "^(chapter|part|book|prologue|epilogue|interlude|rozdział|rozdzial|część|czesc|prolog|epilog|tom|" +
            "глава|часть|пролог|эпилог|книга|capítulo|capitulo|parte|kapitel|teil|chapitre|partie|capitolo|hoofdstuk)\\b"
    )
    private val NUMBER = Regex("^([0-9]{1,4}|[ivxlcdmIVXLCDM]{1,8})(?=$|[\\s.:)\\-—–])")
    private val CJK = Regex("^第[0-9一二三四五六七八九十百千零〇]+[章回节節卷部話话]")

    /** A roman numeral alone ("IV", "XII."). */
    private val ROMAN = Regex("^[IVXLC]{1,7}\\.?$")
}
