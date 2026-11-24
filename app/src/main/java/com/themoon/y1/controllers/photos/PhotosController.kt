package com.themoon.y1.controllers.photos

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.Pane
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.navigation.InputRouter
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.PhotosLevel
import com.themoon.y1.navigation.RowFocus
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.photos.PhotoDecoder
import com.themoon.y1.photos.PhotoIndex
import com.themoon.y1.photos.PhotoItem
import com.themoon.y1.photos.PhotoLibrary
import com.themoon.y1.photos.PhotoThumbs
import com.themoon.y1.views.ClassyMetrics
import com.themoon.y1.views.Notice
import java.io.File
import java.util.Random
import java.util.concurrent.Executors

/**
 * Main menu → Photos, built as the Music and Videos menus: text lists, the art pane at the right of the menu, and the
 * photos themselves only at the end, as a grid of thumbnails ([PhotoGridView]).
 *
 * - All Photos, Recently Added (found in the last 30 days), By Date (years, then months), Folders (the folders in
 *   Photos, as Music → Folders: Back goes up, no ".." row) and Shuffle Slideshow.
 * - In the grid the centre button shows the photo full screen ([PhotoViewerController]) and a held centre starts the
 *   slideshow there. The slideshow settings are in Settings → Photos.
 * - Play/Pause stays with the music on all Photos screens.
 */
