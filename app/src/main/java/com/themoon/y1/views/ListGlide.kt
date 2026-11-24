package com.themoon.y1.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.ScrollView
import com.themoon.y1.AppPrefs
import com.themoon.y1.Prefs
import com.themoon.y1.R
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Settings → Controls → List Glide (default On): at a wheel step the highlight glides to the new row, and the rows
 * glide when the list scrolls. Only a wheel step glides ([beforeStep]); a letter jump, a loop to the other end and
 * code that selects a row move at once. Each motion can change its target at the next step, so the wheel never waits.
 *
 * The rows have their own look for the focus (background and text colours), so the list draws the moving highlight
 * itself ([RowGlide]): a copy of the focused row's background, with pictures of the focused text of the rows under it,
 * and a picture of the plain look of the new row (taken before the focus moves) around it. The disclosure arrow
 * ([arrowOf]) is not in the pictures of the text: one picture of it moves with the highlight. Region clips need
 * Android 4.3 (API 18) with hardware drawing: not on the Y1 (Android 4.2).
 */
object ListGlide {
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 18

    fun isOn(context: Context): Boolean = isSupported && Prefs.LIST_GLIDE.get(AppPrefs.get(context))

    /**
     * A wheel step will move the focus in [rows] from row [from] to row [to]: the index of the row in a ScrollView
     * list (the rows in a LinearLayout), the adapter position in a ListView. Call it before the focus moves.
     */
    fun beforeStep(rows: ViewGroup, from: Int, to: Int) {
        val glide = (rows as? EdgeFadeListView)?.glide ?: (rows.parent as? EdgeFadeScrollView)?.glide ?: return
        glide.beforeStep(from, to)
    }

    /** Ends a glide in [rows] at once: the rows change their places (a row that the wheel moves). */
    fun cancel(rows: ViewGroup) {
        ((rows as? EdgeFadeListView)?.glide ?: (rows.parent as? EdgeFadeScrollView)?.glide)?.cancel()
    }

    /** Rates of the approach to the target: about 95 % of the way in 3 / rate seconds. */
    const val BAR_RATE = 24f // ≈125 ms
    const val SCROLL_RATE = 18f // ≈165 ms

    /** The highlight stays this many rows near its row at most: the pictures of rows further away are gone. */
    const val MAX_BAR_LAG_ROWS = 1.5f

    /** The rows stay this many rows near their place at most: a longer move goes at once. */
    const val MAX_SCROLL_LAG_ROWS = 2f

    /** One frame at 60 fps, in ns. */
    const val FRAME_NS = 16_666_667L

    /** The longest time step of one frame of the motion, in seconds (1.5 frames at 60 fps). */
    const val MAX_FRAME_DT = 0.025f

    /** A picture of a focused row is used again for this time at most (a marquee starts 1.2 s after the focus). */
    const val TEXT_PICTURE_NS = 1_000_000_000L

    /** A highlight move longer than this many rows (a loop to the other end) goes at once. */
    const val MAX_BAR_JUMP_ROWS = 2f

