package com.themoon.y1.books

/** The lines of a laid-out text: their tops and bottoms in px (a StaticLayout, or a list in tests). */
interface LineSource {
    val lineCount: Int
    fun top(line: Int): Int
    fun bottom(line: Int): Int
}

/**
 * Pages of a laid-out part, only whole lines on a page. The pages start at the top of the part, so a page has the
 * same lines each time (Back returns to the page before).
 */
object PageMath {

    /** The first line of each page of [lines] for pages [height] px high. A line taller than a page has its own page. */
    fun pageStarts(lines: LineSource, height: Int): IntArray {
        val n = lines.lineCount
        if (n <= 0) return intArrayOf(0)
        val out = IntArrayBuilder()
        var start = 0
        while (start < n) {
            out.add(start)
            start = pageEnd(lines, start, height)
        }
        return out.toArray()
    }

    /** The line after the last whole line that fits from [top] (at least one line). */
    fun pageEnd(lines: LineSource, top: Int, height: Int): Int {
        val n = lines.lineCount
        if (top >= n) return n
        val origin = lines.top(top)
        var end = top + 1
        while (end < n && lines.bottom(end) - origin <= height) end++
        return end
    }

    /** The page that has [line] (the last page start at or before it). */
    fun pageOf(starts: IntArray, line: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= line) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Where a section of [length] chars is cut into parts of at most about [maxChars] for layout: the start offset
     * of each part, at a paragraph end ('\n') when one is in the last quarter of the part.
     */
    fun partStarts(text: CharSequence, maxChars: Int): IntArray {
        val length = text.length
        val out = IntArrayBuilder()
        out.add(0)
        var start = 0
        while (length - start > maxChars) {
            var cut = start + maxChars
            var i = cut
            val min = start + maxChars * 3 / 4
            while (i > min && text[i - 1] != '\n') i--
            if (i > min) cut = i
            out.add(cut)
            start = cut
        }
        return out.toArray()
    }

    /** The part of [offset] in [starts] (from [partStarts]). */
    fun partOf(starts: IntArray, offset: Int): Int = pageOf(starts, offset)

    private class IntArrayBuilder {
        private var a = IntArray(16)
        private var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray(): IntArray = a.copyOf(n)
    }
}
