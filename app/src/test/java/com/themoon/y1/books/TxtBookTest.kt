package com.themoon.y1.books

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

class TxtBookTest {
    private val files = ArrayList<File>()

    @After
    fun cleanUp() {
        files.forEach { it.deleteRecursively() }
    }

    private fun temp(bytes: ByteArray): File = File.createTempFile("book", ".txt").also {
        files += it
        it.writeBytes(bytes)
    }

    @Test
    fun encodings() {
        assertEquals("UTF-8", TextEncoding.detect(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte()), null))
        assertEquals("UTF-16LE", TextEncoding.detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'a'.code.toByte(), 0), null))
        assertEquals("UTF-8", TextEncoding.detect("Zażółć gęślą jaźń".toByteArray(Charsets.UTF_8), null))
        val polish = "Zażółć gęślą jaźń. Pchnąć w tę łódź jeża lub ośm skrzyń fig. ".repeat(20).toByteArray(charset("windows-1250"))
        assertEquals("windows-1250", TextEncoding.detect(polish, "polish.json"))
        // Also with an English UI: the text has symbols inside words as windows-1252
        assertEquals("windows-1250", TextEncoding.detect(polish, "english.json"))
        val spanish = "¿Qué pasó? El niño comió una piña. Ça va très bien, garçon, à l'école. Größe, Fräulein. ".repeat(20)
            .toByteArray(charset("windows-1252"))
        assertEquals("WINDOWS-1252", TextEncoding.detect(spanish, "english.json").uppercase())
        val russian = "Съешь же ещё этих мягких французских булок, да выпей чаю. В чащах юга жил бы цитрус? ".repeat(20)
            .toByteArray(charset("windows-1251"))
        assertEquals("WINDOWS-1251", TextEncoding.detect(russian, "english.json").uppercase())
    }

    @Test
    fun utf8CutAtTheEndOfTheSampleIsStillUtf8() {
        val bytes = "abcó".toByteArray(Charsets.UTF_8)
        assertTrue(TextEncoding.isUtf8(bytes.copyOf(bytes.size - 1)))
        assertFalse(TextEncoding.isUtf8(byteArrayOf('a'.code.toByte(), 0xF3.toByte(), 'b'.code.toByte())))
        // A whole file that ends in a Latin-1 byte is not UTF-8
        val cafe = "Café".toByteArray(charset("windows-1252"))
        assertFalse(TextEncoding.isUtf8(cafe, complete = true))
        assertTrue(TextEncoding.detect(cafe, "english.json", complete = true) != "UTF-8")
        // ...and a lone byte that cannot start a UTF-8 character is not a cut character either
        assertFalse(TextEncoding.isUtf8(byteArrayOf('a'.code.toByte(), 0xFF.toByte())))
    }

    @Test
    fun longLinesAreCutAtWholeCharacters() {
        // GBK: "a" then two-byte characters, no newline: the cut must not split one
        val gbk = charset("GBK")
        val text = "a" + "你".repeat(20_000)
        val bytes = text.toByteArray(gbk)
        val cut = TxtIndex.cutIn(bytes, bytes.size, 1, false, gbk)
        assertEquals(text.substring(0, (cut - 1) / 2 + 1), String(bytes, 0, cut, gbk))
        // UTF-16LE: an emoji (a surrogate pair) on the cut moves the cut past it
        val emoji = "x".repeat(TxtIndex.TARGET_BYTES / 2 - 1) + "\uD83D\uDE00" + "y".repeat(10_000)
        val u16 = emoji.toByteArray(Charsets.UTF_16LE)
        val c16 = TxtIndex.cutIn(u16, u16.size, 2, false, Charsets.UTF_16LE)
        assertFalse(Character.isHighSurrogate(String(u16, 0, c16, Charsets.UTF_16LE).last()))
    }

    @Test
    fun gutenbergHardWrappedTextJoinsBack() {
        val raw = """
            CHAPTER I. THE BEGINNING

            It was a bright cold day in April, and the clocks were striking thirteen.
            Winston Smith, his chin nuzzled into his breast in an effort to escape the
            vile wind, slipped quickly through the glass doors of Victory Mansions.

            The hallway smelt of boiled cabbage and old rag mats. At one end of it a
            coloured poster, too large for indoor display, had been tacked to the wall.
            It depicted simply an enormous face, more than a metre wide: the face of a
            man of about forty-five, with a heavy black moustache and ruggedly handsome
            features. Winston made for the stairs. It was no use trying the lift.
        """.trimIndent()
        val t = TxtFormatter.format(raw)
        val paras = t.text.split('\n')
        assertEquals("CHAPTER I. THE BEGINNING", paras[0])
        assertEquals(3, paras.size)
        assertTrue(paras[1].startsWith("It was a bright") && paras[1].endsWith("Victory Mansions."))
        assertEquals(listOf("CHAPTER I. THE BEGINNING"), t.headings.map { it.title })
        assertEquals(StyledText.Run.HEADING, t.runs.first().kind)
    }

    @Test
    fun oneLinePerParagraph() {
        val raw = "Глава 1\r\n    Первый абзац текста.\r\n    Второй абзац, тоже длинный.\r\n\r\n\r\nПосле разрыва."
        val t = TxtFormatter.format(raw)
        assertEquals("Глава 1\nПервый абзац текста.\nВторой абзац, тоже длинный.\n\nПосле разрыва.", t.text)
        assertEquals(listOf("Глава 1"), t.headings.map { it.title })
    }

    @Test
    fun headings() {
        for (h in listOf("Chapter 12", "CHAPTER IV", "Rozdział 3. Powrót", "ROZDZIAŁ II", "Глава 3", "Prologue", "IV.", "第三章 归来", "Part 2: Night")) {
            assertTrue(h, TxtFormatter.isHeading(h))
        }
        for (h in listOf("Part of the plan was simple.", "Chapter did not end", "Part civil war", "Bookkeeping", "It was IV o'clock")) {
            assertFalse(h, TxtFormatter.isHeading(h))
        }
    }

    @Test
    fun largeFileIsCutAtParagraphEndsAndReadsBack() {
        val para = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(12)
        val text = (1..300).joinToString("\n\n") { if (it % 50 == 1) "Chapter ${it / 50 + 1}" else "$it $para" }
        val f = temp(text.toByteArray(Charsets.UTF_8))
        val cache = File(f.parentFile, "cache_" + f.name).also { files += it }
        val index = BookOpener.txtIndex(f, cache, "english.json")
        assertEquals("UTF-8", index.charset)
        assertTrue(index.sectionCount > 3)
        assertEquals(f.length(), index.starts.last())
        val source = TxtSource(f, index)
        val joined = (0 until source.sectionCount).joinToString("\n") { source.section(it).text }
        assertEquals(TxtFormatter.format(text).text, joined)
        assertEquals((1..6).map { "Chapter $it" }, index.headings.map { it.title })
        // Each section after the first starts at a paragraph
        for (i in 1 until source.sectionCount) assertFalse(source.section(i).text.startsWith(" "))
        // The cached index is used again
        val again = BookOpener.txtIndex(f, cache, "english.json")
        assertEquals(index.starts.toList(), again.starts.toList())
        assertNotNull(TxtIndex.fromJson(JSONObject(index.toJson().toString()), f))
    }

    @Test
    fun utf16FileIsCutAtCharacters() {
        val text = (1..3000).joinToString("\n") { "Line $it with some text in it" }
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val f = temp(bytes)
        val index = TxtIndex.build(f, null)
        assertEquals("UTF-16LE", index.charset)
        assertEquals(2L, index.starts[0])
        for (s in index.starts) assertEquals(0L, s % 2)
        val source = TxtSource(f, index)
        val joined = (0 until source.sectionCount).joinToString("\n") { source.section(it).text }
        assertEquals(TxtFormatter.format(text).text, joined)
    }
}
