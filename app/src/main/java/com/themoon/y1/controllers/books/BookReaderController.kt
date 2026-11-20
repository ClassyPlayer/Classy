package com.themoon.y1.controllers.books

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Xml
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.themoon.y1.Prefs
import com.themoon.y1.books.BookOpener
import com.themoon.y1.books.BookPosition
import com.themoon.y1.books.BookSession
import com.themoon.y1.books.Bookmark
import com.themoon.y1.books.ChapterNav
import com.themoon.y1.books.DrmException
import com.themoon.y1.books.LaidOut
import com.themoon.y1.books.PageMath
import com.themoon.y1.books.PageStyle
import com.themoon.y1.books.Progress
import com.themoon.y1.books.SavedPosition
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.player.LyricsScreenHold
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.ClassicMenuStyle
import java.io.File

/**
 * Books → a book: the page ([ReaderView]) under the status bar. The wheel turns pages (or moves a line, Settings →
 * Books → Wheel), Next/Previous turn pages and a hold of them jumps a chapter, the centre button opens the book
 * menu ([BooksController], [com.themoon.y1.navigation.BooksLevel.ReaderMenu]), and Back closes the book.
 * Play/Pause stays with the music.
 *
 * The open book ([session]) stays open while its menu shows; it closes when another screen shows.
 * The reading place is saved at each page ([Prefs.EBOOK_POSITION]).
 */
