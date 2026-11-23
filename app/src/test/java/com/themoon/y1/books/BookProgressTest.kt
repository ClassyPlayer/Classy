package com.themoon.y1.books

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookProgressTest {

    private class Lines(val heights: IntArray) : LineSource {
        private val tops = IntArray(heights.size + 1).also { for (i in heights.indices) it[i + 1] = it[i] + heights[i] }
        override val lineCount: Int get() = heights.size
        override fun top(line: Int) = tops[line]
        override fun bottom(line: Int) = tops[line + 1]
    }

    @Test
    fun pagesHoldWholeLines() {
        val lines = Lines(IntArray(10) { 30 })
        assertArrayEquals(intArrayOf(0, 3, 6, 9), PageMath.pageStarts(lines, 100))
        assertEquals(1, PageMath.pageOf(intArrayOf(0, 3, 6, 9), 5))
        assertEquals(3, PageMath.pageOf(intArrayOf(0, 3, 6, 9), 9))
        assertEquals(0, PageMath.pageOf(intArrayOf(0, 3, 6, 9), 0))
    }

    @Test
    fun aLineTallerThanThePageHasItsOwnPage() {
        val lines = Lines(intArrayOf(30, 500, 30, 30))
        assertArrayEquals(intArrayOf(0, 1, 2), PageMath.pageStarts(lines, 100))
        assertArrayEquals(intArrayOf(0), PageMath.pageStarts(Lines(IntArray(0)), 100))
    }

    @Test
    fun partsEndAtParagraphs() {
        val text = ("a".repeat(70) + "\n").repeat(10)
        val starts = PageMath.partStarts(text, 160)
        assertEquals(0, starts[0])
        for (s in starts.drop(1)) assertEquals('\n', text[s - 1])
        assertTrue(starts.toList().zipWithNext().all { (a, b) -> b - a <= 160 })
        assertEquals(1, PageMath.partOf(starts, starts[1]))
        assertArrayEquals(intArrayOf(0), PageMath.partStarts("short", 200))
        // No paragraph end: a hard cut
        assertArrayEquals(intArrayOf(0, 200, 400), PageMath.partStarts("x".repeat(450), 200))
    }

    @Test
    fun savedPosition() {
        val s = SavedPosition(BookPosition(3, 1200), 420)
        val back = SavedPosition.decode(s.encode())!!
        assertEquals(BookPosition(3, 1200), back.position)
        assertEquals(420, back.permille)
        assertEquals(BookPosition(1, 2), SavedPosition.decode("1:2")!!.position)
        assertNull(SavedPosition.decode("x:1:2"))
        assertNull(SavedPosition.decode(null))
        assertNull(SavedPosition.decode("-1:0:0"))
    }

    @Test
    fun permille() {
        val w = longArrayOf(100, 300, 600)
        assertEquals(0, Progress.permille(w, BookPosition(0, 0), 50))
        assertEquals(100, Progress.permille(w, BookPosition(1, 0), 10))
        assertEquals(250, Progress.permille(w, BookPosition(1, 5), 10))
        assertEquals(1000, Progress.permille(w, BookPosition(2, 10), 10))
        assertEquals(1 to 0.5, Progress.at(w, 250))
        assertEquals(0 to 0.0, Progress.at(w, 0))
        assertEquals(2, Progress.at(w, 1000).first)
        assertEquals("42%", Progress.percentText(425))
        assertEquals("99%", Progress.percentText(999))
    }

    @Test
    fun bookmarks() {
        val a = Bookmark(BookPosition(2, 10), 300, "Later", "Two", 5)
        val b = Bookmark(BookPosition(0, 4), 10, "Early", "One", 6)
        val marks = Bookmark.add(Bookmark.add(emptyList(), a), b)
        assertEquals(listOf("Early", "Later"), marks.map { it.snippet })
        val back = Bookmark.fromJson(Bookmark.toJson(marks))
        assertEquals(marks.map { it.position }, back.map { it.position })
        assertEquals("Two", back[1].chapter)
        // The same place again replaces the mark
        assertEquals(2, Bookmark.add(marks, Bookmark(BookPosition(2, 10), 300, "New", "Two", 7)).size)
        assertEquals(listOf("Later"), Bookmark.remove(marks, BookPosition(0, 4)).map { it.snippet })
        assertTrue(Bookmark.fromJson("not json").isEmpty())
        assertTrue(Bookmark.fromJson("[{\"s\":-1}]").isEmpty())
        assertEquals("one two…", Bookmark.snippet("  one\n two three", 7))
    }

    @Test
    fun chapters() {
        val toc = listOf(
            TocEntry("One", 0, 0, null),
            TocEntry("One b", 1, 0, "b"),
            TocEntry("Two", 0, 1, null),
            TocEntry("Three", 0, 2, "late")
        )
        val places = ChapterNav.places(toc) { if (it == 0) mapOf("b" to 500) else null }
        assertEquals(listOf(BookPosition(0, 0), BookPosition(0, 500), BookPosition(1, 0), BookPosition(2, 0)), places)
        assertEquals(1, ChapterNav.current(places, BookPosition(0, 600)))
        assertEquals(0, ChapterNav.current(places, BookPosition(0, 499)))
        assertEquals(2, ChapterNav.next(places, BookPosition(0, 600)))
        assertEquals(-1, ChapterNav.next(places, BookPosition(2, 0)))
        // Mid chapter: back to its start; at its start: the chapter before
        assertEquals(1, ChapterNav.previous(places, BookPosition(0, 700)))
        assertEquals(0, ChapterNav.previous(places, BookPosition(0, 500)))
        assertEquals(-1, ChapterNav.previous(places, BookPosition(0, 0)))
    }
}