    /**
     * The disclosure arrow of a row that shows now ([R.id.list_arrow], [ClassyLists.arrow]), or null: a row with no
     * arrow, or a row that shows it only with the focus.
     */
    fun arrowOf(row: View): View? =
        row.findViewById<View>(R.id.list_arrow)?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }

    /**
     * Top of the arrow below the top of the highlight, for an arrow at [top] in a row of [rowHeight] (both with
     * [arrowHeight]) and a highlight of [barHeight]: its centre keeps its place in the height. With the highlight
     * on the row, it is where the row draws it.
     */
    fun arrowTop(top: Int, arrowHeight: Int, rowHeight: Int, barHeight: Int): Int {
        if (rowHeight <= 0 || barHeight == rowHeight) return top
        return Math.round((top + arrowHeight / 2f) * barHeight / rowHeight - arrowHeight / 2f)
    }

    /**
     * Alpha of the arrow on the highlight: the part of the highlight on rows with an arrow ([arrowPx]) of the part on
     * rows with a picture ([knownPx]). Between a row with an arrow and a row without one, the arrow fades.
     */
    fun arrowAlpha(arrowPx: Int, knownPx: Int): Int =
        if (knownPx <= 0) 0 else ((255 * arrowPx + knownPx / 2) / knownPx).coerceIn(0, 255)

    /** [value] after [dt] seconds on its way to [target] at [rate]; it ends at the target when less than 0.5 px away. */
    fun approach(value: Float, target: Float, rate: Float, dt: Float): Float {
        val next = value + (target - value) * (1f - exp(-rate * dt))
        return if (abs(target - next) < 0.5f) target else next
    }

    /** [value] kept within [maxLag] of [target]. */
    fun limit(value: Float, target: Float, maxLag: Float): Float = value.coerceIn(target - maxLag, target + maxLag)

    /**
     * How far the rows moved on the screen in a step: the change of the top of the first row (index) that is in
     * both lists, or null when no row is in both (the list went far).
     */
    fun shift(beforeIndex: IntArray, beforeTop: IntArray, afterIndex: IntArray, afterTop: IntArray): Int? {
        for (i in afterIndex.indices) {
            val j = beforeIndex.indexOf(afterIndex[i])
            if (j >= 0) return afterTop[i] - beforeTop[j]
        }
        return null
    }

    /**
     * The free picture for a [w] × [h] picture: one of that size, else (when [canResize]) the smallest that has room
     * for it ([bytes]); -1 for none. A new bitmap is a Dalvik heap allocation on Android 4.4: in the fragmented heap
     * of a large library it often costs a full GC (~100 ms on the Y2), a long stop of the glide.
     */
    fun pickPicture(widths: IntArray, heights: IntArray, bytes: IntArray, w: Int, h: Int, canResize: Boolean): Int {
        for (i in widths.indices) if (widths[i] == w && heights[i] == h) return i
        if (!canResize) return -1
        var best = -1
        for (i in bytes.indices) if (bytes[i] >= w * h * 4 && (best < 0 || bytes[i] < bytes[best])) best = i
        return best
    }

    /**
     * Pictures of rows, in bitmaps used again (a step takes two). Not recycled: the last frame can still draw them.
     * Bitmaps of other lists (other row sizes) are resized for the list in use (Android 4.4), not allocated again.
     */
    internal object Pool {
        private const val MAX_FREE = 8
        private val free = ArrayList<Bitmap>()
        private val canvas = Canvas()

        /**
         * A picture of [v] as it shows now, or null (no size, no memory). Without its background: only the text
         * and the icons (the background is hidden while the picture is taken).
         */
        fun picture(v: View, withBackground: Boolean): Bitmap? {
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) return null
            val b = take(w, h) ?: try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return null
            }
            b.eraseColor(0)
            canvas.setBitmap(b)
            val bg = if (withBackground) null else v.background
            bg?.alpha = 0
            v.draw(canvas)
            bg?.alpha = 255
            canvas.setBitmap(null)
            return b
        }

        private fun take(w: Int, h: Int): Bitmap? {
            val canResize = Build.VERSION.SDK_INT >= 19
            val i = pickPicture(
                IntArray(free.size) { free[it].width }, IntArray(free.size) { free[it].height },
                IntArray(free.size) { if (canResize) free[it].allocationByteCount else 0 }, w, h, canResize
            )
            if (i < 0) return null
            val b = free.removeAt(i)
            if (b.width == w && b.height == h) return b
            // Bitmap.reconfigure needs Android 4.4 (API 19); before it, pickPicture gives only a picture of the same size
            if (Build.VERSION.SDK_INT < 19) return null
            return try {
                b.reconfigure(w, h, Bitmap.Config.ARGB_8888)
                b
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** A picture of the part [r] of [src] (null: no memory); [r] of [src] is then transparent. */
        fun cut(src: Bitmap, r: Rect): Bitmap? {
            val w = r.width()
            val h = r.height()
            if (w <= 0 || h <= 0) return null
            val b = take(w, h) ?: try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                null
            }
            if (b != null) {
                b.eraseColor(0)
                canvas.setBitmap(b)
                canvas.drawBitmap(src, -r.left.toFloat(), -r.top.toFloat(), null)
            }
            canvas.setBitmap(src)
            canvas.save()
            canvas.clipRect(r)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.restore()
            canvas.setBitmap(null)
            return b
        }

        fun give(b: Bitmap) {
            if (free.size >= MAX_FREE) free.removeAt(0) // the oldest goes: the list in use keeps its pictures
            free.add(b)
        }
    }
}

