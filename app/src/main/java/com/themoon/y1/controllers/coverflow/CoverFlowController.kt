package com.themoon.y1.controllers.coverflow

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.PadWheel
import com.themoon.y1.navigation.RowFocus
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.WheelKind
import com.themoon.y1.views.CoverFlowLayout
import com.themoon.y1.views.SmoothEdgeImageView

/**
 * Controller for the 3D CoverFlow carousel view.
 * Uses a 9-slot sliding buffer (-4 to +4) to prevent pop-in at the edges,
 * renders reflected album cards, and binds album art asynchronously via AlbumCoverManager.
 */
class CoverFlowController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val listVirtualSongs: View?,
    private val uniqueAlbumList: List<SongItem>,
    private val clickFeedback: () -> Unit,
    private val onAlbumSelected: (albumItem: SongItem) -> Unit,
    private val onRebuildUniqueAlbumList: (() -> Unit)? = null,
    /** The library song in Now Playing (playing or paused), or null: Cover Flow opens at its album (see [CoverFlowStart]). */
    private val nowPlayingSong: () -> SongItem? = { null },
    private val localize: (String) -> String
) : ScreenController {

    private val containerBrowserItems: LinearLayout = rootView.findViewById(R.id.container_browser_items)
    private val scrollViewBrowser: View? = containerBrowserItems.parent as? View
    private val tvStatusTitle: TextView? by lazy(LazyThreadSafetyMode.NONE) { activity.findViewById(R.id.tv_status_clock) }
    /** True while [statusTitleTask] waits for the next frame: several steps in one frame set the title one time. */
    private var statusTitlePending = false
    private val statusTitleTask = Runnable {
        statusTitlePending = false
        showStatusTitle()
    }

    private val visibleCoversCount = 9
    var currentCoverFlowIndex = 0

    private var coverFlowContainer: CoverFlowLayout? = null
    private var cfViews: Array<View?>? = null

    private var coverFlowActiveDirection = 0
    private var coverFlowDirectionChangeTime = 0L
    private var lastCoverFlowWheelTime = 0L

    // Speed scrolling: after a fast wheel turn, the wheel moves through the artist letters (see CoverFlowLetterJump).
    private val letterJump = CoverFlowLetterJump(uniqueAlbumList, object : CoverFlowLetterJump.Ui {
        override fun showLetter(letter: String) { MainActivity.instance?.showJumpLetter(letter) }
        override fun hideLetter() { MainActivity.instance?.hideFastScrollLetter() }
        override fun goTo(index: Int) = settleOnAlbum(index)
        override fun click() = clickFeedback()
        override fun postDelayed(task: Runnable, delayMs: Long) { rootView.postDelayed(task, delayMs) }
        override fun cancel(task: Runnable) { rootView.removeCallbacks(task) }
    }) { RowFocus.letterJumpSensitivity(activity) }

    // The card motion of the wheel steps and of the fly-by after a letter jump (see CoverFlowMotion). Positions are
    // album numbers that can be fractional, and can be outside 0 until total (the cards show them modulo total).
    private val motion = CoverFlowMotion()
    /** The album number of the centre card (views[centre]); the other cards have the next albums. */
    private var cardsBase = 0
    /** The album number nearest to the centre at the last placement: when it changes, the drawing order changes. */
    private var placedCentre = 0
    /** True while [motionFrame] waits for the next frame. */
    private var framePosted = false
    private val motionFrame = Choreographer.FrameCallback { frameTimeNanos -> onMotionFrame(frameTimeNanos / 1_000_000L) }
    // Card position, angle, scale and alpha at each slot (-4..4), made one time with the cards: no trigonometry in a frame.
    private val slotX = FloatArray(visibleCoversCount)
    private val slotRotY = FloatArray(visibleCoversCount)
    private val slotScale = FloatArray(visibleCoversCount)
    private val slotAlpha = FloatArray(visibleCoversCount)

    /** The song list of the album that was opened from Cover Flow, or null. */
    private var albumListState: ScreenState? = null
    /** The random start album that [prepareCovers] chose and prepared, or -1. */
    private var preparedRandomStart = -1

    override fun onEnter(state: ScreenState) {
        if (uniqueAlbumList.isEmpty()) onRebuildUniqueAlbumList?.invoke()
        // Only Back from the song list of the album that Cover Flow opened keeps that album.
        val back = albumListState
        albumListState = null
        if (back == null || NavigationManager.getInstance().previousScreen != back) currentCoverFlowIndex = startIndex()
        buildCoverFlowUI()
    }

    /** The album of the Now Playing song; without one, a random album (the one that was prepared, if any). */
    private fun startIndex(): Int {
        val random = preparedRandomStart
        preparedRandomStart = -1
        val playing = CoverFlowStart.albumIndex(uniqueAlbumList, nowPlayingSong())
        return when {
            playing >= 0 -> playing
            random in uniqueAlbumList.indices -> random
            else -> CoverFlowStart.startIndex(uniqueAlbumList, null)
        }
    }

    private fun openAlbum(album: SongItem) {
        onAlbumSelected(album)
        albumListState = NavigationManager.getInstance().currentState
    }

    override fun onRefreshUI() {
        buildCoverFlowUI()
    }

    override fun onLibraryLoaded() {
        buildCoverFlowUI()
    }

    override fun onThemeChanged() {
        // A rebuild takes about 1.2 s; only the card texts take theme colours
        for (card in cfViews ?: return) {
            val state = card?.tag as? CardState ?: continue
            state.title.setTextColor(ThemeManager.getTextColorPrimary())
            state.title.typeface = ThemeManager.getCustomFontBold()
            state.artist.setTextColor(ThemeManager.getTextColorSecondary())
        }
    }

    override fun onExit() {
        letterJump.reset()
        stopMotion()
        rootView.removeCallbacks(statusTitleTask)
        statusTitlePending = false
        // Keep the layout and its cards for the next entry (no new views and bitmaps).
        containerBrowserItems.removeAllViews()
    }

    fun buildCoverFlowUI() {
        letterJump.reset()
        stopMotion()
        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        statusBar?.setBackgroundColor(0x00000000)

        // Ensure split-screen right cover pane from browser mode is hidden in Cover Flow
        rootView.findViewById<View>(R.id.frame_browser_cover)?.visibility = View.GONE

        scrollViewBrowser?.visibility = View.VISIBLE
        if (scrollViewBrowser is ScrollView) {
            scrollViewBrowser.scrollTo(0, 0)
        }
        listVirtualSongs?.visibility = View.GONE
        containerBrowserItems.removeAllViews()

        if (uniqueAlbumList.isEmpty()) {
            onRebuildUniqueAlbumList?.invoke()
        }

        // A scan pre-generates by itself; here only when the album list changed (for example after a sync).
        checkThumbnails()

        if (uniqueAlbumList.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No Albums Found")
                textSize = 18f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            containerBrowserItems.addView(tvEmpty)
            return
        }

        if (currentCoverFlowIndex >= uniqueAlbumList.size) {
            currentCoverFlowIndex = 0
        }

        val d = activity.resources.displayMetrics.density
        val kept = cfViews != null
        ensureCards()
        val cfLayout = coverFlowContainer ?: return
        (cfLayout.parent as? ViewGroup)?.removeView(cfLayout)
        containerBrowserItems.clipChildren = false
        containerBrowserItems.clipToPadding = false
        if (scrollViewBrowser is ViewGroup) {
            scrollViewBrowser.clipChildren = false
            scrollViewBrowser.clipToPadding = false
        }

        // Fill the space below the status bar (the ScrollView fills its viewport), so that
        // the cards are placed in it for each status bar height.
        containerBrowserItems.addView(
            cfLayout,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        if (kept) {
            // The theme can have changed since the cards were made.
            cfViews?.forEach { card -> (card?.tag as? CardState)?.let { styleCardTexts(it, d) } }
        }

        initCoverFlowPositions()
    }

    /** The Cover Flow layout and its cards: made one time and kept. */
    private fun ensureCards(): Array<View?> {
        cfViews?.let { return it }
        val d = activity.resources.displayMetrics.density
        val cfLayout = coverFlowContainer ?: CoverFlowLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            // The cover top is at the iPod position for the space below the status bar.
            childTopForHeight = { height -> (CoverFlowGeometry.coverTop((height / d).toInt()) * d).toInt() }
            // Changes in a card do not lay out the full window.
            isLayoutBoundary = true
        }
        coverFlowContainer = cfLayout
        val views = arrayOfNulls<View>(visibleCoversCount)
        for (i in 0 until visibleCoversCount) {
            val card = createSingleCoverView()
            views[i] = card
            cfLayout.addView(card)
        }
        cfViews = views
        makeSlots()
        return views
    }

    /** The album index that card [slot] shows (the centre card shows the current album). */
    private fun cardIndex(slot: Int): Int {
        val total = uniqueAlbumList.size
        return (currentCoverFlowIndex + slot - visibleCoversCount / 2 + total * 10) % total
    }

    // --- preparing the first entry ---

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prepareTask = Runnable { prepareCovers() }
    private var scratch: Bitmap? = null
    /** False after a library load until the thumbnail check ran for the new album list (see [checkThumbnails]). */
    @Volatile
    private var thumbnailsChecked = false

    /**
     * Does the slow work of the first entry before the user opens Cover Flow, so that the first entry after an app
     * start or a library change is as fast as the next ones (Y2: before, up to 2.5 s with no frame). That work: the
     * thumbnail check of all albums (when the album list changed), the covers around the current album, the
     * placeholder, and the cards with their art bitmaps. On Android 4.4 a bitmap allocation often starts a full
     * garbage collection (~60 ms, all threads stop). Call it after each library load, from any thread: it starts
     * [PREPARE_DELAY_MS] later, and prepares one card for each main-thread message, so keys still go through.
     */
    fun prepareSoon() {
        thumbnailsChecked = false
        mainHandler.removeCallbacks(prepareTask)
        mainHandler.postDelayed(prepareTask, PREPARE_DELAY_MS)
    }

    private fun isOpen() = NavigationManager.getInstance().currentState is ScreenState.CoverFlow

    /**
     * Starts the thumbnail check of all albums one time for each album list (the list changes only at a library
     * load, which calls [prepareSoon]). The check makes a key for each album on a new thread: at each entry, that
     * was garbage and CPU time in the first steps.
     */
    private fun checkThumbnails() {
        if (thumbnailsChecked) return
        val manager = AlbumCoverManager.getInstance()
        // A check that runs now can be for the old list: check again at the next entry.
        if (manager.isPregenerating()) return
        thumbnailsChecked = true
        manager.pregenerateThumbnailsIfChanged(uniqueAlbumList)
    }

    private fun prepareCovers() {
        if (uniqueAlbumList.isEmpty() || isOpen()) return
        if (albumListState == null) {
            // The album that the next entry opens at.
            val playing = CoverFlowStart.albumIndex(uniqueAlbumList, nowPlayingSong())
            preparedRandomStart = if (playing >= 0) -1 else CoverFlowStart.startIndex(uniqueAlbumList, null)
            currentCoverFlowIndex = if (playing >= 0) playing else preparedRandomStart
        }
        if (currentCoverFlowIndex >= uniqueAlbumList.size) currentCoverFlowIndex = 0
        val manager = AlbumCoverManager.getInstance()
        checkThumbnails()
        // Decoded on the first cover miss otherwise: an icon decode and a bitmap allocation in the first steps.
        manager.getPlaceholder(activity)
        // The covers that the first steps show, in the memory cache (requested first: the worker takes the newest first).
        prefetchAround(currentCoverFlowIndex, visibleCoversCount / 2 + PREFETCH_AHEAD, visibleCoversCount / 2 + 1)
        // The covers of the cards first (worker thread), then the cards with them (no placeholder art to draw first).
        val items = (0 until visibleCoversCount).map { uniqueAlbumList[cardIndex(it)] }.filter { it.file != null }
        var waiting = items.size
        if (waiting == 0) {
            prepareCard(0)
            return
        }
        for (item in items) {
            manager.loadCoverAsync(item, object : AlbumCoverManager.CoverCallback {
                override fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?) {
                    if (--waiting == 0) prepareCard(0)
                }
            })
        }
    }

    /**
     * Binds card [slot], and measures, lays out and draws it off screen: this makes its text layouts and the art
     * bitmaps of its [SmoothEdgeImageView]s now, at their Cover Flow sizes. The entry then only binds the same bitmaps.
     */
    private fun prepareCard(slot: Int) {
        if (uniqueAlbumList.isEmpty() || isOpen()) {
            scratch = null
            return
        }
        val views = ensureCards()
        val card = views[slot] ?: return
        bindCover(card, cardIndex(slot))
        (card.tag as? CardState)?.let { bindText(it) }
        card.measure(
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        // A canvas of the card size (one byte a pixel), so that no child is outside the clip and skipped.
        val bmp = scratch?.takeIf { it.width >= card.measuredWidth && it.height >= card.measuredHeight }
            ?: Bitmap.createBitmap(card.measuredWidth.coerceAtLeast(1), card.measuredHeight.coerceAtLeast(1), Bitmap.Config.ALPHA_8)
        scratch = bmp
        card.draw(Canvas(bmp))
        if (slot + 1 < views.size) mainHandler.post { prepareCard(slot + 1) } else scratch = null
    }

    // --- cover loading ---

    /** Album keys whose cover load runs, with the start time: one load for each album, also for many requests. */
    private val coverLoads = HashMap<String, Long>()

    /**
     * Loads the cover of album [index] into the memory cache in the background, when it is not there (one load for
     * each album). When it comes, the cards that show that album get it.
     */
    private fun requestCover(index: Int) {
        val item = uniqueAlbumList.getOrNull(index) ?: return
        if (item.file == null) return
        val key = AlbumCoverManager.getAlbumKey(item.file, item.album)
        val manager = AlbumCoverManager.getInstance()
        // In the cache already (for example from the warm-up load, whose callback does not update the cards): show it
        val cached = manager.getMemoryCover(key)
        if (cached != null) {
            showLoadedCover(key, cached)
            return
        }
        if (manager.hasNoArt(key)) return
        val now = SystemClock.uptimeMillis()
        val started = coverLoads[key]
        // A load that did not come back in time (it failed) does not block a new one.
        if (started != null && now - started < COVER_LOAD_TIMEOUT_MS) return
        coverLoads[key] = now
        manager.loadCoverAsync(item, object : AlbumCoverManager.CoverCallback {
            override fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?) {
                coverLoads.remove(key)
                showLoadedCover(key, cover)
            }
        })
    }

    /** The cards that show the album [key] (and still the placeholder) get its cover, or no reflection. */
    private fun showLoadedCover(key: String, cover: Bitmap?) {
        for (card in cfViews ?: return) {
            val state = card?.tag as? CardState ?: continue
            if (state.cover.tag != key) continue
            if (cover != null) setBitmapIfChanged(state.cover, cover)
            showReflection(state.reflection, cover)
        }
    }

    /**
     * Requests the covers [from] to [to] albums from album [centre], on both sides. The farthest first: the cover
     * worker takes the newest request first, so the nearest come first.
     */
    private fun prefetchAround(centre: Int, to: Int, from: Int) {
        val total = uniqueAlbumList.size
        if (total == 0) return
        for (d in to downTo from) {
            requestCover(wrap(centre + d, total))
            requestCover(wrap(centre - d, total))
        }
    }

    private fun initCoverFlowPositions() {
        val total = uniqueAlbumList.size
        if (total == 0) return
        val views = cfViews ?: return

        stopMotion()
        motion.reset(currentCoverFlowIndex)
        cardsBase = currentCoverFlowIndex
        placedCentre = currentCoverFlowIndex
        // The covers that the first steps show, either way. Before the cards: the cover worker takes the newest
        // request first, so a card cover that is not in memory comes first.
        prefetchAround(currentCoverFlowIndex, visibleCoversCount / 2 + PREFETCH_AHEAD, visibleCoversCount / 2 + 1)
        for (i in 0 until visibleCoversCount) {
            views[i]?.let { bindCover(it, cardIndex(i)) }
        }
        placeCards(motion.pos)
        // The drawing order of the new positions.
        coverFlowContainer?.invalidate()

        rootView.removeCallbacks(statusTitleTask)
        statusTitlePending = false
        showStatusTitle()
    }

    /** "Cover Flow (n / total)" in the status bar. */
    private fun showStatusTitle() {
        val total = uniqueAlbumList.size
        if (total == 0) return
        tvStatusTitle?.text = CoverFlowTitle.format(localize("Cover Flow"), currentCoverFlowIndex + 1, total)
    }

    /** Sets the status title at the next frame, one time for all the steps before it. */
    private fun showStatusTitleSoon() {
        if (statusTitlePending) return
        statusTitlePending = true
        rootView.postOnAnimation(statusTitleTask)
    }

    private fun getTransXForDist(dist: Int, d: Float): Float {
        return CoverFlowGeometry.translationX(dist, sideTilt) * d
    }

    private fun getRotYForDist(dist: Int): Float {
        return if (dist == 0) 0f else sideTilt.rotationY
    }

    private fun getScaleForDist(dist: Int): Float {
        return if (dist == 0) 1.0f else sideTilt.scale
    }

    private fun getAlphaForDist(dist: Int): Float {
        return if (dist <= CoverFlowGeometry.SIDE_COUNT) 1.0f else 0.0f
    }

    /**
     * Shows the cover of album [dataIndex] on [card]: from the memory cache, else the placeholder until the cover
     * loads. The texts stay as they are: [bindText] sets them when they show.
     */
    private fun bindCover(card: View, dataIndex: Int) {
        if (uniqueAlbumList.isEmpty() || dataIndex < 0 || dataIndex >= uniqueAlbumList.size) return
        val item = uniqueAlbumList[dataIndex]
        val state = card.tag as? CardState ?: return
        state.album = dataIndex
        // The album at an index can change (library load): the texts are set again when the card shows them
        state.textAlbum = -1
        val ivCover = state.cover

        val albumKey = AlbumCoverManager.getAlbumKey(item.file, item.album)
        ivCover.tag = albumKey

        val manager = AlbumCoverManager.getInstance()
        val cachedBmp = manager.getMemoryCover(albumKey)

        if (cachedBmp != null) {
            setBitmapIfChanged(ivCover, cachedBmp)
            showReflection(state.reflection, cachedBmp)
            return
        }

        // Albums without art, and albums not yet loaded, show the placeholder (decoded one time, thumbnail size).
        setBitmapIfChanged(ivCover, manager.getPlaceholder(activity))
        showReflection(state.reflection, null)
        requestCover(dataIndex)
    }

    /** Sets the album and artist texts of the album that the card shows. */
    private fun bindText(state: CardState) {
        val item = uniqueAlbumList.getOrNull(state.album) ?: return
        state.textAlbum = state.album
        setTextIfChanged(state.title, item.album)
        // The album artist, as on the iPod ("Various Artists" for a compilation), not the artist of the first track.
        setTextIfChanged(state.artist, CoverFlowOrder.artistOf(item))
    }

    /**
     * Shows the reflection of [cover] (drawn from the cover, no reflection bitmap), or hides the view with
     * alpha 0 (not drawn) and keeps its old cover. The view never changes size: no requestLayout, which on
     * Android 4.4 lays out the full window. setVisibility is not used for the same reason.
     */
    private fun showReflection(view: ReflectionView, cover: Bitmap?) {
        if (cover != null) {
            view.setCover(cover)
            view.alpha = 1.0f
        } else {
            view.alpha = 0f
        }
    }

    /** Each setText makes a new text layout, also for the same text. */
    private fun setTextIfChanged(tv: TextView, text: String) {
        if (!TextUtils.equals(tv.text, text)) tv.text = text
    }

    /** Sets [bmp] only when the view does not already show it (a new drawable makes the card draw its art again). */
    private fun setBitmapIfChanged(iv: ImageView, bmp: Bitmap?) {
        if ((iv.drawable as? BitmapDrawable)?.bitmap !== bmp || (bmp == null && iv.drawable != null)) {
            iv.setImageBitmap(bmp)
        }
    }

    private fun createSingleCoverView(): View {
        val d = activity.resources.displayMetrics.density
        val coverSize = (CoverFlowGeometry.COVER_SIZE * d).toInt()
        // The cover is at the top of the card, the reflection directly under it, and the text on the reflection.
        // No off-screen buffer when a card fades: its children do not overlap where it is not opaque
        // (the edge cards have no text).
        val card = object : FrameLayout(activity) {
            override fun hasOverlappingRendering(): Boolean = false
        }

        // CoverFlowLayout puts the top of the card (the cover top) at the iPod position.
        val lp = FrameLayout.LayoutParams((CoverFlowGeometry.TEXT_WIDTH * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        card.layoutParams = lp
        // Scale and turn the side cards about the cover centre (x) and the iPod vanishing line (y).
        // Setting one pivot stops Android from centring the other, so set both.
        card.pivotX = lp.width / 2f
        card.pivotY = coverSize * CoverFlowGeometry.PIVOT_Y_FRACTION
        // The camera distance sets the perspective (Android: distance / densityDpi inches, 72 px per inch).
        card.cameraDistance = sideTilt.cameraDistancePx * d * activity.resources.displayMetrics.densityDpi / 72f
        card.setLayerType(View.LAYER_TYPE_NONE, null)

        // Smooth edges while the cards turn (see SmoothEdges)
        val ivCover = SmoothEdgeImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(coverSize, coverSize).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val ivReflection = ReflectionView().apply {
            layoutParams = FrameLayout.LayoutParams(coverSize, (CoverFlowGeometry.REFLECTION_HEIGHT * d).toInt()).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = coverSize
            }
            alpha = 0f
        }

        // Album (bold, primary colour: black on the Classic themes), then artist (regular, secondary colour),
        // on the iPod baselines. No view ids: the Y2 ROM calls View.toString() when it draws a TextView, and that
        // looks up the name of the id (see ids.xml).
        val tvTitle = createCardText()
        val tvArtist = createCardText()
        val state = CardState(ivCover, ivReflection, tvTitle, tvArtist)
        styleCardTexts(state, d)
        card.tag = state

        card.addView(ivCover)
        card.addView(ivReflection)
        card.addView(tvTitle)
        card.addView(tvArtist)

        card.setOnClickListener { v ->
            val centerIdx = visibleCoversCount / 2
            val views = cfViews
            if (views != null && v == views[centerIdx] && currentCoverFlowIndex in uniqueAlbumList.indices) {
                clickFeedback()
                val chosen = uniqueAlbumList[currentCoverFlowIndex]
                openAlbum(chosen)
            }
        }

        return card
    }

    /** One line of centred text, styled by [styleCardText]. */
    private fun createCardText(): TextView {
        return CardText().apply {
            includeFontPadding = false
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun styleCardTexts(state: CardState, d: Float) {
        styleCardText(state.title, CoverFlowGeometry.TITLE_SIZE * d, CoverFlowGeometry.TITLE_BASELINE * d, bold = true)
        styleCardText(state.artist, CoverFlowGeometry.ARTIST_SIZE * d, CoverFlowGeometry.ARTIST_BASELINE * d, bold = false)
    }

    /** Theme colour and font (bold or regular), with the baseline [baseline] px below the card top. Sets only what changed. */
    private fun styleCardText(tv: TextView, sizePx: Float, baseline: Float, bold: Boolean) {
        tv.setTextColor(if (bold) ThemeManager.getTextColorPrimary() else ThemeManager.getTextColorSecondary())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        tv.typeface = if (bold) ThemeManager.getCustomFontBold() else ThemeManager.getCustomFont()
        // Without font padding, the baseline is -ascent below the top of the view.
        val top = Math.round(baseline + tv.paint.fontMetricsInt.ascent)
        val lp = tv.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.topMargin != top) {
            lp.topMargin = top
            tv.layoutParams = lp
        }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        handleCoverFlowWheelInput(if (delta > 0) 22 else 21, event)
        return true
    }

    override fun wheelKind(): WheelKind = if (letterJump.isActive) WheelKind.LETTERS else WheelKind.VALUE

    override fun onWheelTouch(isTouching: Boolean, timeMs: Long) = letterJump.onTouch(isTouching, timeMs)

    private fun handleCoverFlowWheelInput(keyCode: Int, event: KeyEvent) {
        val dir = if (keyCode == 22) 1 else -1
        val eventTime = event.eventTime
        val now = SystemClock.uptimeMillis()

        if (now - lastCoverFlowWheelTime > 250) {
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
        } else if (coverFlowActiveDirection != 0 && dir != coverFlowActiveDirection) {
            // The motion turns at once (CoverFlowMotion: a step the other way starts a new run).
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
        } else if (coverFlowActiveDirection == 0) {
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
        }

        if (dir != coverFlowActiveDirection || eventTime < coverFlowDirectionChangeTime) {
            return
        }

        val lag = now - eventTime
        lastCoverFlowWheelTime = now
        if (letterJump.onStep(dir, eventTime, currentCoverFlowIndex, PadWheel.stepSpeed)) return
        if (cfViews != null && uniqueAlbumList.isNotEmpty()) {
            // Only the target changes; the next frame moves the cards (also for many steps before it).
            motion.step(dir, eventTime)
            onTargetChanged()
            // The cover that comes in at the outer slot PREFETCH_AHEAD steps later (the nearer ones came before).
            requestCover(wrap(motion.target + dir * (visibleCoversCount / 2 + PREFETCH_AHEAD), uniqueAlbumList.size))
        }

        if (lag <= 40) {
            clickFeedback()
        }
    }

    /** End of a letter jump: the covers scroll past fast to album [index]. */
    private fun settleOnAlbum(index: Int) {
        val total = uniqueAlbumList.size
        if (index !in 0 until total) return
        if (motion.flying || index != currentCoverFlowIndex) {
            // In the numbers of the motion (its target can be outside 0 until total after wheel steps).
            flyTo(index + motion.target - wrap(motion.target, total))
        }
    }

    /**
     * Starts a fly-by to album [target] from the position on the screen, or changes the target of the running
     * fly-by (the covers continue from where they are). The album count and select use [target] immediately.
     */
    private fun flyTo(target: Int) {
        if (cfViews == null || uniqueAlbumList.isEmpty()) return
        motion.flyTo(target)
        onTargetChanged()
        // The covers of the cards where the fly-by stops (the nearest to the centre first).
        prefetchAround(target, visibleCoversCount / 2, 0)
    }

    /** The album index, the status title and the motion follow the new target. */
    private fun onTargetChanged() {
        currentCoverFlowIndex = wrap(motion.target, uniqueAlbumList.size)
        showStatusTitleSoon()
        if (!framePosted) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(motionFrame)
        }
    }

    private fun onMotionFrame(frameTimeMs: Long) {
        framePosted = false
        val moving = motion.frame(frameTimeMs)
        placeCards(motion.pos)
        if (moving) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(motionFrame)
            return
        }
        // At rest: back to album numbers in 0 until total (the cards keep their albums).
        val total = uniqueAlbumList.size
        if (total == 0) return
        val laps = motion.target - wrap(motion.target, total)
        if (laps != 0) {
            motion.shift(-laps)
            cardsBase -= laps
            placedCentre -= laps
        }
    }

    /** Stops the motion where it is; [initCoverFlowPositions] puts the cards at rest again. */
    private fun stopMotion() {
        if (framePosted) {
            framePosted = false
            Choreographer.getInstance().removeFrameCallback(motionFrame)
        }
        motion.reset(motion.target)
    }

    /**
     * Puts the cards at position [pos] (album number, can be fractional). The cards move in the views array as in
     * a wheel step; only the cards that come in at the outer slot get a new album (from the cover cache, else the
     * placeholder).
     */
    private fun placeCards(pos: Float) {
        val views = cfViews ?: return
        val total = uniqueAlbumList.size
        if (total == 0) return
        val centerIdx = visibleCoversCount / 2
        val base = Math.floor(pos.toDouble()).toInt()
        val shift = base - cardsBase
        if (shift >= visibleCoversCount || shift <= -visibleCoversCount) {
            for (i in 0 until visibleCoversCount) views[i]?.let { bindCover(it, wrap(base + i - centerIdx, total)) }
        } else if (shift > 0) {
            repeat(shift) {
                val first = views[0]
                for (i in 0 until visibleCoversCount - 1) views[i] = views[i + 1]
                views[visibleCoversCount - 1] = first
            }
            for (i in visibleCoversCount - shift until visibleCoversCount) {
                views[i]?.let { bindCover(it, wrap(base + i - centerIdx, total)) }
            }
        } else if (shift < 0) {
            repeat(-shift) {
                val last = views[visibleCoversCount - 1]
                for (i in visibleCoversCount - 1 downTo 1) views[i] = views[i - 1]
                views[0] = last
            }
            for (i in 0 until -shift) views[i]?.let { bindCover(it, wrap(base + i - centerIdx, total)) }
        }
        cardsBase = base
        // CoverFlowLayout draws the card nearest to the centre last (on top), in the order of its last draw: a new
        // centre card must make it draw again (a card property change does not).
        val centre = Math.round(pos)
        if (centre != placedCentre) {
            placedCentre = centre
            coverFlowContainer?.invalidate()
        }
        val frac = pos - base
        for (i in 0 until visibleCoversCount) views[i]?.let { placeCard(it, i - centerIdx - frac) }
    }

    /**
     * Puts [card] at [offset] albums from the centre: between two slots, the values are interpolated. A card that
     * stays hidden (alpha 0) is not changed: each View setter invalidates up to the window. The text shows near the
     * centre ([CoverFlowMotion.textAlpha]); it is set when it starts to show.
     */
    private fun placeCard(card: View, offset: Float) {
        val maxDist = visibleCoversCount / 2
        val o = offset.coerceIn(-maxDist.toFloat(), maxDist.toFloat())
        val k = Math.floor(o.toDouble()).toInt()
        val t = o - k
        val a = k + maxDist
        val b = if (t > 0f) a + 1 else a
        // A card at alpha 0 is not drawn. Not setVisibility: on Android 4.4 it lays out the full window.
        val alpha = slotAlpha[a] + (slotAlpha[b] - slotAlpha[a]) * t
        if (alpha <= 0f && card.alpha <= 0f) return
        // The setters do nothing for an equal value: a side card that moves between side slots changes only x.
        card.translationX = slotX[a] + (slotX[b] - slotX[a]) * t
        card.rotationY = slotRotY[a] + (slotRotY[b] - slotRotY[a]) * t
        val scale = slotScale[a] + (slotScale[b] - slotScale[a]) * t
        card.scaleX = scale
        card.scaleY = scale
        card.alpha = alpha
        val state = card.tag as? CardState ?: return
        val textAlpha = CoverFlowMotion.textAlpha(offset, motion.textLevel)
        if (textAlpha > 0f && state.textAlbum != state.album) bindText(state)
        state.title.alpha = textAlpha
        state.artist.alpha = textAlpha
    }

    /** The card values at each slot: made one time with the cards, no trigonometry for each step. */
    private fun makeSlots() {
        val d = activity.resources.displayMetrics.density
        val centerIdx = visibleCoversCount / 2
        for (i in 0 until visibleCoversCount) {
            val dist = Math.abs(i - centerIdx)
            val sign = if (i < centerIdx) -1f else 1f
            slotX[i] = sign * getTransXForDist(dist, d)
            slotRotY[i] = -sign * getRotYForDist(dist)
            slotScale[i] = getScaleForDist(dist)
            slotAlpha[i] = getAlphaForDist(dist)
        }
    }

    private fun wrap(index: Int, total: Int): Int = ((index % total) + total) % total
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Letter mode: a centre press only ends it; Back and Play/Pause end it, then work as usual.
        if (letterJump.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)) return true
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == 21 || event.keyCode == 22)) {
            handleCoverFlowWheelInput(event.keyCode, event)
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == 21 || keyCode == 22) {
            handleCoverFlowWheelInput(keyCode, event)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Open the album on release (onKeyUp); on press, the release went to the album list and played a song
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentCoverFlowIndex in uniqueAlbumList.indices) {
                clickFeedback()
                val chosen = uniqueAlbumList[currentCoverFlowIndex]
                openAlbum(chosen)
            }
            return true
        }
        return false
    }

    /** Views of a card, the album that it shows, and the album of its texts (kept in card.tag). */
    private class CardState(
        val cover: SmoothEdgeImageView,
        val reflection: ReflectionView,
        val title: TextView,
        val artist: TextView
    ) {
        var album = -1
        var textAlbum = -1
    }

    /**
     * A card text. Its alpha is a display-list property, as for other views: a TextView with no background otherwise
     * draws the alpha itself, so each alpha change (each frame while it fades) records its text again.
     */
    private inner class CardText : TextView(activity) {
        override fun onSetAlpha(alpha: Int): Boolean = false
    }

    /**
     * The floor reflection of a card. It draws its [CoverReflectionDrawable] directly, so the GPU draws it
     * (not Skia into a software bitmap that the GPU must then load again), and keeps it for all albums.
     */
    private inner class ReflectionView : View(activity) {
        private val reflection = CoverReflectionDrawable(AlbumCoverManager.THUMB_SIZE)

        fun setCover(cover: Bitmap) {
            if (reflection.setCover(cover)) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            reflection.setBounds(0, 0, width, height)
            reflection.draw(canvas)
        }
    }

    companion object {
        // After a library load: the start-up work and the main menu first
        private const val PREPARE_DELAY_MS = 3000L

        /**
         * Covers loaded into the memory cache before they show: this many albums past the outer slot, in the scroll
         * direction at each step, and on both sides at the entry. A miss shows the placeholder, then draws the card
         * art again when the cover comes (two software draws and two texture uploads).
         */
        private const val PREFETCH_AHEAD = 6

        /** A cover load that did not come back after this time does not block a new request for the album. */
        private const val COVER_LOAD_TIMEOUT_MS = 5000L

        // Scale, angle and perspective of the side covers (see CoverFlowGeometry)
        private val sideTilt = CoverFlowGeometry.sideTilt()
    }
}
