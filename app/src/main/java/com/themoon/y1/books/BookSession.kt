package com.themoon.y1.books

import android.os.Handler
import android.os.Looper
import android.text.Spanned
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * An open book while it is read: its [source], its [images], and the laid-out parts of its sections. One worker
 * thread converts and lays out the text; the results come back on the main thread. A section longer than
 * [MAX_PART_CHARS] is laid out a part at a time.
 */
class BookSession(val file: File, val source: BookSource) {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "BookLayout") }

    /** Runs on the main thread when an image that was drawn missing is ready. */
    var onImageReady: (() -> Unit)? = null
    val images = BookImages(source) { onImageReady?.invoke() }

    @Volatile
    private var style: PageStyle? = null

    /** Changes with [setStyle]: a part laid out for an older style is not used. */
    @Volatile
    var styleGeneration = 0
        private set

    @Volatile
    private var closed = false

    // Worker thread only
    /** In access order: the least recently used section goes first. */
    private val sections = LinkedHashMap<Int, Section>(4, 0.75f, true)
    private val parts = LinkedHashMap<Long, LaidOut>()

    /** The anchors (TOC fragment ids) and char lengths of the sections read so far. */
    private val anchors = ConcurrentHashMap<Int, Map<String, Int>>()
    private val lengths = ConcurrentHashMap<Int, Int>()

    val sectionCount: Int get() = source.sectionCount

    private class Section(val length: Int, val spanned: Spanned, val starts: IntArray)

    fun setStyle(s: PageStyle) {
        if (s == style) return
        style = s
        styleGeneration++
        worker.execute {
            sections.clear()
            parts.clear()
        }
    }

    fun anchorsOf(section: Int): Map<String, Int>? = anchors[section]

    /** The char length of [section] when it was read (else 0). */
    fun lengthOf(section: Int): Int = lengths[section] ?: 0

    /**
     * Lays out the part of [section] that has [offset] (Int.MAX_VALUE: its last part) and gives it to [done] on the
     * main thread (null: the book closed or the section failed).
     */
    fun load(section: Int, offset: Int, done: (LaidOut?) -> Unit) {
        val gen = styleGeneration
        worker.execute {
            val result = if (closed) null else try {
                laidOut(section, gen) { starts -> PageMath.partOf(starts, offset) }
            } catch (_: Throwable) {
                null
            }
            main.post { done(result) }
        }
    }

    /** As [load], at a fraction (0..1) of [section] (Go to Position). */
    fun loadFraction(section: Int, fraction: Double, done: (LaidOut?, Int) -> Unit) {
        val gen = styleGeneration
        worker.execute {
            var target = 0
            val result = if (closed) null else try {
                val sec = section(section, gen)
                target = (sec.length * fraction).toInt()
                laidOut(section, gen) { starts -> PageMath.partOf(starts, target) }
            } catch (_: Throwable) {
                null
            }
            main.post { done(result, target) }
        }
    }

    /** As [load], for part [part] of [section] (the next or previous part). */
    fun loadPart(section: Int, part: Int, done: (LaidOut?) -> Unit) {
        val gen = styleGeneration
        worker.execute {
            val result = if (closed) null else try {
                laidOut(section, gen) { starts -> part.coerceIn(0, starts.size - 1) }
            } catch (_: Throwable) {
                null
            }
            main.post { done(result) }
        }
    }

    /** Lays out a part now on the worker, so the next page turn has it ready. */
    fun prefetch(section: Int, part: Int) {
        if (section !in 0 until sectionCount) return
        val gen = styleGeneration
        worker.execute {
            if (!closed) try {
                laidOut(section, gen) { starts -> part.coerceIn(0, starts.size - 1) }
            } catch (_: Throwable) {}
        }
    }

    /** Reads [section] on the worker (its anchors for the chapter list), then [done] on the main thread. */
    fun readSection(section: Int, done: () -> Unit) {
        val gen = styleGeneration
        worker.execute {
            if (!closed) try {
                section(section, gen)
            } catch (_: Throwable) {}
            main.post(done)
        }
    }

    private fun laidOut(index: Int, gen: Int, pickPart: (IntArray) -> Int): LaidOut? {
        val st = style ?: return null
        val sec = section(index, gen)
        val part = pickPart(sec.starts)
        val key = index.toLong() shl 20 or part.toLong()
        parts[key]?.let {
            // Most recently used last
            parts.remove(key)
            parts[key] = it
            return it
        }
        val start = sec.starts[part]
        val end = if (part + 1 < sec.starts.size) sec.starts[part + 1] else sec.length
        var text: CharSequence = sec.spanned.subSequence(start, end)
        // A part that ends a paragraph ends with '\n': without it the layout has no empty line at the end
        if (text.isNotEmpty() && text[text.length - 1] == '\n') text = text.subSequence(0, text.length - 1)
        val (layout, paint) = SpannedFactory.layout(text, st)
        val pages = PageMath.pageStarts(object : LineSource {
            override val lineCount: Int get() = layout.lineCount
            override fun top(line: Int) = layout.getLineTop(line)
            override fun bottom(line: Int) = layout.getLineBottom(line)
        }, st.height)
        val out = LaidOut(index, part, sec.starts.size, start, sec.length, layout, paint, pages)
        parts[key] = out
        while (parts.size > MAX_PARTS) parts.remove(parts.keys.first())
        return out
    }

    private fun section(index: Int, @Suppress("UNUSED_PARAMETER") gen: Int): Section {
        sections[index]?.let { return it }
        val st = style ?: throw IllegalStateException("No style")
        val text = source.section(index)
        anchors[index] = text.anchors
        lengths[index] = text.length
        val spanned = SpannedFactory.build(text, st, images)
        val sec = Section(text.length, spanned, PageMath.partStarts(text.text, MAX_PART_CHARS))
        sections[index] = sec
        while (sections.size > MAX_SECTIONS) sections.remove(sections.keys.first())
        return sec
    }

    fun close() {
        closed = true
        worker.shutdownNow()
        images.close()
        source.close()
    }

    companion object {
        /** A part this long lays out in about half a second on the Y2. */
        const val MAX_PART_CHARS = 20_000
        private const val MAX_PARTS = 4
        private const val MAX_SECTIONS = 2
    }
}