/**
 * The glide of one list ([EdgeFadeListView] or [EdgeFadeScrollView]); the list calls [draw] from its dispatchDraw.
 * Positions are in screen pixels: from the top of the list view, not moved by the scroll.
 */
internal class RowGlide(private val host: ViewGroup) {

    /** A row on the screen: [index] as in [ListGlide.beforeStep], at its place after layout. */
    private class Row(val index: Int, val view: View, val left: Int, val top: Int) {
        val bottom: Int get() = top + view.height
    }

    private var active = false
    private var pending = false
    private var target = -1
    /** The rows show this far below their place (negative: above). */
    private var offset = 0f
    private var barTop = 0f
    private var barHeight = 0f
    private var lastNs = 0L
    private var beforeIndex = IntArray(0)
    private var beforeTop = IntArray(0)

    /**
     * The text of the rows in their focused look (no background), by index: the rows that the highlight can still
     * cover, and the [target] row from its first frame with the focus.
     */
    private val focusedText = LinkedHashMap<Int, Bitmap>()
    /** When each picture of [focusedText] was taken ([System.nanoTime]). */
    private val focusedTextAt = HashMap<Int, Long>()
    /** The rows of [focusedText] that have an arrow ([ListGlide.arrowOf]) in their focused look. */
    private val withArrow = HashSet<Int>()
    /**
     * The arrow in its focused look, from the last focused row with one; it moves with the highlight, at its place
     * in that row ([arrowLeft], [arrowTop] in a row of [arrowRowHeight]).
     */
    private var arrowLook: Bitmap? = null
    private var arrowLeft = 0
    private var arrowTop = 0
    private var arrowRowHeight = 0
    private val arrowPaint = Paint()
    /** The plain look of the [target] row, with its background. */
    private var targetLook: Bitmap? = null
    /**
     * A ListView row at the edge that the step scrolls away (its view goes to the next row at once), and its adapter
     * position: it shows in the gap while the rows glide. A picture costs less than a bind and a measure of the row.
     */
    private var edgeLook: Bitmap? = null
    private var edgeIndex = -1
    /** The highlight: a copy of the background of the focused [target] row. */
    private var bar: Drawable? = null

    /** ListView rows above or below the rows of the list: they show while the rows glide. By adapter position. */
    private val extraRows = HashMap<Int, View>()
    /** Extra rows not in use, by view type: the next extra row of that type binds into one (no new row view). */
    private val spareRows = HashMap<Int, ArrayList<View>>()
    private var spareAdapter: android.widget.ListAdapter? = null

    private val clip = Rect()

    fun beforeStep(from: Int, to: Int) {
        if (!ListGlide.isOn(host.context) || !host.isShown || host.height == 0) return cancel()
        val rows = rows()
        val fromRow = rows.firstOrNull { it.index == from }
        // A ListView row just past the list edge has no view yet (the rows above fill the list to the pixel): the rows
        // still glide, and the highlight moves with its row (no picture of the new row before the step)
        val toRow = rows.firstOrNull { it.index == to }
        if (toRow == null && host !is ListView) return cancel()
        if (!active) {
            fromRow ?: return cancel()
            barTop = fromRow.top.toFloat()
            barHeight = fromRow.view.height.toFloat()
            offset = 0f
            lastNs = System.nanoTime()
        }
        if (toRow != null && abs(toRow.top - barTop) > ListGlide.MAX_BAR_JUMP_ROWS * toRow.view.height) return cancel()
        // The picture of the old target from its first frame with the focus is still correct (a marquee starts later)
        if (fromRow != null && !hasFreshText(from)) keepText(from, fromRow.view)
        focusedText.remove(to)?.let { ListGlide.Pool.give(it) }
        targetLook?.let { ListGlide.Pool.give(it) }
        targetLook = if (toRow == null) null else ListGlide.Pool.picture(toRow.view, withBackground = true) ?: return cancel()
        keepEdge(rows, from, to, toRow)
        bar = null
        // Keep the first sample of a burst of steps before one frame: draw makes up for the whole move since it
        if (!pending) {
            beforeIndex = IntArray(rows.size) { rows[it].index }
            beforeTop = IntArray(rows.size) { rows[it].top }
        }
        target = to
        pending = true
        active = true
        host.invalidate()
    }

