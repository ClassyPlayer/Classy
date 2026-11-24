package com.themoon.y1.controllers.books

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.util.LruCache
import android.util.Xml
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import com.themoon.y1.Prefs
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.books.BookFormat
import com.themoon.y1.books.BookItem
import com.themoon.y1.books.BookLibrary
import com.themoon.y1.books.BookTitles
import com.themoon.y1.books.Progress
import com.themoon.y1.books.SavedPosition
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.settings.SettingRows
import com.themoon.y1.lists.ArtRow
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.Pane
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.lists.RowTexts
import com.themoon.y1.navigation.BooksLevel
import com.themoon.y1.navigation.InputRouter
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.isFolderLevel
import com.themoon.y1.views.ClassyMetrics
import com.themoon.y1.views.EdgeFadeListView
import com.themoon.y1.views.Notice
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.util.concurrent.Executors

/**
 * Main menu → Books: the ebooks in the Books folders, as the Audiobooks menu (Continue Reading, Books, Authors) plus
 * Folders, the file tree as in Music. The lists of books have the art pane at the right: the cover of the selected
 * book. Also the menu of the open book (centre button in the reader): chapters, places, bookmarks, settings.
 *
 * The first visit scans the Books folders on a worker thread ([BookLibrary]); the list stays in memory until
 * Rescan Books or the end of a USB share ([forget]).
 */
