package com.themoon.y1.controllers.photos

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager
import com.themoon.y1.photos.PhotoDecoder
import com.themoon.y1.photos.PhotoItem
import com.themoon.y1.photos.PhotoLibrary
import com.themoon.y1.views.CoverArtView
import com.themoon.y1.views.CoverPane
import com.themoon.y1.views.TablerIcons
import java.util.Random
import java.util.concurrent.Executors

/**
 * While the Photos row of the main menu is selected: random photos in the art pane, one every [CHANGE_MS], with the
 * same slow pan and zoom as the album art ([CoverPane]). The main menu (MainMenuController) puts it over its cover.
 *
 * It works only while it shows and the screen is on.
 */
class PhotoPreviewView(context: Context) : FrameLayout(context) {

    private val image = CoverArtView(context)
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()
    private var lastPath: String? = null
    private var running = false
    private var token = 0

    private val change = Runnable { next() }
    private val libraryListener: () -> Unit = { if (running && lastPath == null) next() }

    init {
        clipChildren = true
        addView(image)
        CoverPane.attach(image)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        update()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        update()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun update() {
        val on = isShown && windowVisibility == View.VISIBLE
        if (on && !running) start() else if (!on && running) stop()
    }

    /** The art panes under this view, hidden while it shows ([hidePanesBelow]). */
    private val hiddenPanes = ArrayList<View>()

    /**
     * Hides the art panes that this view covers (the main menu cover). All art panes share one
     * motion ([CoverPane.motion]): two panes with different art on the screen would fade into each other at each frame.
     */
    private fun hidePanesBelow() {
        val parent = parent as? android.view.ViewGroup ?: return
        val me = rectOf(this)
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            if (v === this || v.visibility != View.VISIBLE || !hasCoverArt(v)) continue
            if (!android.graphics.Rect.intersects(me, rectOf(v))) continue
            v.visibility = View.INVISIBLE
            hiddenPanes += v
        }
    }

    /** The place of [v] in the menu canvas; from its layout params before its first layout (it was GONE). */
    private fun rectOf(v: View): android.graphics.Rect {
        if (v.width > 0 && v.height > 0) return android.graphics.Rect(v.left, v.top, v.right, v.bottom)
        val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return android.graphics.Rect()
        val w = if (lp.width > 0) lp.width else resources.displayMetrics.widthPixels
        val h = if (lp.height > 0) lp.height else resources.displayMetrics.heightPixels
        return android.graphics.Rect(lp.leftMargin, lp.topMargin, lp.leftMargin + w, lp.topMargin + h)
    }

    private fun showPanesBelow() {
        for (v in hiddenPanes) if (v.visibility == View.INVISIBLE) v.visibility = View.VISIBLE
        hiddenPanes.clear()
    }

    private fun hasCoverArt(v: View): Boolean {
        if (v is CoverArtView) return true
        val g = v as? android.view.ViewGroup ?: return false
        for (i in 0 until g.childCount) if (hasCoverArt(g.getChildAt(i))) return true
        return false
    }

    private fun start() {
        running = true
        hidePanesBelow()
        PhotoLibrary.addListener(libraryListener)
        PhotoLibrary.ensureLoaded()
        // The last photo at once (no empty pane), then a new one after the change time
        if (lastBitmap != null) {
            image.setImageBitmap(lastBitmap)
            handler.postDelayed(change, CHANGE_MS)
        } else {
            next()
        }
    }

    private fun stop() {
        running = false
        showPanesBelow()
        token++
        handler.removeCallbacks(change)
        PhotoLibrary.removeListener(libraryListener)
    }

    private fun next() {
        handler.removeCallbacks(change)
        if (!running) return
        val photos = PhotoLibrary.photos.orEmpty()
        if (photos.isEmpty()) {
            // The library listener starts again when photos come ([libraryListener]); the next start shows no old photo,
            // and a decode that still runs is dropped
            token++
            lastPath = null
            lastBitmap = null
            image.setImageBitmap(TablerIcons.renderTile(TablerIcons.PHOTO, PANE_W, PANE_W / 3,
                ThemeManager.getTextColorSecondary(), 0x22888888, 0f))
            return
        }
        // Screen off or blanked: wait, no decode
        if ((context as? MainActivity)?.isOnScreen == false) {
            handler.postDelayed(change, CHANGE_MS)
            return
        }
        val p = pick(photos)
        val t = token
        worker.execute {
            val bmp = PhotoDecoder.decode(p.path, p.orientation, PANE_W, PANE_H, PhotoDecoder.Mode.COVER)
            handler.post {
                if (t != token || !running) return@post
                if (bmp != null) {
                    lastPath = p.path
                    lastBitmap = bmp
                    image.setImageBitmap(bmp)
                }
                // A read that a USB share refused tries again later, not at once
                handler.postDelayed(change, if (bmp != null || PhotoDecoder.isClosed) CHANGE_MS else RETRY_MS)
            }
        }
    }

    private fun pick(photos: List<PhotoItem>): PhotoItem {
        if (photos.size == 1) return photos[0]
        var p: PhotoItem
        do {
            p = photos[random.nextInt(photos.size)]
        } while (p.path == lastPath)
        return p
    }

    companion object {
        private const val CHANGE_MS = 5_000L
        private const val RETRY_MS = 500L
        /** Larger than the pane: the pane zooms and moves the photo. */
        private const val PANE_W = 360
        private const val PANE_H = 480

        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "PhotoPreview").apply { priority = Thread.NORM_PRIORITY - 1 } }

        /** The last photo shown, for the next time the row is selected (the view stays, but the decode takes time). */
        private var lastBitmap: Bitmap? = null

        /** The card changed: the next preview picks from the new photos. */
        fun forget() {
            lastBitmap = null
        }
    }
}