class BookReaderController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val prefs: SharedPreferences,
    private val onOpenMenu: (File) -> Unit,
    private val onLeave: () -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val view = ReaderView(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val screenHold = LyricsScreenHold(activity, "Y1:BookReader")

    /** The open book, or null. */
    var session: BookSession? = null
        private set
    private var opening: File? = null
    /** Changes at each open and close: an open that is not the last one is thrown away. */
    @Volatile
    private var openToken = 0
    /** Changes at each jump (chapter, place, bookmark): a late anchor of an older jump is not shown. */
    private var jumpGeneration = 0
    private var shown = false
    /** MainActivity is in the background (screen off): the screen hold stays released. */
    private var paused = false
    private var busy = false

    /** A place to show when the reader shows again (a jump from the book menu). */
    private var pendingJump: BookPosition? = null
    private var pendingPermille = -1

    private var wheelSum = 0
    private var mediaDownAt = 0L
    private var mediaHeld = false
    private var lastInputMs = 0L

    private var places: List<BookPosition> = emptyList()
    private var placesKnown = -1

    init {
        rootView.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private val holdTask = object : Runnable {
        override fun run() {
            val keep = shown && !paused && ReaderSettings.keepScreenOn(prefs) && SystemClock.uptimeMillis() - lastInputMs < IDLE_MS
            screenHold.update(keep)
            if (shown) handler.postDelayed(this, HOLD_TICK_MS)
        }
    }

    override fun title(): String? = session?.source?.title?.ifEmpty { null } ?: opening?.let { com.themoon.y1.books.BookTitles.fromFileName(it) }

    override fun onEnter(state: ScreenState) {
        val file = (state as? ScreenState.BookReader)?.file ?: return
        rootView.visibility = View.VISIBLE
        rootView.setPadding(0, (ClassicMenuStyle.STATUS_HEIGHT_DP * activity.resources.displayMetrics.density).toInt(), 0, 0)
        shown = true
        lastInputMs = SystemClock.uptimeMillis()
        handler.removeCallbacks(holdTask)
        handler.post(holdTask)
        applyColors()
        val s = session
        if (s != null && s.file == file) {
            afterSize { resume(s) }
        } else if (opening != file) {
            open(file)
        }
    }

    override fun onExit() {
        savePosition()
        shown = false
        handler.removeCallbacks(holdTask)
        screenHold.update(false)
        rootView.visibility = View.GONE
        // After the change: close the book unless its menu shows now
        handler.post {
            val now = NavigationManager.getInstance().currentState
            if (!isReaderScreen(now)) closeBook()
        }
    }

    override fun onThemeChanged() {
        applyColors()
    }

    /** The page colours, also behind the status bar (the strip above the page). */
    private fun applyColors() {
        val dark = ReaderSettings.isDarkPage(prefs)
        view.setColors(dark, com.themoon.y1.ThemeManager.getCustomFont())
        rootView.setBackgroundColor(if (dark) ReaderColors.DARK_BG else ReaderColors.LIGHT_BG)
    }

    override fun onRefreshUI() {
        onThemeChanged()
    }

    /** MainActivity went to the background (screen off): the place is saved and the screen may sleep. */
    fun onActivityPause() {
        paused = true
        savePosition()
        screenHold.update(false)
    }

    /** MainActivity is on the screen again: the screen hold works again (after the next key). */
    fun onActivityResume() {
        paused = false
        lastInputMs = SystemClock.uptimeMillis()
    }

    /** The card is shared over USB: the book closes (its zip is on the card) and the list shows. */
    fun closeForUsbShare() {
        if (shown) onLeave()
        closeBook()
    }

    fun closeBook() {
        savePosition()
        session?.close()
        session = null
        opening = null
        openToken++
        view.clear()
        placesKnown = -1
    }

    // --- Opening ---

    private fun open(file: File) {
        closeBook()
        opening = file
        val token = ++openToken
        view.clear()
        view.message = localize("Loading...")
        Prefs.LAST_BOOK.setAsync(prefs, file.absolutePath)
        val cacheDir = File(activity.filesDir, "books")
        val language = LanguageManager.getInstance(activity).currentLangFileName
        opensRunning.incrementAndGet()
        Thread({
            val result: Any = try {
                BookOpener.open(file, cacheDir, language, { Xml.newPullParser() }) { openToken != token }
            } catch (e: DrmException) {
                e
            } catch (e: Throwable) {
                e
            }
            if (openToken != token) {
                // Closed meanwhile (another book, a USB share): close its file now, on this thread
                (result as? com.themoon.y1.books.BookSource)?.close()
                opensRunning.decrementAndGet()
                return@Thread
            }
            activity.runOnUiThread {
                opensRunning.decrementAndGet()
                if (openToken != token) {
                    (result as? com.themoon.y1.books.BookSource)?.close()
                    return@runOnUiThread
                }
                opening = null
                when (result) {
                    is com.themoon.y1.books.BookSource -> {
                        val s = BookSession(file, result)
                        s.onImageReady = { view.invalidate() }
                        session = s
                        com.themoon.y1.MainActivity.instance?.refreshStatusBarTitle()
                        afterSize { start(s) }
                    }
                    is DrmException -> view.message = localize("This book is protected (DRM)")
                    else -> view.message = String.format(localize("Could not open %s"), file.name)
                }
            }
        }, "BookOpen").start()
    }

    /** Runs [block] when the page has its size (the first time the reader shows, after its layout). */
    private fun afterSize(block: () -> Unit) {
        if (view.width > 0 && view.height > 0) {
            block()
            return
        }
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (r - l <= 0 || b - t <= 0) return
                v.removeOnLayoutChangeListener(this)
                block()
            }
        })
    }

    private fun currentStyle(): PageStyle {
        val d = activity.resources.displayMetrics.density
        return PageStyle(
            view.pageWidth(), view.pageHeight(), ReaderSettings.fontSizePx(prefs, d), ReaderSettings.lineSpacing(prefs),
            ReaderSettings.typeface(prefs)
        )
    }

    private fun start(s: BookSession) {
        if (s !== session) return
        s.setStyle(currentStyle())
        val saved = SavedPosition.decode(prefs.getString(Prefs.EBOOK_POSITION.key(s.file.absolutePath), null))
        val pos = saved?.position?.takeIf { it.section < s.sectionCount } ?: BookPosition.START
        loadAt(s, pos)
    }

    /** The reader shows again (from its menu): new settings, a jump, or the same page. */
    private fun resume(s: BookSession) {
        if (s !== session) return
        val jump = pendingJump
        val permille = pendingPermille
        pendingJump = null
        pendingPermille = -1
        val before = s.styleGeneration
        s.setStyle(currentStyle())
        val styleChanged = s.styleGeneration != before
        when {
            permille >= 0 -> loadPermille(s, permille)
            jump != null -> loadAt(s, jump)
            styleChanged || view.part == null -> loadAt(s, position() ?: BookPosition.START)
            else -> show(view.part!!, view.topLine)
        }
    }

    private fun loadAt(s: BookSession, pos: BookPosition) {
        busy = true
        s.load(pos.section.coerceIn(0, s.sectionCount - 1), pos.offset) { part ->
            busy = false
            if (s !== session) return@load
            if (part == null) {
                view.message = String.format(localize("Could not open %s"), s.file.name)
                return@load
            }
            if (part.isEmpty) {
                // An empty section (a page break file): the next one with text
                if (part.section + 1 < s.sectionCount) loadAt(s, BookPosition(part.section + 1, 0)) else show(part, 0)
                return@load
            }
            // The page starts at the place (a chapter, a bookmark, the saved page): not at the page that has it
            show(part, part.lineOf(pos.offset))
        }
    }

    private fun loadPermille(s: BookSession, permille: Int) {
        val (section, fraction) = Progress.at(s.source.weights, permille)
        busy = true
        s.loadFraction(section, fraction) { part, offset ->
            busy = false
            if (s !== session || part == null) return@loadFraction
            show(part, part.lineOf(offset))
        }
    }

    // --- Pages ---

    private fun show(part: LaidOut, top: Int) {
        view.show(part, top)
        updateFooter()
        savePosition()
        prefetch(part, top)
    }

    private fun prefetch(part: LaidOut, top: Int) {
        val s = session ?: return
        val page = PageMath.pageOf(part.pages, top)
        if (page < part.pages.size - 3) return
        if (!part.isLastPart) s.prefetch(part.section, part.part + 1) else s.prefetch(part.section + 1, 0)
    }

    /** One step of the wheel or a page of Next/Previous ([byLines] false: a page whatever the Wheel setting). */
    private fun turn(forward: Boolean, byLines: Boolean = false) {
        val p = view.part ?: return
        if (busy) return
        val lines = byLines
        if (forward) {
            if (lines) {
                if (view.pageEnd() < p.lineCount) return show(p, view.topLine + 1)
            } else {
                // On a page of the part (PageMath.pageStarts): the next one. After a jump the page can start
                // elsewhere: then the next page starts at the first line not shown
                val page = PageMath.pageOf(p.pages, view.topLine)
                if (p.pages[page] == view.topLine) {
                    if (page + 1 < p.pages.size) return show(p, p.pages[page + 1])
                } else if (view.pageEnd() < p.lineCount) {
                    return show(p, view.pageEnd())
                }
            }
            nextPart(p)
        } else {
            if (view.topLine > 0) {
                if (lines) return show(p, view.topLine - 1)
                val page = PageMath.pageOf(p.pages, view.topLine)
                return show(p, if (p.pages[page] == view.topLine) p.pages[page - 1] else pageBefore(p, view.topLine))
            }
            previousPart(p, lines)
        }
    }

    private fun nextPart(p: LaidOut) {
        val s = session ?: return
        val (section, part) = when {
            !p.isLastPart -> p.section to p.part + 1
            p.section + 1 < s.sectionCount -> p.section + 1 to 0
            else -> return
        }
        busy = true
        s.loadPart(section, part) { next ->
            busy = false
            if (s !== session || next == null) return@loadPart
            if (next.isEmpty) {
                nextPart(next)
                return@loadPart
            }
            show(next, 0)
        }
    }

    private fun previousPart(p: LaidOut, byLines: Boolean) {
        val s = session ?: return
        val (section, part) = when {
            p.part > 0 -> p.section to p.part - 1
            p.section > 0 -> p.section - 1 to Int.MAX_VALUE
            else -> return
        }
        busy = true
        val done: (LaidOut?) -> Unit = done@{ prev ->
            busy = false
            if (s !== session || prev == null) return@done
            if (prev.isEmpty) {
                previousPart(prev, byLines)
                return@done
            }
            show(prev, lastTop(prev, byLines))
        }
        if (part == Int.MAX_VALUE) s.load(section, Int.MAX_VALUE, done) else s.loadPart(section, part, done)
    }

    /** The first line of the page that ends just before [top]: as many whole lines above it as fit. */
    private fun pageBefore(p: LaidOut, top: Int): Int {
        val h = view.pageHeight()
        var t = top - 1
        while (t > 0 && p.bottom(top - 1) - p.top(t - 1) <= h) t--
        return t
    }

    /** The top of the last page of [p]: its last page (Pages mode), or the lines that end it (Lines mode). */
    private fun lastTop(p: LaidOut, byLines: Boolean): Int {
        if (!byLines) return p.pages[p.lastPage()]
        val h = view.pageHeight()
        val n = p.lineCount
        var t = n - 1
        while (t > 0 && p.bottom(n - 1) - p.top(t - 1) <= h) t--
        return t
    }

    // --- Chapters, places, bookmarks (the book menu) ---

    /** The place at the top of the page. */
    fun position(): BookPosition? {
        val p = view.part ?: return null
        return BookPosition(p.section, p.offsetOf(view.topLine))
    }

    fun permille(): Int {
        val s = session ?: return 0
        val p = view.part ?: return 0
        return Progress.permille(s.source.weights, BookPosition(p.section, p.offsetOf(view.topLine)), p.sectionLength)
    }

    /** The places of the table of contents (anchors of the sections read so far). */
    fun chapterPlaces(): List<BookPosition> {
        val s = session ?: return emptyList()
        var known = 0
        for (i in 0 until s.sectionCount) if (s.anchorsOf(i) != null) known++
        if (known != placesKnown) {
            places = ChapterNav.places(s.source.toc) { s.anchorsOf(it) }
            placesKnown = known
        }
        return places
    }

    fun currentChapter(): Int {
        val pos = position() ?: return -1
        return ChapterNav.current(chapterPlaces(), pos)
    }

    fun nextChapter(): Int {
        val pos = position() ?: return -1
        return ChapterNav.next(chapterPlaces(), pos)
    }

    fun previousChapter(): Int {
        val pos = position() ?: return -1
        return ChapterNav.previous(chapterPlaces(), pos)
    }

    fun chapterTitle(index: Int): String = session?.source?.toc?.getOrNull(index)?.title.orEmpty()

    /** Shows [pos] when the reader shows again (call before going back to it). */
    fun jumpTo(pos: BookPosition) {
        jumpGeneration++
        pendingJump = pos
        pendingPermille = -1
    }

    fun jumpToPermille(permille: Int) {
        jumpGeneration++
        pendingJump = null
        pendingPermille = permille.coerceIn(0, 1000)
    }

    /** Jumps to TOC entry [index]; an anchor in a section not read yet is found when that section is read. */
    fun jumpToChapter(index: Int) {
        val s = session ?: return
        val entry = s.source.toc.getOrNull(index) ?: return
        val anchor = entry.anchor
        if (anchor == null || s.anchorsOf(entry.section) != null) {
            jumpTo(chapterPlaces().getOrNull(index) ?: BookPosition(entry.section, entry.offset))
            return
        }
        jumpTo(BookPosition(entry.section, 0))
        val generation = jumpGeneration
        s.readSection(entry.section) {
            if (session !== s || generation != jumpGeneration) return@readSection
            val offset = s.anchorsOf(entry.section)?.get(anchor) ?: 0
            val target = BookPosition(entry.section, offset)
            if (shown) loadAt(s, target) else {
                pendingJump = target
                pendingPermille = -1
            }
        }
    }

    fun bookmarks(): List<Bookmark> {
        val s = session ?: return emptyList()
        return Bookmark.fromJson(prefs.getString(Prefs.EBOOK_MARKS.key(s.file.absolutePath), null))
    }

    /** Saves the place at the top of the page as a bookmark. False with no page. */
    fun addBookmark(): Boolean {
        val s = session ?: return false
        val p = view.part ?: return false
        val pos = BookPosition(p.section, p.offsetOf(view.topLine))
        val text = p.layout.text
        val from = p.layout.getLineStart(view.topLine)
        val to = p.layout.getLineEnd((view.pageEnd() - 1).coerceAtLeast(view.topLine))
        val snippet = Bookmark.snippet(text.subSequence(from, maxOf(from, to)))
        val chapter = chapterTitle(currentChapter())
        val mark = Bookmark(pos, permille(), snippet, chapter, System.currentTimeMillis())
        prefs.edit().putString(Prefs.EBOOK_MARKS.key(s.file.absolutePath), Bookmark.toJson(Bookmark.add(bookmarks(), mark))).apply()
        return true
    }

    fun removeBookmark(pos: BookPosition) {
        val s = session ?: return
        prefs.edit().putString(Prefs.EBOOK_MARKS.key(s.file.absolutePath), Bookmark.toJson(Bookmark.remove(bookmarks(), pos))).apply()
    }

    private fun savePosition() {
        val s = session ?: return
        val pos = position() ?: return
        prefs.edit().putString(Prefs.EBOOK_POSITION.key(s.file.absolutePath), SavedPosition(pos, permille()).encode()).apply()
    }

    private fun updateFooter() {
        val s = session ?: return
        val c = currentChapter()
        val chapter = if (c >= 0) chapterTitle(c) else s.source.title
        view.setFooter(chapter, Progress.percentText(permille()))
    }

    // --- Keys ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) lastInputMs = SystemClock.uptimeMillis()
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) wheel(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val forward = code == KeyEvent.KEYCODE_MEDIA_NEXT
                if (down) {
                    if (event.repeatCount == 0) {
                        mediaDownAt = event.eventTime
                        mediaHeld = false
                    } else if (!mediaHeld && event.eventTime - mediaDownAt >= HOLD_MS) {
                        mediaHeld = true
                        clickFeedback()
                        chapterJump(forward)
                    }
                } else if (!mediaHeld && !event.isCanceled) {
                    clickFeedback()
                    turn(forward)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!down && !event.isCanceled && session != null && view.part != null) {
                    clickFeedback()
                    savePosition()
                    session?.let { onOpenMenu(it.file) }
                }
                return true
            }
        }
        return false
    }

    private fun wheel(delta: Int) {
        clickFeedback()
        if (ReaderSettings.wheelLines(prefs)) {
            turn(delta > 0, byLines = true)
            return
        }
        if (wheelSum != 0 && (wheelSum > 0) != (delta > 0)) wheelSum = 0
        wheelSum += delta
        if (Math.abs(wheelSum) >= ReaderSettings.wheelClicks(prefs)) {
            val forward = wheelSum > 0
            wheelSum = 0
            turn(forward)
        }
    }

    private fun chapterJump(forward: Boolean) {
        val s = session ?: return
        val i = if (forward) nextChapter() else previousChapter()
        if (i >= 0) {
            jumpToChapter(i)
            pendingJump?.let {
                pendingJump = null
                loadAt(s, it)
            }
            return
        }
        // No table of contents: the next or previous section
        val p = view.part ?: return
        val section = if (forward) p.section + 1 else if (view.topLine > 0 || p.part > 0) p.section else p.section - 1
        if (section in 0 until s.sectionCount) loadAt(s, BookPosition(section, 0))
    }

    companion object {
        /** A hold of Next or Previous jumps a chapter. */
        const val HOLD_MS = 600L
        /** The screen stays on while reading, until this long without a key. */
        const val IDLE_MS = 10 * 60_000L
        private const val HOLD_TICK_MS = 5_000L

        /** Book opens on their worker threads that still hold or may open a book file. */
        private val opensRunning = java.util.concurrent.atomic.AtomicInteger()

        /** Before a USB share: waits up to [timeoutMs] for the book opens to end and close their files. */
        fun awaitBookOpens(timeoutMs: Long): Boolean {
            val end = SystemClock.uptimeMillis() + timeoutMs
            while (opensRunning.get() > 0) {
                if (SystemClock.uptimeMillis() > end) return false
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return true
        }

        fun isReaderScreen(state: ScreenState?): Boolean =
            state is ScreenState.BookReader || (state is ScreenState.Books && state.level.isReaderMenu)
    }
}