    private fun hasFreshText(index: Int): Boolean =
        focusedText.containsKey(index) && System.nanoTime() - (focusedTextAt[index] ?: 0L) < ListGlide.TEXT_PICTURE_NS

    private fun keepEdge(rows: List<Row>, from: Int, to: Int, toRow: Row?) {
        edgeLook?.let { ListGlide.Pool.give(it) }
        edgeLook = null
        edgeIndex = -1
        if (host !is ListView || rows.isEmpty() || toRow == null) return
        val fade = host.verticalFadingEdgeLength // 0 with List Edges = Cut
        val edge = when {
            to > from && toRow.bottom > host.height - host.paddingBottom - fade -> rows.first()
            to < from && toRow.top < host.paddingTop + fade -> rows.last()
            else -> return
        }
        if (edge.index == from || edge.index == to) return
        edgeLook = ListGlide.Pool.picture(edge.view, withBackground = true) ?: return
        edgeIndex = edge.index
    }

    private fun keepText(index: Int, view: View) {
        focusedText.remove(index)?.let { ListGlide.Pool.give(it) }
        withArrow.remove(index)
        val arrow = ListGlide.arrowOf(view)
        val text = ListGlide.Pool.picture(view, withBackground = false)
        // The arrow moves with the highlight: its own picture, cut out of the picture of the text. The row does not
        // change: this can run in a draw, and on Android 4.4 an arrow made INVISIBLE for the picture lost its
        // display list, and the row then drew no arrow until the next step.
        if (text != null && arrow != null) keepArrow(view, arrow, text)
        if (text != null) {
            focusedText[index] = text
            focusedTextAt[index] = System.nanoTime()
            if (arrow != null) withArrow.add(index)
        }
        while (focusedText.size > 4) {
            val oldest = focusedText.keys.first()
            focusedText.remove(oldest)?.let { ListGlide.Pool.give(it) }
            focusedTextAt.remove(oldest)
            withArrow.remove(oldest)
        }
    }

    private fun keepArrow(row: View, arrow: View, text: Bitmap) {
        var x = 0
        var y = 0
        var v = arrow
        while (v !== row) {
            x += v.left
            y += v.top
            v = v.parent as? View ?: break
        }
        arrowLeft = x
        arrowTop = y
        arrowRowHeight = row.height
        clip.set(x, y, x + arrow.width, y + arrow.height)
        arrowLook?.let { ListGlide.Pool.give(it) }
        arrowLook = ListGlide.Pool.cut(text, clip)
    }

    /** Ends the glide: everything shows at its place at the next frame. */
    fun cancel() {
        if (!active) return
        active = false
        pending = false
        target = -1
        offset = 0f
        for (b in focusedText.values) ListGlide.Pool.give(b)
        focusedText.clear()
        focusedTextAt.clear()
        withArrow.clear()
        arrowLook?.let { ListGlide.Pool.give(it) }
        arrowLook = null
        targetLook?.let { ListGlide.Pool.give(it) }
        targetLook = null
        edgeLook?.let { ListGlide.Pool.give(it) }
        edgeLook = null
        edgeIndex = -1
        bar = null
        for ((position, v) in extraRows) spare(position, v)
        extraRows.clear()
        host.invalidate()
    }

