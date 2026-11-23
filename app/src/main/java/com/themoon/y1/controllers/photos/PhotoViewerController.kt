package com.themoon.y1.controllers.photos

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.Prefs
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.player.LyricsScreenHold
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.RowFocus
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.WheelKind
import com.themoon.y1.photos.PhotoDecoder
import com.themoon.y1.photos.PhotoItem
import com.themoon.y1.photos.PhotoLibrary
import com.themoon.y1.photos.SlideshowOrder
import java.text.DateFormat
import java.util.Date
import java.util.Random
import java.util.concurrent.Executors

/**
 * A photo full screen, over the status bar: the whole photo with black bars
 * ([PhotoDecoder.Mode.FIT]); the wheel, Next and Previous go to the next or previous photo.
 *
 * - Centre: starts the slideshow from this photo; during the slideshow it pauses and resumes it.
 * - Hold the centre: the name, date and size of the photo.
 * - Back: the grid, on this photo ([onLeave]).
 * - Play/Pause stays with the music: the slideshow never starts, stops or changes it.
 *
 * The photos next to this one decode ahead on a worker thread, at the screen size. The screen stays on only while
 * a timed slideshow runs ([holdsScreen]; Time Per Slide = Manual turns off as usual), and screen off pauses it.
 */
class PhotoViewerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val prefs: SharedPreferences,
    private val onLeave: (set: String, index: Int) -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "PhotoViewer").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val screenHold = LyricsScreenHold(activity, "Y1:PhotoSlideshow")

    private var front = newImage()
    private var back = newImage()
    private val info = TextView(activity).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xB0000000.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f * activity.resources.displayMetrics.density)
        gravity = Gravity.CENTER
        val p = (10 * activity.resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, p / 2)
        visibility = View.GONE
    }

    /** The set of photos ([com.themoon.y1.photos.PhotoIndex.select]) that the viewer goes through. */
    private var set = ""
    /** Shuffle Slideshow: shuffled whatever Settings → Photos says. */
    private var forceShuffle = false
    private var photos: List<PhotoItem> = emptyList()
    // Read by the decode worker ([isNear])
    @Volatile
    private var index = 0
    @Volatile
    private var nextSlideIndex = -1
    @Volatile
    private var token = 0
    private var shown = false
    /** The index that [front] shows (-1: none yet). */
    private var showing = -1
    private val bitmaps = HashMap<Int, Bitmap>()
    /** A decode that runs; [forDisplay]: a display request started it (not a prefetch). */
    private class Decode(val forDisplay: Boolean) {
        /** The display request that waits for it: only the latest one (its transition is the one to show). */
        var waiting: ((Bitmap?) -> Unit)? = null
    }

    /** The photos that decode now, with the callbacks that wait for them. */
    private val decoding = HashMap<Int, Decode>()
    private var animating = false

    private var order: SlideshowOrder? = null
    private var paused = false

    private var centreDownAt = 0L
    private var centreHeld = false
    /** The centre press started on this screen (not a hold that opened it from the screen before). */
    private var centreOwn = false

    private val hideInfo = Runnable { info.visibility = View.GONE }
    /** The info line shows the details of one photo ([showPhotoInfo]): it goes when another photo shows. */
    private var infoIsPhoto = false
    private val nextSlide = Runnable { onSlideTime() }
    private val holdTask = object : Runnable {
        override fun run() {
            if (!shown) return
            val onScreen = (activity as? MainActivity)?.isOnScreen != false
            // Screen off (or another app) pauses the slideshow; the screen stays on only while it runs
            if (!onScreen && isRunning()) pause()
            screenHold.update(onScreen && holdsScreen())
            handler.postDelayed(this, HOLD_TICK_MS)
        }
    }

    init {
        rootView.setBackgroundColor(0xFF000000.toInt())
        rootView.addView(back, match())
        rootView.addView(front, match())
        rootView.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun newImage() = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(0xFF000000.toInt())
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun isRunning(): Boolean = order != null && !paused

    /** The screen stays on: the slideshow runs and moves on by itself (not Manual, where nothing happens without a key). */
    private fun holdsScreen(): Boolean = isRunning() && PhotoSettings.slideMs(prefs) > 0

    override fun onEnter(state: ScreenState) {
        centreOwn = false
        val s = state as? ScreenState.PhotoViewer ?: return
        rootView.visibility = View.VISIBLE
        shown = true
        set = s.set
        forceShuffle = s.shuffle
        photos = PhotoLibrary.set(set)
        if (photos.isEmpty()) {
            backIfShown()
            return
        }
        // The photo chosen in the grid, also when the set changed since (Recently Added drops photos after 30 days)
        val at = s.path?.let { p -> photos.indexOfFirst { it.path == p } } ?: s.index
        if (at < 0) {
            // It left the set: back to the grid, which shows the set as it is now
            photos = emptyList()
            backIfShown()
            return
        }
        index = at.coerceIn(0, photos.size - 1)
        showing = -1
        show(index, PhotoSettings.TRANSITION_OFF, 1)
        handler.removeCallbacks(holdTask)
        handler.post(holdTask)
        if (s.slideshow) {
            start()
        } else if (!Prefs.PHOTOS_HINT_SHOWN.get(prefs)) {
            Prefs.PHOTOS_HINT_SHOWN.set(prefs, true)
            showInfo(localize("Press the centre button for a slideshow"), HINT_MS)
        }
    }

    /** Back after [onEnter] returns, only while the viewer still shows (a USB share or a Back press may close it first). */
    private fun backIfShown() {
        handler.post {
            if (shown && NavigationManager.getInstance().currentState is ScreenState.PhotoViewer) {
                NavigationManager.getInstance().popBackStack()
            }
        }
    }

    override fun onExit() {
        shown = false
        stop()
        handler.removeCallbacks(holdTask)
        handler.removeCallbacks(hideInfo)
        screenHold.update(false)
        info.visibility = View.GONE
        endAnimation()
        if (photos.isNotEmpty()) onLeave(set, index)
        rootView.visibility = View.GONE
        token++
        front.setImageDrawable(null)
        back.setImageDrawable(null)
        bitmaps.clear()
        decoding.clear()
        photos = emptyList()
    }

    /** MainActivity went to the background (screen off): the slideshow pauses. */
    fun onActivityPause() {
        if (shown && isRunning()) pause()
        screenHold.update(false)
    }

    /** Before a USB share: the photos are on the card, so the viewer closes. */
    fun closeForUsbShare() {
        if (!shown) return
        NavigationManager.getInstance().popBackStackTo { it !is ScreenState.PhotoViewer }
    }

    // --- Photos ---

    private fun screenW() = rootView.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
    private fun screenH() = rootView.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels

    /** Shows photo [i] with [transition] ([direction]: 1 = forward, -1 = back, for Push). */
    private fun show(i: Int, transition: Int, direction: Int) {
        if (i !in photos.indices) return
        if (i != index && infoIsPhoto) info.visibility = View.GONE
        index = i
        val bmp = bitmaps[i]
        if (bmp != null) {
            if (showing != i) display(bmp, transition, direction)
            showing = i
            prefetch()
            return
        }
        request(i) { b ->
            // Only the photo that is still wanted, and once (a wheel step and the slideshow can both wait for it)
            if (index == i && shown && showing != i) {
                if (b != null) display(b, transition, direction) else showBroken()
                showing = i
                prefetch()
            }
        }
    }

    /**
     * Decodes photo [i] once: a request for a photo that is decoding already waits for that decode, and replaces the
     * display request that waited before it (a slideshow step after a wheel step shows its own transition).
     */
    private fun request(i: Int, done: ((Bitmap?) -> Unit)? = null) {
        val p = photos.getOrNull(i) ?: return
        decoding[i]?.let { d ->
            if (done != null) d.waiting = done
            return
        }
        decoding[i] = Decode(forDisplay = done != null).also { d -> d.waiting = done }
        val t = token
        val w = screenW()
        val h = screenH()
        val n = photos.size
        nextSlideIndex = order?.peek() ?: -1
        worker.execute {
            // Skip a photo that the wheel already left
            val wanted = t == token && isNear(i, n)
            val bmp = if (wanted) PhotoDecoder.decode(p.path, p.orientation, w, h, PhotoDecoder.Mode.FIT) else null
            handler.post {
                if (t != token) return@post
                val d = decoding.remove(i)
                val waiting = d?.waiting
                if (bmp != null) bitmaps[i] = bmp
                // Skipped (the wheel came back to it meanwhile), or a prefetch that failed while the photo was
                // wanted on the screen: one decode of its own before "Could not open"
                if (bmp == null && waiting != null && index == i && (!wanted || d?.forDisplay == false)) {
                    request(i, waiting)
                    return@post
                }
                waiting?.invoke(bmp)
            }
        }
    }

    /** The photos to keep decoded: this one, its neighbours, the next slide. */
    private fun wantedSet(): Set<Int> {
        val n = photos.size
        val set = HashSet<Int>()
        set += index
        if (n > 1) {
            set += (index + 1) % n
            if (!isLowMemory()) set += (index - 1 + n) % n
        }
        order?.peek()?.let { if (it >= 0) set += it }
        return set
    }

    /** True for a photo next to the one that shows ([index]): it is still worth decoding (worker thread). */
    private fun isNear(i: Int, n: Int): Boolean {
        val at = index
        if (n <= 0) return false
        val d = Math.abs(i - at)
        return Math.min(d, n - d) <= 1 || i == nextSlideIndex
    }

    private fun prefetch() {
        val want = wantedSet()
        bitmaps.keys.retainAll(want + showing)
        for (i in want) if (i !in bitmaps) request(i)
    }

    private fun isLowMemory(): Boolean =
        ((activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 64) < 48

    private fun showBroken() {
        endAnimation()
        front.setImageDrawable(null)
        showInfo(String.format(localize("Could not open %s"), photos.getOrNull(index)?.name ?: ""), INFO_MS)
    }

    // --- Transitions ---

    private fun display(bmp: Bitmap, transition: Int, direction: Int) {
        endAnimation()
        val t = if (transition == PhotoSettings.TRANSITION_RANDOM) {
            if (Random().nextBoolean()) PhotoSettings.TRANSITION_DISSOLVE else PhotoSettings.TRANSITION_PUSH
        } else transition
        if (t == PhotoSettings.TRANSITION_OFF || showing < 0 || rootView.width <= 0) {
            front.setImageBitmap(bmp)
            return
        }
        val incoming = back
        val outgoing = front
        incoming.setImageBitmap(bmp)
        incoming.visibility = View.VISIBLE
        incoming.bringToFront()
        info.bringToFront()
        animating = true
        if (t == PhotoSettings.TRANSITION_PUSH) {
            val w = rootView.width.toFloat()
            incoming.alpha = 1f
            incoming.translationX = direction * w
            incoming.animate().translationX(0f).setDuration(PUSH_MS).start()
            outgoing.animate().translationX(-direction * w).setDuration(PUSH_MS).withEndAction { endAnimation() }.start()
        } else {
            incoming.alpha = 0f
            incoming.translationX = 0f
            incoming.animate().alpha(1f).setDuration(DISSOLVE_MS).withEndAction { endAnimation() }.start()
        }
        // From now on the new photo is the front one
        front = incoming
        back = outgoing
    }

    /** Ends a running transition at once: the front photo shows, the other one is cleared. */
    private fun endAnimation() {
        if (!animating) return
        animating = false
        front.animate().cancel()
        back.animate().cancel()
        front.alpha = 1f
        front.translationX = 0f
        back.translationX = 0f
        back.alpha = 1f
        back.setImageDrawable(null)
    }

    // --- Slideshow ---

    private fun start() {
        order = SlideshowOrder(photos.size, index, forceShuffle || Prefs.PHOTOS_SHUFFLE.get(prefs), Prefs.PHOTOS_REPEAT.get(prefs))
        paused = false
        showInfo(localize("Slideshow"), INFO_MS)
        schedule()
        screenHold.update(holdsScreen())
        prefetch()
    }

    private fun stop() {
        handler.removeCallbacks(nextSlide)
        order = null
        paused = false
        screenHold.update(false)
    }

    private fun pause() {
        paused = true
        handler.removeCallbacks(nextSlide)
        screenHold.update(false)
        showInfo(localize("Paused"), INFO_MS)
    }

    private fun resume() {
        paused = false
        showInfo(localize("Slideshow"), INFO_MS)
        schedule()
        screenHold.update(holdsScreen())
    }

    private fun schedule() {
        handler.removeCallbacks(nextSlide)
        val ms = PhotoSettings.slideMs(prefs)
        if (ms > 0 && isRunning()) handler.postDelayed(nextSlide, ms)
    }

    private fun onSlideTime() {
        val o = order ?: return
        if (!shown || paused) return
        val next = o.next()
        if (next < 0) {
            // The end without Repeat: the last photo stays
            stop()
            showInfo(localize("Slideshow ended"), INFO_MS)
            return
        }
        show(next, PhotoSettings.transition(prefs), 1)
        schedule()
    }

    // --- Keys ---

    private fun step(delta: Int) {
        val n = photos.size
        if (n == 0) return
        val o = order
        val to = if (o != null) {
            o.step(delta).takeIf { it >= 0 } ?: return
        } else {
            val t = index + delta
            when {
                t in 0 until n -> t
                RowFocus.isLoopOn(activity) -> (t + n) % n
                else -> return
            }
        }
        if (to == index) return
        clickFeedback()
        info.visibility = View.GONE
        show(to, PhotoSettings.TRANSITION_OFF, if (delta > 0) 1 else -1)
        if (o != null && !paused) schedule()
    }

    // The wheel moves one photo for each step
    override fun wheelKind(): WheelKind = WheelKind.VALUE

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        step(delta)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (down) step(if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down) {
                    if (event.repeatCount == 0) {
                        centreDownAt = SystemClock.uptimeMillis()
                        centreHeld = false
                        centreOwn = true
                    } else if (centreOwn && !centreHeld && SystemClock.uptimeMillis() - centreDownAt >= HOLD_MS) {
                        centreHeld = true
                        clickFeedback()
                        showPhotoInfo()
                    }
                } else if (centreOwn.also { centreOwn = false } && !centreHeld && !event.isCanceled) {
                    clickFeedback()
                    when {
                        order == null -> start()
                        paused -> resume()
                        else -> pause()
                    }
                }
                return true
            }
        }
        return false
    }

    private fun showPhotoInfo() {
        val p = photos.getOrNull(index) ?: return
        val lines = ArrayList<String>()
        lines += p.name
        lines += DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.sortDate))
        val b = PhotoDecoder.bounds(p.path)
        val dims = if (b != null) {
            val turned = com.themoon.y1.photos.ExifOrientation.swapsSides(p.orientation)
            (if (turned) "${b[1]} × ${b[0]}" else "${b[0]} × ${b[1]}") + " · "
        } else ""
        lines += dims + Formatter.formatShortFileSize(activity, p.size)
        lines += String.format(localize("%1\$d of %2\$d"), index + 1, photos.size)
        showInfo(lines.joinToString("\n"), INFO_LONG_MS)
        infoIsPhoto = true
    }

    private fun showInfo(text: String, ms: Long) {
        infoIsPhoto = false
        info.text = text
        info.typeface = ThemeManager.getCustomFont()
        info.visibility = View.VISIBLE
        info.bringToFront()
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, ms)
    }

    private companion object {
        const val HOLD_MS = 600L
        const val HOLD_TICK_MS = 5_000L
        const val DISSOLVE_MS = 500L
        const val PUSH_MS = 400L
        const val INFO_MS = 1_500L
        const val INFO_LONG_MS = 4_000L
        const val HINT_MS = 2_500L
    }
}