class PhotosController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val changeScreen: (ScreenState) -> Unit,
    private val clickFeedback: () -> Unit,
    private val refreshTitle: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val lists: View = rootView.findViewById(R.id.layout_photos_lists)
    private val rowViews: LinearLayout = rootView.findViewById(R.id.container_photo_items)
    private val listPane: ViewGroup = (rowViews.parent as View).parent as ViewGroup
    private val paneImage: ImageView = rootView.findViewById(R.id.iv_photos_cover)
    private val pane = Pane(activity, listPane, rootView.findViewById(R.id.frame_photos_cover), paneImage)
    override val listScreen: ListScreen get() = screen

    private val screen = ListScreen(rowViews, clickFeedback = clickFeedback)
    private val grid = PhotoGridView(activity)

    private var level: PhotosLevel = PhotosLevel.Root
    /** The selected photo of each grid (by set), kept when the viewer or Back comes back. */
    private val gridFocus = HashMap<String, Int>()

    private val paneArt = LruCache<String, Bitmap>(2)
    private val paneWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "PhotoPane").apply { priority = Thread.NORM_PRIORITY - 1 } }
    /** The photo that the art pane of the menu shows ([PhotoItem.version]): one random photo, kept while Photos is open. */
    private var panePhoto: String? = null
    /** The photo that decodes for the pane now; it becomes [panePhoto] only when it shows (a failed decode can retry). */
    private var paneWanted: String? = null
    private val random = Random()

    private var centreDownAt = 0L
    private var centreHeld = false
    /** The centre press started on this screen (not a hold that opened it from the screen before). */
    private var centreOwn = false

    private val libraryListener: () -> Unit = { if (isShown()) show(keep = true) }

    init {
        rootView.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        grid.visibility = View.GONE
        // The grid keeps the focus (the rows of the list are gone)
        grid.isFocusable = true
    }

    private fun isShown(): Boolean = rootView.visibility == View.VISIBLE

    val isScanning: Boolean get() = PhotoLibrary.isScanning

    override fun title(): String = when (val l = level) {
        PhotosLevel.Root -> localize("Photos")
        PhotosLevel.Years -> localize("By Date")
        is PhotosLevel.Months -> l.year.toString()
        is PhotosLevel.Folder -> l.dir?.name ?: localize("Folders")
        is PhotosLevel.Grid -> {
            val name = setName(l.set)
            if (grid.count > 0 && grid.visibility == View.VISIBLE) "$name (${grid.selected + 1} / ${grid.count})" else name
        }
    }

    override fun onEnter(state: ScreenState) {
        centreOwn = false
        level = (state as? ScreenState.Photos)?.level ?: PhotosLevel.Root
        rootView.visibility = View.VISIBLE
        rootView.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        PhotoLibrary.addListener(libraryListener)
        // Photos opens: new photos may be on the card (a scan reads only the new and changed files)
        if (level == PhotosLevel.Root) PhotoLibrary.refresh() else PhotoLibrary.ensureLoaded()
        show()
    }

    override fun onExit() {
        PhotoLibrary.removeListener(libraryListener)
        rootView.visibility = View.GONE
        screen.clear()
        grid.visibility = View.GONE
    }

    override fun onThemeChanged() {
        if (isShown()) show(keep = true)
    }

    override fun onRefreshUI() {
        if (isShown()) show(keep = true)
    }

    /** The viewer showed photo [index] of [set] last: the grid selects it when it shows again. */
    fun rememberPhoto(set: String, index: Int) {
        gridFocus[set] = index
    }

    /** The card changed (end of a USB share): drop the art and scan again. */
    fun forget() {
        paneArt.evictAll()
        panePhoto = null
        paneWanted = null
        PhotoThumbs.clearMemory()
        PhotoPreviewView.forget()
        PhotoLibrary.forget()
    }

    // --- Lists ---

    private fun show(keep: Boolean = false) {
        val l = level
        if (l is PhotosLevel.Grid && PhotoLibrary.set(l.set).isNotEmpty()) {
            showGrid(l.set)
            return
        }
        grid.visibility = View.GONE
        lists.visibility = View.VISIBLE
        val spec = when (l) {
            PhotosLevel.Root -> rootSpec()
            PhotosLevel.Years -> yearsSpec()
            is PhotosLevel.Months -> monthsSpec(l.year)
            is PhotosLevel.Folder -> folderSpec(l.dir)
            is PhotosLevel.Grid -> ListSpec(setName(l.set), emptyList(), empty = emptyText(), id = "photos_grid_empty")
        }
        if (keep && screen.spec?.id == spec.id && rowViews.hasFocus()) {
            screen.update(spec)
        } else {
            screen.show(spec)
        }
        if (l == PhotosLevel.Root) showPanePhoto()
    }

    private fun emptyText(): String = localize(if (PhotoLibrary.isScanning) "Indexing photos..." else "No photos found.")

    private fun openGrid(set: String) = changeScreen(ScreenState.Photos(PhotosLevel.Grid(set)))

    private fun rootSpec(): ListSpec {
        val rows = ArrayList<RowSpec>()
        rows += RowSpec(localize("All Photos"), onClick = { openGrid(PhotoIndex.ALL) })
        rows += RowSpec(localize("Recently Added"), onClick = { openGrid(PhotoIndex.RECENT) })
        rows += RowSpec(localize("By Date"), onClick = { changeScreen(ScreenState.Photos(PhotosLevel.Years)) })
        rows += RowSpec(localize("Folders"), onClick = { changeScreen(ScreenState.Photos(PhotosLevel.Folder(null))) })
        rows += RowSpec(localize("Shuffle Slideshow"), onClick = { shuffleSlideshow() })
        val photos = PhotoLibrary.photos
        if (PhotoLibrary.isScanning) {
            val n = PhotoLibrary.scanCount
            rows += RowSpec(if (n > 0) String.format(localize("Indexing photos... %d"), n) else localize("Indexing photos..."),
                RowLook.INFO, key = KEY_PROGRESS)
        } else if (photos.isNullOrEmpty()) {
            rows += RowSpec(localize("No photos found.\nCopy JPEG or PNG photos into the \"Photos\" folder on your SD card."), RowLook.INFO)
        }
        return ListSpec(localize("Photos"), rows, pane = pane, id = "photos_root")
    }

    private fun shuffleSlideshow() {
        val all = PhotoLibrary.set(PhotoIndex.ALL)
        if (all.isEmpty()) {
            Notice.show(activity, localize("No photos found."))
            return
        }
        changeScreen(ScreenState.PhotoViewer(PhotoIndex.ALL, random.nextInt(all.size), slideshow = true, shuffle = true))
    }

    private fun yearsSpec(): ListSpec {
        val rows = PhotoIndex.years(PhotoLibrary.photos.orEmpty()).map { (year, _) ->
            RowSpec(year.toString(), key = "year:$year",
                onClick = { changeScreen(ScreenState.Photos(PhotosLevel.Months(year))) })
        }
        return ListSpec(localize("By Date"), rows, empty = emptyText(), id = "photos_years")
    }

    private fun monthsSpec(year: Int): ListSpec {
        val rows = PhotoIndex.months(PhotoLibrary.photos.orEmpty(), year).map { (month, _) ->
            RowSpec(localize(MONTHS[month - 1]), key = "month:$month",
                onClick = { openGrid(PhotoIndex.month(year, month)) })
        }
        return ListSpec(year.toString(), rows, empty = emptyText(), id = "photos_months:$year")
    }

    /**
     * Folders: at the top the Photos folder of each storage (with one, its contents at once); in a folder its Slideshow
     * (the photos there and deeper), the folders that have photos, A to Z, and a Photos row with its own photos (the
     * grid). Back goes up: each folder is a level of the back stack, so no ".." row.
     */
    private fun folderSpec(dir: File?): ListSpec {
        val photos = PhotoLibrary.photos.orEmpty()
        val roots = StoragePaths.getPhotosDirs().filter { it.isDirectory }
        val folder = dir ?: roots.singleOrNull()
        val rows = ArrayList<RowSpec>()
        if (folder == null) {
            val labelled = StoragePaths.hasSecondaryStorage()
            for (r in roots) {
                val n = PhotoIndex.countUnder(photos, r.absolutePath)
                if (n == 0) continue
                val label = if (!labelled) r.name
                else if (StoragePaths.isOnSecondary(r)) "${r.name} (SD)" else "${r.name} (${localize("Internal Flash")})"
                rows += RowSpec(label, key = r.absolutePath,
                    onClick = { changeScreen(ScreenState.Photos(PhotosLevel.Folder(r))) })
            }
            return ListSpec(localize("Folders"), rows, empty = emptyText(), id = "photos_folders")
        }
        val path = folder.absolutePath
        if (PhotoIndex.countUnder(photos, path) > 0) {
            // A folder opens at its first folder (or its Photos row), not at Slideshow: as Music → Folders and Play All
            rows += RowSpec("▶ " + localize("Slideshow"), key = KEY_SLIDESHOW, lead = true,
                onClick = { changeScreen(ScreenState.PhotoViewer(PhotoIndex.underFolder(path), 0, slideshow = true)) })
        }
        for ((sub, _) in PhotoIndex.subfolders(photos, path)) {
            rows += RowSpec(sub.substringAfterLast('/'), key = sub,
                onClick = { changeScreen(ScreenState.Photos(PhotosLevel.Folder(File(sub)))) })
        }
        val here = PhotoLibrary.set(PhotoIndex.inFolder(path)).size
        if (here > 0) {
            rows += RowSpec(localize("Photos") + " ($here)", key = KEY_PHOTOS,
                onClick = { openGrid(PhotoIndex.inFolder(path)) })
        }
        val title = if (dir == null) localize("Folders") else folder.name
        return ListSpec(title, rows, empty = emptyText(), letters = true, id = "photos_folder:$path")
    }

    /** The title of a grid. */
    private fun setName(set: String): String {
        if (set == PhotoIndex.ALL) return localize("All Photos")
        if (set == PhotoIndex.RECENT) return localize("Recently Added")
        PhotoIndex.parseMonth(set)?.let { (y, m) -> return localize(MONTHS[(m - 1).coerceIn(0, 11)]) + " " + y }
        PhotoIndex.folderOf(set)?.let { return it.substringAfterLast('/') }
        return localize("Photos")
    }

    // --- Art pane of the menu: one random photo, with the slow pan and zoom of the art panes ---

    private fun showPanePhoto() {
        if (!pane.isShown) return
        val photos = PhotoLibrary.photos.orEmpty()
        val known = (paneWanted ?: panePhoto)?.let { v -> photos.firstOrNull { it.version == v } }
        val photo: PhotoItem = known ?: photos.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] } ?: run {
            // No photos: a decode that still runs does not paint over the empty pane, and a photo that comes back shows
            panePhoto = null
            paneWanted = null
            paneImage.setImageResource(R.drawable.default_album)
            return
        }
        // The version, not the path: a photo replaced under the same name decodes again
        val version = photo.version
        if (version == panePhoto && paneImage.drawable != null) {
            // It shows already: a decode of another photo that still runs must not replace it
            paneWanted = null
            return
        }
        if (version == paneWanted) return
        paneArt.get(version)?.let {
            paneImage.setImageBitmap(it)
            panePhoto = version
            paneWanted = null
            return
        }
        paneWanted = version
        paneWorker.execute {
            val bmp = PhotoDecoder.decode(photo.path, photo.orientation, PANE_W, PANE_H, PhotoDecoder.Mode.COVER)
            activity.runOnUiThread {
                if (bmp != null) paneArt.put(version, bmp)
                if (paneWanted != version) return@runOnUiThread
                paneWanted = null
                // A failed decode (a USB share closed the reads, a broken file) keeps the old pane: the next time tries again
                if (bmp != null && isShown()) {
                    paneImage.setImageBitmap(bmp)
                    panePhoto = version
                }
            }
        }
    }

    // --- Grid ---

    private fun gridSet(): String? = (level as? PhotosLevel.Grid)?.set

    private fun showGrid(set: String) {
        screen.clear()
        lists.visibility = View.GONE
        grid.visibility = View.VISIBLE
        grid.topInset = (ClassyMetrics.STATUS_BAR_HEIGHT * activity.resources.displayMetrics.density).toInt()
        grid.frameColor = ThemeManager.getListButtonFocusedBg()
        grid.emptyColor = (ThemeManager.getTextColorSecondary() and 0x00FFFFFF) or 0x33000000
        grid.setPhotos(PhotoLibrary.set(set), gridFocus[set] ?: 0)
        grid.requestFocus()
        refreshTitle()
    }

    private fun gridStep(delta: Int) {
        val n = grid.count
        if (n == 0) return
        var to = grid.selected + delta
        if (to < 0 || to >= n) {
            to = if (!RowFocus.isLoopOn(activity) || Math.abs(delta) > 1) to.coerceIn(0, n - 1) else (to + n) % n
        }
        if (to == grid.selected) return
        clickFeedback()
        grid.select(to)
        gridSet()?.let { gridFocus[it] = to }
        refreshTitle()
    }

    private fun openPhoto(slideshow: Boolean) {
        val set = gridSet() ?: return
        if (grid.count == 0) return
        clickFeedback()
        gridFocus[set] = grid.selected
        changeScreen(ScreenState.PhotoViewer(set, grid.selected, slideshow, path = grid.selectedPhoto?.path))
    }

    // --- Keys ---

    private fun isGridShown(): Boolean = level is PhotosLevel.Grid && grid.visibility == View.VISIBLE

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (isGridShown()) {
            gridStep(delta)
            return true
        }
        return screen.wheel(delta, event.eventTime)
    }

    /** Back held in Folders (the first key repeat): out of all folder levels at once, as Music → Folders. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val router = InputRouter.getInstance()
        if (event.repeatCount == 0 || !router.isBackKey(keyCode) || level !is PhotosLevel.Folder) return false
        router.suppressBackUntilRelease()
        clickFeedback()
        NavigationManager.getInstance().popBackStackTo { !(it is ScreenState.Photos && it.level is PhotosLevel.Folder) }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isGridShown()) return screen.key(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down) {
                    if (event.repeatCount == 0) {
                        centreDownAt = SystemClock.uptimeMillis()
                        centreHeld = false
                        centreOwn = true
                    } else if (centreOwn && !centreHeld && SystemClock.uptimeMillis() - centreDownAt >= HOLD_MS) {
                        // Held: the slideshow from the selected photo
                        centreHeld = true
                        openPhoto(slideshow = true)
                    }
                } else if (centreOwn.also { centreOwn = false } && !centreHeld && !event.isCanceled) {
                    openPhoto(slideshow = false)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                // A page of photos (not the next song): 20 on the Y2
                if (down) gridStep(if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) grid.pageSize else -grid.pageSize)
                return true
            }
        }
        return false
    }

    private companion object {
        const val KEY_PROGRESS = "\u0000progress"
        const val KEY_SLIDESHOW = "\u0000slideshow"
        const val KEY_PHOTOS = "\u0000photos"
        /** The art pane photo: larger than the pane, which zooms and moves it ([com.themoon.y1.views.CoverPane]). */
        const val PANE_W = 360
        const val PANE_H = 480
        /** A centre hold: as the hold of a list row. */
        const val HOLD_MS = 600L
        val MONTHS = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
    }
}