    /** Draws the rows ([drawRows]: the list's own dispatchDraw) with the glide. */
    fun draw(canvas: Canvas, drawRows: (Canvas) -> Unit) {
        if (!active) return drawRows(canvas)
        val rows = rows()
        if (pending) {
            pending = false
            val shift = ListGlide.shift(beforeIndex, beforeTop, IntArray(rows.size) { rows[it].index }, IntArray(rows.size) { rows[it].top })
            if (shift == null) {
                cancel()
                return drawRows(canvas)
            }
            // At this frame the rows show where they were before the step
            offset -= shift
            // The step took main-thread time (focus, layout): the motion starts from here, as one frame after the step
            lastNs = System.nanoTime() - ListGlide.FRAME_NS
        }
        val t = rows.firstOrNull { it.index == target }
        // Another row has the focus (code selected it): no glide. A ListView row can get the focus one frame later.
        val focused = rows.firstOrNull { it.view.hasFocus() }
        if (t == null || (focused != null && focused.index != target)) {
            cancel()
            return drawRows(canvas)
        }
        if (bar == null && focused != null) {
            // The first frame with the focus on the new row: its text and its background in the focused look
            keepText(target, t.view)
            bar = t.view.background?.constantState?.newDrawable(host.resources)?.mutate()
        }
        val rowHeight = t.view.height.toFloat()
        val now = System.nanoTime()
        // A late frame (a wheel step can block the main thread for some frames) moves only a little more than one
        // frame: the motion continues from where it stopped, it does not jump.
        val dt = min((now - lastNs) / 1e9f, ListGlide.MAX_FRAME_DT)
        lastNs = now
        offset = ListGlide.approach(ListGlide.limit(offset, 0f, ListGlide.MAX_SCROLL_LAG_ROWS * rowHeight), 0f, ListGlide.SCROLL_RATE, dt)
        barTop = ListGlide.approach(ListGlide.limit(barTop, t.top.toFloat(), ListGlide.MAX_BAR_LAG_ROWS * rowHeight), t.top.toFloat(), ListGlide.BAR_RATE, dt)
        barHeight = ListGlide.approach(barHeight, rowHeight, ListGlide.BAR_RATE, dt)
        if (offset == 0f && barTop == t.top.toFloat() && barHeight == rowHeight) {
            cancel()
            return drawRows(canvas)
        }

        // Canvas coordinates = screen + the scroll of the list
        val sx = host.scrollX
        val sy = host.scrollY
        val dy = offset.toInt()
        val left = t.left + sx
        val right = left + t.view.width
        val tTop = t.top + dy + sy
        val tBottom = t.bottom + dy + sy
        val barT = barTop.toInt() + sy
        val barB = (barTop + barHeight).toInt() + sy
        val barDrawable = bar
        val look = targetLook

        canvas.save()
        canvas.clipRect(sx, sy + host.paddingTop, sx + host.width, sy + host.height - host.paddingBottom)
        canvas.save()
        if (barDrawable != null && look != null) {
            // The target row and the highlight show from the pictures
            clip.set(left, tTop, right, tBottom)
            canvas.clipRect(clip, Region.Op.DIFFERENCE)
            clip.set(left, barT, right, barB)
            canvas.clipRect(clip, Region.Op.DIFFERENCE)
        }
        canvas.translate(0f, dy.toFloat())
        drawRows(canvas)
        if (host is ListView) drawExtraRows(canvas, host, rows)
        canvas.restore()
        if (barDrawable != null && look != null) {
            // The plain look of the target row above and below the highlight
            if (barT > tTop) drawPart(canvas, look, left, tTop, left, tTop, right, min(barT, tBottom))
            if (barB < tBottom) drawPart(canvas, look, left, tTop, left, maxOf(barB, tTop), right, tBottom)
            barDrawable.setBounds(left, barT, right, barB)
            barDrawable.draw(canvas)
            // The focused text of each row under the highlight, and how much of the highlight is on rows with an arrow
            var arrowPx = 0
            var knownPx = 0
            for (r in rows) {
                val top = r.top + dy + sy
                val bottom = top + r.view.height
                if (barT >= bottom || barB <= top) continue
                val text = focusedText[r.index] ?: continue
                val rl = r.left + sx
                val partTop = maxOf(barT, top)
                val partBottom = min(barB, bottom)
                drawPart(canvas, text, rl, top, rl, partTop, rl + r.view.width, partBottom)
                knownPx += partBottom - partTop
                if (r.index in withArrow) arrowPx += partBottom - partTop
            }
            // One whole arrow at the right of the highlight, at its place in a row
            val arrow = arrowLook
            if (arrow != null && arrowPx > 0) {
                arrowPaint.alpha = ListGlide.arrowAlpha(arrowPx, knownPx)
                val y = barT + ListGlide.arrowTop(arrowTop, arrow.height, arrowRowHeight, barB - barT)
                canvas.drawBitmap(arrow, (left + arrowLeft).toFloat(), y.toFloat(), arrowPaint)
            }
        }
        canvas.restore()
        host.postInvalidateOnAnimation()
    }