class BooksController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val prefs: SharedPreferences,
    private val reader: BookReaderController,
    private val changeScreen: (ScreenState) -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val rowViews: LinearLayout = rootView.findViewById(R.id.container_book_items)
    private val listPane: ViewGroup = (rowViews.parent as View).parent as ViewGroup
    private val listView: ListView = EdgeFadeListView(activity).apply {
        divider = ColorDrawable(0)
        dividerHeight = 0
        selector = ColorDrawable(0)
        itemsCanFocus = true
        isSoundEffectsEnabled = false
        visibility = View.GONE
    }
    private val paneImage: ImageView = rootView.findViewById(R.id.iv_books_cover)
    private val pane = Pane(activity, listPane, rootView.findViewById(R.id.frame_books_cover), paneImage)
    override val listScreen: ListScreen get() = screen

    private val screen: ListScreen

    private var level: BooksLevel = BooksLevel.Root
    private var books: List<BookItem>? = null
    private var byPath: Map<String, BookItem> = emptyMap()
    var isScanning = false
        private set
    /** [forget] came during a scan (the card came back while Books scanned it): scan again when it ends. */
    private var rescanPending = false
    private var scanProgress: String? = null

    /** The book of each row of the rows list ([rowViews]); null for a row that is not a book. */
    private var rowBooks: List<BookItem?> = emptyList()
    /** The book or file that the pane shows for the list itself (the Books menu: the last book). */
    private var paneDefault: File? = null

    private val thumbs = LruCache<String, Bitmap>(160)
    private val paneCovers = LruCache<String, Bitmap>(6)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "BookCovers").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private var paneKey: String? = null
    private var placeholderTile: Bitmap? = null

    private val library = BookLibrary(File(activity.filesDir, "books")) { Xml.newPullParser() }

    init {
        listPane.setPadding(0, (ClassyMetrics.STATUS_BAR_HEIGHT * activity.resources.displayMetrics.density).toInt(), 0, 0)
        listPane.addView(listView, (rowViews.parent as View).layoutParams)
        screen = ListScreen(rowViews, listView, clickFeedback)
        rootView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> if (isShown()) onFocus(now) }
    }

    private fun isShown(): Boolean = rootView.visibility == View.VISIBLE

    override fun title(): String = when (val l = level) {
        BooksLevel.Root -> localize("Books")
        BooksLevel.All -> localize("All Books")
        BooksLevel.Authors -> localize("Authors")
        is BooksLevel.Author -> l.name
        is BooksLevel.Folder -> l.dir?.let { folderName(it) } ?: localize("Folders")
        is BooksLevel.ReaderMenu -> menuTitle()
        is BooksLevel.Chapters -> localize("Chapters")
        is BooksLevel.GoTo -> localize("Go to Position")
        is BooksLevel.Bookmarks -> localize("Bookmarks")
        is BooksLevel.Info -> localize("Book Info")
        is BooksLevel.ReaderSettings -> localize("Reader Settings")
    }

    override fun onEnter(state: ScreenState) {
        level = (state as? ScreenState.Books)?.level ?: BooksLevel.Root
        rootView.visibility = View.VISIBLE
        rootView.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        if (level.isReaderMenu && reader.session == null) {
            // The book closed under its menu (USB share): back to the lists
            NavigationManager.getInstance().popBackStackTo { it is ScreenState.Books && !it.level.isReaderMenu }
            return
        }
        if (books == null && !isScanning && !level.isReaderMenu) scan()
        show()
    }

    override fun onExit() {
        rootView.visibility = View.GONE
        screen.clear()
        paneKey = null
    }

    override fun onThemeChanged() {
        placeholderTile = null
        if (isShown()) refresh()
    }

    override fun onRefreshUI() {
        if (isShown()) refresh()
    }

    /** Back held in Folders (the first key repeat): out of all folder levels at once. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val router = InputRouter.getInstance()
        if (event.repeatCount == 0 || !router.isBackKey(keyCode) || level !is BooksLevel.Folder) return false
        router.suppressBackUntilRelease()
        clickFeedback()
        NavigationManager.getInstance().popBackStackTo { !it.isFolderLevel }
        return true
    }


    /** The books on the card may have changed (a USB share): the next visit scans again, or now if Books shows. */
    fun forget() {
        if (isScanning) {
            rescanPending = true
            return
        }
        books = null
        thumbs.evictAll()
        paneCovers.evictAll()
        if (isShown() && !level.isReaderMenu) scan()
    }

    // --- Library ---

    private fun scan() {
        isScanning = true
        scanProgress = localize("Indexing books...")
        val roots = StoragePaths.getBooksDirs().filter { it.isDirectory }
        Thread({
            val found = library.scan(roots, onProgress = { sofar ->
                activity.runOnUiThread {
                    setBooks(sofar)
                    scanProgress = String.format(localize("Indexing books... %d"), sofar.size)
                    if (isShown() && level is BooksLevel.Root) refresh()
                }
            })
            activity.runOnUiThread {
                setBooks(found)
                isScanning = false
                scanProgress = null
                if (rescanPending) {
                    // This scan may have read the card before it was back: drop it and scan again
                    rescanPending = false
                    forget()
                }
                if (isShown() && !level.isReaderMenu) refresh()
            }
        }, "BookScanner").start()
    }

    private fun setBooks(list: List<BookItem>) {
        books = list
        byPath = list.associateBy { it.path }
    }

    // --- Lists ---

    private fun show() {
        screen.show(spec())
    }

    /** The list again, the selected row kept (a scan step, a value change). */
    private fun refresh() {
        val s = spec()
        if (screen.spec?.id == s.id && (rowViews.hasFocus() || listView.hasFocus())) screen.update(s) else screen.show(s)
    }

    private fun spec(): ListSpec {
        rowBooks = emptyList()
        paneDefault = null
        return when (val l = level) {
            BooksLevel.Root -> rootSpec()
            BooksLevel.All -> bookList(localize("All Books"), books.orEmpty(), "books_all")
            BooksLevel.Authors -> authorsSpec()
            is BooksLevel.Author -> bookList(l.name, books.orEmpty().filter { it.author == l.name }, "books_author")
            is BooksLevel.Folder -> folderSpec(l.dir)
            is BooksLevel.ReaderMenu -> readerMenuSpec(l.file)
            is BooksLevel.Chapters -> chaptersSpec()
            is BooksLevel.GoTo -> goToSpec()
            is BooksLevel.Bookmarks -> bookmarksSpec()
            is BooksLevel.Info -> infoSpec(l.file)
            is BooksLevel.ReaderSettings -> settingsSpec()
        }
    }

    private fun rootSpec(): ListSpec {
        val rows = ArrayList<RowSpec>()
        val last = Prefs.LAST_BOOK.get(prefs).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
        if (last != null) {
            rows += RowSpec(localize("Continue Reading"), key = KEY_CONTINUE, onClick = { openBook(last) })
        }
        rows += RowSpec(localize("All Books"), onClick = { changeScreen(ScreenState.Books(BooksLevel.All)) })
        rows += RowSpec(localize("Authors"), onClick = { changeScreen(ScreenState.Books(BooksLevel.Authors)) })
        rows += RowSpec(localize("Folders"), onClick = { changeScreen(ScreenState.Books(BooksLevel.Folder(null))) })
        if (isScanning) {
            rows += RowSpec(scanProgress ?: localize("Indexing books..."), RowLook.INFO, key = KEY_PROGRESS)
        } else {
            if (books.isNullOrEmpty()) rows += RowSpec(
                localize("No books found.\nCopy EPUB or TXT books into the \"Books\" folder on your SD card, then select Rescan Books."),
                RowLook.INFO
            )
            rows += RowSpec(localize("Rescan Books"), RowLook.ICON, icon = TablerIcons.REFRESH, key = KEY_RESCAN, onClick = { scan(); refresh() })
        }
        paneDefault = last ?: books?.firstOrNull()?.file
        rowBooks = rows.map { null }
        return ListSpec(localize("Books"), rows, pane = pane, id = "books_root")
    }

    private fun authorsSpec(): ListSpec {
        val list = books.orEmpty()
        val rows = BookLibrary.authors(list).map { name ->
            RowSpec(name, onClick = { changeScreen(ScreenState.Books(BooksLevel.Author(name))) })
        }
        return ListSpec(localize("Authors"), rows, empty = emptyText(), letters = true, id = "books_authors")
    }

    private fun emptyText(): String = localize(if (isScanning) "Indexing books..." else "No books found")

    /** A list of books: a ListView (it can be long), the cover of the selected book in the pane. */
    private fun bookList(title: String, items: List<BookItem>, id: String): ListSpec {
        if (items.isEmpty()) return ListSpec(title, emptyList(), empty = emptyText(), pane = pane, id = id)
        return ListSpec(title, adapter = BookAdapter(items), pane = pane, letters = true, id = id)
    }

    private fun folderSpec(dir: File?): ListSpec {
        val roots = StoragePaths.getBooksDirs().filter { it.isDirectory }
        val folder = dir ?: roots.singleOrNull()
        val rows = ArrayList<RowSpec>()
        val bookOfRow = ArrayList<BookItem?>()
        if (folder == null) {
            // The Books folders of each storage; with none, the storages (books can be anywhere on the card)
            val tops = roots.ifEmpty { storageRoots() }
            for (r in tops) {
                rows += folderRow(r, storageName(r))
                bookOfRow += null
            }
            rowBooks = bookOfRow
            return ListSpec(localize("Folders"), rows, pane = pane, id = "books_folders")
        }
        // ".." goes up to the storage, and from a storage or from one of several Books folders to the top list
        val isBooksRoot = roots.any { it.absolutePath == folder.absolutePath }
        val up: File? = when {
            StoragePaths.isAnyStorageRoot(folder) -> null
            isBooksRoot && roots.size > 1 -> null
            else -> folder.parentFile
        }
        // A folder opens at its first folder or book, not at ".." (as Music → Folders)
        rows += RowSpec("..", key = KEY_UP, lead = true, onClick = { changeScreen(ScreenState.Books(BooksLevel.Folder(up))) })
        bookOfRow += null
        val files = folder.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        val dirs = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val docs = files.filter { it.isFile && BookFormat.of(it) != null }.sortedBy { it.name.lowercase() }
        for (d in dirs) {
            rows += folderRow(d, d.name)
            bookOfRow += null
        }
        for (f in docs) {
            val item = byPath[f.absolutePath] ?: BookItem(f, BookTitles.fromFileName(f), "", BookFormat.of(f)!!, null)
            rows += artRow(item, f.name)
            bookOfRow += item
        }
        rowBooks = bookOfRow
        return ListSpec(folderName(folder), rows, pane = pane, letters = true,
            id = "books_folder:" + folder.absolutePath)
    }

    private fun folderRow(dir: File, label: String) = RowSpec(label, RowLook.ICON, icon = TablerIcons.FOLDER, key = dir.absolutePath,
        onClick = { changeScreen(ScreenState.Books(BooksLevel.Folder(dir))) })

    private fun artRow(item: BookItem, label: String = item.title) = RowSpec(label, RowLook.ART, value = subtitle(item), key = item.path,
        image = { iv -> bindThumb(iv, item) }, onClick = { openBook(item.file) })

    private fun storageRoots(): List<File> {
        val out = ArrayList<File>()
        out += StoragePaths.getPrimaryRoot()
        if (StoragePaths.hasSecondaryStorage()) out += File(StoragePaths.SECONDARY_PATH)
        return out.filter { it.isDirectory }
    }

    private fun volumeName(dir: File): String =
        if (dir.absolutePath.startsWith(StoragePaths.SECONDARY_PATH)) localize("SD Card") else localize("Internal Storage")

    /** "SD Card: Books", or the volume alone for a storage root. */
    private fun storageName(dir: File): String =
        if (StoragePaths.isAnyStorageRoot(dir)) volumeName(dir) else volumeName(dir) + ": " + dir.name

    private fun folderName(dir: File): String = if (StoragePaths.isAnyStorageRoot(dir)) volumeName(dir) else dir.name

    /** "Author · 42%", or the author alone for a book not opened yet. */
    private fun subtitle(item: BookItem): String {
        val author = item.author.ifEmpty { localize("Unknown Author") }
        val saved = SavedPosition.decode(prefs.getString(Prefs.EBOOK_POSITION.key(item.path), null)) ?: return author
        return author + " · " + Progress.percentText(saved.permille)
    }

    private fun openBook(file: File) {
        if (!file.isFile) {
            Notice.show(activity, String.format(localize("Could not open %s"), file.name))
            return
        }
        changeScreen(ScreenState.BookReader(file))
    }

    // --- Covers ---

    private fun tile(): Bitmap = placeholderTile ?: TablerIcons.renderTile(
        TablerIcons.BOOK, THUMB_PX, THUMB_PX * 2 / 3, ThemeManager.getTextColorPrimary(), 0x22888888, THUMB_PX / 8f
    ).also { placeholderTile = it }

    /** The row art: the thumbnail from the library (decoded on the worker), else the book tile. */
    private fun bindThumb(iv: ImageView, item: BookItem) {
        val cover = item.cover
        iv.tag = item.path
        val cached = thumbs.get(item.path)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageBitmap(tile())
        if (cover == null) return
        worker.execute {
            val bmp = try {
                BitmapFactory.decodeFile(cover.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            } catch (_: Throwable) {
                null
            } ?: return@execute
            activity.runOnUiThread {
                thumbs.put(item.path, bmp)
                if (iv.tag == item.path) iv.setImageBitmap(bmp)
            }
        }
    }

    /** The focus moved: the pane shows the cover of the book of the focused row. */
    private fun onFocus(view: View?) {
        if (view != null && view.parent === listView) revealInList(view)
        if (!pane.isShown) return
        val item = bookOf(view)
        val file = item?.file ?: paneDefault
        if (file == null) {
            if (view != null && (view === rowViews || view.parent === rowViews)) showPane(null, null)
            return
        }
        showPane(file, item ?: byPath[file.absolutePath])
    }

    /**
     * The focused book row all on the screen: Android 4.4 does not scroll this ListView to a row that only got the
     * focus (a wheel step down to the row half under the bottom edge stayed there).
     */
    private fun revealInList(row: View) {
        val h = listView.height - listView.paddingBottom
        val top = listView.paddingTop
        if (h <= 0 || (row.top >= top && row.bottom <= h)) return
        val pos = listView.getPositionForView(row)
        if (pos == AbsListView.INVALID_POSITION) return
        val y = if (row.top < top) top else h - row.height
        listView.setSelectionFromTop(pos, y - listView.paddingTop)
        listView.post { listView.getChildAt(pos - listView.firstVisiblePosition)?.let { if (!it.hasFocus()) it.requestFocus() } }
    }

    private fun bookOf(view: View?): BookItem? {
        var v = view ?: return null
        while (true) {
            val parent = v.parent ?: return null
            if (parent === rowViews) return rowBooks.getOrNull(rowViews.indexOfChild(v))
            if (parent === listView) {
                val pos = listView.getPositionForView(v)
                return if (pos == AbsListView.INVALID_POSITION) null else listView.adapter?.getItem(pos) as? BookItem
            }
            v = parent as? View ?: return null
        }
    }

    private fun showPane(file: File?, item: BookItem?) {
        val key = file?.absolutePath ?: ""
        if (key == paneKey) return
        paneKey = key
        if (file == null) {
            paneImage.setImageBitmap(BookCovers.placeholder(localize("Books"), "", PANE_W, PANE_H, ThemeManager.getCustomFont()))
            return
        }
        paneCovers.get(key)?.let {
            paneImage.setImageBitmap(it)
            return
        }
        val title = item?.title ?: BookTitles.fromFileName(file)
        val author = item?.author.orEmpty()
        worker.execute {
            if (paneKey != key) return@execute
            val bmp = BookLibrary.paneCover(file, { Xml.newPullParser() }, PANE_H * 3 / 2)
                ?: BookCovers.placeholder(title, author, PANE_W, PANE_H, ThemeManager.getCustomFont())
            activity.runOnUiThread {
                paneCovers.put(key, bmp)
                if (paneKey == key && isShown()) paneImage.setImageBitmap(bmp)
            }
        }
    }

    private inner class BookAdapter(private val items: List<BookItem>) : BaseAdapter(), RowTexts {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun rowText(position: Int): String = items[position].title

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val art = ArtRow.of(convertView, activity)
            art.bind(item.title, subtitle(item))
            bindThumb(art.image, item)
            art.view.setOnClickListener {
                clickFeedback()
                openBook(item.file)
            }
            return art.view
        }
    }

    // --- The menu of the open book ---

    private fun menuTitle(): String {
        val c = reader.currentChapter()
        return if (c >= 0) reader.chapterTitle(c) else reader.title() ?: localize("Books")
    }

    /** Back to the page (the reader shows the place that a row chose). */
    private fun backToPage() {
        NavigationManager.getInstance().popBackStackTo { it is ScreenState.BookReader }
    }

    /** A row of the reader menu. With no [value], the arrow shows on the selected row (the row opens a list). */
    private fun value(label: String, value: String? = null, opens: Boolean = value == null, enabled: Boolean = true, onClick: () -> Unit) =
        RowSpec(localize(label), RowLook.VALUE, value = value.orEmpty(), opens = opens, enabled = enabled, key = label, onClick = onClick)

    private fun readerMenuSpec(file: File): ListSpec {
        val rows = ArrayList<RowSpec>()
        rows += value("Resume Reading", "") { backToPage() }
        val toc = reader.session?.source?.toc.orEmpty()
        rows += value("Go to Chapter") { changeScreen(ScreenState.Books(BooksLevel.Chapters(file))) }
        val next = reader.nextChapter()
        if (next >= 0) rows += value("Next Chapter", reader.chapterTitle(next)) {
            reader.jumpToChapter(next)
            backToPage()
        }
        val prev = reader.previousChapter()
        if (prev >= 0) rows += value("Previous Chapter", reader.chapterTitle(prev)) {
            reader.jumpToChapter(prev)
            backToPage()
        }
        rows += value("Go to Position", Progress.percentText(reader.permille()), opens = true) {
            changeScreen(ScreenState.Books(BooksLevel.GoTo(file)))
        }
        rows += value("Add Bookmark", "") {
            if (reader.addBookmark()) Notice.show(activity, localize("Bookmark added"))
            backToPage()
        }
        val marks = reader.bookmarks().size
        rows += value("Bookmarks", if (marks > 0) "$marks" else "", opens = true) {
            changeScreen(ScreenState.Books(BooksLevel.Bookmarks(file)))
        }
        rows += value("Book Info") { changeScreen(ScreenState.Books(BooksLevel.Info(file))) }
        rows += value("Reader Settings") { changeScreen(ScreenState.Books(BooksLevel.ReaderSettings(file))) }
        rows += value("Close Book", "") {
            reader.closeBook()
            NavigationManager.getInstance().popBackStackTo { it !is ScreenState.BookReader && !(it is ScreenState.Books && it.level.isReaderMenu) }
        }
        if (toc.isEmpty()) rows.removeAll { it.key == "Go to Chapter" }
        return ListSpec(menuTitle(), rows, id = "book_menu")
    }

    private fun chaptersSpec(): ListSpec {
        val s = reader.session
        val toc = s?.source?.toc.orEmpty()
        val places = reader.chapterPlaces()
        val current = reader.currentChapter()
        val weights = s?.source?.weights ?: LongArray(0)
        val rows = toc.mapIndexed { i, e ->
            val pct = places.getOrNull(i)?.let { Progress.percentText(Progress.permille(weights, it, s?.lengthOf(it.section) ?: 0)) }.orEmpty()
            RowSpec("\u2003\u2003".repeat(e.depth.coerceAtMost(4)) + e.title, RowLook.VALUE,
                value = if (i == current) "✓ $pct" else pct, checked = i == current, key = "ch$i",
                onClick = {
                    reader.jumpToChapter(i)
                    backToPage()
                })
        }
        return ListSpec(localize("Chapters"), rows, empty = localize("No chapters found"), focus = Focus.CHECKED, id = "book_chapters")
    }

    private fun goToSpec(): ListSpec {
        val now = reader.permille() / 50 * 5
        val rows = (0..100 step 5).map { p ->
            RowSpec("$p%", RowLook.CHOICE, checked = p == now, onClick = {
                reader.jumpToPermille(p * 10)
                backToPage()
            })
        }
        return ListSpec(localize("Go to Position"), rows, focus = Focus.CHECKED, id = "book_goto")
    }

    private fun bookmarksSpec(): ListSpec {
        val rows = reader.bookmarks().map { m ->
            RowSpec(m.snippet.ifEmpty { m.chapter.ifEmpty { "•" } }, RowLook.VALUE, value = Progress.percentText(m.permille),
                key = "bm${m.position.section}:${m.position.offset}",
                onClick = {
                    reader.jumpTo(m.position)
                    backToPage()
                },
                onHold = {
                    reader.removeBookmark(m.position)
                    Notice.show(activity, localize("Bookmark deleted"))
                    refresh()
                })
        }
        val all = ArrayList(rows)
        if (rows.isNotEmpty()) all += RowSpec(localize("Hold a bookmark to delete it."), RowLook.INFO)
        return ListSpec(localize("Bookmarks"), all, empty = localize("No bookmarks. Select Add Bookmark in the book menu."), id = "book_marks")
    }

    private fun infoSpec(file: File): ListSpec {
        val src = reader.session?.source
        val rows = ArrayList<RowSpec>()
        fun info(label: String, v: String) {
            if (v.isNotEmpty()) rows += RowSpec(localize(label), RowLook.VALUE, value = v, key = label)
        }
        info("Title", src?.title.orEmpty())
        info("Author", src?.author.orEmpty())
        info("Language", src?.language.orEmpty())
        info("Format", src?.format?.name.orEmpty())
        info("Progress", Progress.percentText(reader.permille()))
        info("Chapters", src?.toc?.size?.takeIf { it > 0 }?.toString().orEmpty())
        info("Size", String.format(java.util.Locale.US, "%.1f MB", file.length() / 1048576.0))
        rows += RowSpec(StoragePaths.toDisplayPath(file.absolutePath), RowLook.INFO)
        return ListSpec(localize("Book Info"), rows, id = "book_info")
    }

    private fun settingsSpec(): ListSpec {
        val r = SettingRows(prefs, localize) { refresh() }
        val rows = ReaderSettings.rows(r, prefs).filter { it.visible() }.map { it.make() }
        return ListSpec(localize("Reader Settings"), rows, id = "book_settings")
    }

    private companion object {
        const val KEY_CONTINUE = "\u0000continue"
        const val KEY_RESCAN = "\u0000rescan"
        const val KEY_PROGRESS = "\u0000progress"
        const val KEY_UP = "\u0000up"
        const val THUMB_PX = 64
        /** The pane covers: a little larger than the pane (it zooms the art). */
        const val PANE_W = 330
        const val PANE_H = 440
    }
}