    /** Draws the part ([l], [t], [r], [b]) of [picture], a picture of a row with its top left corner at ([x], [y]). */
    private fun drawPart(canvas: Canvas, picture: Bitmap, x: Int, y: Int, l: Int, t: Int, r: Int, b: Int) {
        if (b <= t) return
        canvas.save()
        clip.set(l, t, r, b)
        canvas.clipRect(clip)
        canvas.drawBitmap(picture, x.toFloat(), y.toFloat(), null)
        canvas.restore()
    }

    /**
     * The rows past the first or the last row of a ListView that show while the rows glide (the ListView has views
     * only for the rows at their place). Drawn in the moved canvas, at their place.
     */
    private fun drawExtraRows(canvas: Canvas, list: ListView, rows: List<Row>) {
        val adapter = list.adapter ?: return
        if (rows.isEmpty()) return
        val gap = list.dividerHeight
        val width = list.width - list.paddingLeft - list.paddingRight
        val dy = offset.toInt()
        var above = rows.first().index - 1
        var top = rows.first().top
        while (above >= 0 && top + dy > list.paddingTop && rows.first().index - above <= 3) {
            val edge = edgeLook?.takeIf { above == edgeIndex }
            val v = if (edge == null) extraRow(list, adapter, above, width) ?: break else null
            top -= gap + (edge?.height ?: v!!.height)
            if (edge != null) canvas.drawBitmap(edge, list.paddingLeft.toFloat(), top.toFloat(), null)
            else drawRow(canvas, v!!, list.paddingLeft, top)
            above--
        }
        var below = rows.last().index + 1
        var bottom = rows.last().bottom
        while (below < adapter.count && bottom + dy < list.height - list.paddingBottom && below - rows.last().index <= 3) {
            val edge = edgeLook?.takeIf { below == edgeIndex }
            val v = if (edge == null) extraRow(list, adapter, below, width) ?: break else null
            if (edge != null) canvas.drawBitmap(edge, list.paddingLeft.toFloat(), (bottom + gap).toFloat(), null)
            else drawRow(canvas, v!!, list.paddingLeft, bottom + gap)
            bottom += gap + (edge?.height ?: v!!.height)
            below++
        }
    }

    private fun drawRow(canvas: Canvas, v: View, left: Int, top: Int) {
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        v.draw(canvas)
        canvas.restore()
    }

    private fun extraRow(list: ListView, adapter: android.widget.ListAdapter, position: Int, width: Int): View? {
        extraRows[position]?.let { if (it.width == width) return it }
        if (spareAdapter !== adapter) {
            spareRows.clear()
            spareAdapter = adapter
        }
        val type = adapter.getItemViewType(position)
        val convert = extraRows.remove(position) ?: spareRows[type]?.let { if (it.isEmpty()) null else it.removeAt(it.size - 1) }
        val v = try {
            adapter.getView(position, convert, list)
        } catch (_: Exception) {
            return null
        } ?: return null
        val lp = v.layoutParams
        val heightSpec = if (lp != null && lp.height > 0) View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY)
        else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec)
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        if (extraRows.size > 8) {
            for ((p, row) in extraRows) spare(p, row)
            extraRows.clear()
        }
        extraRows[position] = v
        return v
    }

    private fun spare(position: Int, v: View) {
        val adapter = spareAdapter ?: return
        if (position >= adapter.count) return
        val type = adapter.getItemViewType(position)
        if (type < 0) return
        val list = spareRows.getOrPut(type) { ArrayList(2) }
        if (list.size < 4) list.add(v)
    }

    /** The rows of the list on the screen (a ScrollView list: all its rows), at their place. */
    private fun rows(): List<Row> {
        val out = ArrayList<Row>()
        when (host) {
            is ListView -> {
                val first = host.firstVisiblePosition
                for (i in 0 until host.childCount) {
                    val v = host.getChildAt(i)
                    out.add(Row(first + i, v, v.left, v.top))
                }
            }
            is ScrollView -> {
                val content = host.getChildAt(0) as? ViewGroup ?: return out
                for (i in 0 until content.childCount) {
                    val v = content.getChildAt(i)
                    if (v.visibility != View.VISIBLE || v.height == 0) continue
                    out.add(Row(i, v, content.left + v.left - host.scrollX, content.top + v.top - host.scrollY))
                }
            }
        }
        return out
    }
}
