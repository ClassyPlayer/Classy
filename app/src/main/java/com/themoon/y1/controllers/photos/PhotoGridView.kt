package com.themoon.y1.controllers.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.themoon.y1.photos.PhotoGeometry
import com.themoon.y1.photos.PhotoItem
import com.themoon.y1.photos.PhotoThumbs

/**
 * The thumbnail grid of a set of photos: 5 x 4 thumbnails under the status bar ([PhotoGeometry]),
 * a frame in the theme colour on the selected photo, and the page moves one row at a time.
 * One view draws the whole page: no row views, so a wheel step only draws again.
 */
class PhotoGridView(context: Context) : View(context) {

    private var photos: List<PhotoItem> = emptyList()
    private var geometry: PhotoGeometry? = null
    private var firstRow = 0

    /** Space at the top for the status bar. */
    var topInset = 0
        set(value) {
            field = value
            geometry = null
            invalidate()
        }

    var selected = 0
        private set

    var frameColor = 0xFF2588DC.toInt()
        set(value) {
            field = value
            framePaint.color = value
            invalidate()
        }

    var emptyColor = 0x22888888
        set(value) {
            field = value
            emptyPaint.color = value
            invalidate()
        }

    private val framePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = frameColor
    }
    private val emptyPaint = Paint().apply { color = emptyColor }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val broken = HashSet<String>()

    val count: Int get() = photos.size

    val selectedPhoto: PhotoItem? get() = photos.getOrNull(selected)

    fun setPhotos(list: List<PhotoItem>, select: Int) {
        photos = list
        broken.clear()
        selected = if (list.isEmpty()) 0 else select.coerceIn(0, list.size - 1)
        firstRow = 0
        geometry()?.let { firstRow = it.firstRowFor(selected, 0, count) }
        invalidate()
    }

    /** Selects photo [index] (the page follows). */
    fun select(index: Int) {
        if (photos.isEmpty()) return
        selected = index.coerceIn(0, photos.size - 1)
        geometry()?.let { firstRow = it.firstRowFor(selected, firstRow, count) }
        invalidate()
    }

    /** Photos on one page (for Next and Previous). */
    val pageSize: Int get() = geometry()?.pageSize ?: 20

    private fun geometry(): PhotoGeometry? {
        if (width <= 0 || height <= 0) return null
        geometry?.let { if (it.width == width && it.height == height) return it }
        return PhotoGeometry(width, height, topInset).also {
            geometry = it
            framePaint.strokeWidth = it.frameWidth.toFloat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        geometry = null
        geometry()?.let { firstRow = it.firstRowFor(selected, 0, count) }
    }

    override fun onDraw(canvas: Canvas) {
        val g = geometry() ?: return
        val first = firstRow * g.columns
        val last = minOf(count, first + g.pageSize)
        for (i in first until last) {
            val r = g.thumbRect(i, firstRow)
            dst.set(r[0], r[1], r[2], r[3])
            val p = photos[i]
            val bmp = PhotoThumbs.cached(p)
            if (bmp != null && !bmp.isRecycled) {
                drawCropped(canvas, bmp)
            } else {
                canvas.drawRect(dst, emptyPaint)
                if (p.path !in broken) PhotoThumbs.load(p) { b ->
                    // Only for the photo still in the list (setPhotos may have put a new list meanwhile)
                    if (photos.getOrNull(i) !== p) return@load
                    if (b == null) broken += p.path
                    invalidate()
                }
            }
            if (i == selected) {
                val half = g.frameWidth / 2f
                canvas.drawRect(dst.left - half, dst.top - half, dst.right + half, dst.bottom + half, framePaint)
            }
        }
    }

    /** The 4:3 thumbnail into the cell, the middle of it when the cell has another shape. */
    private fun drawCropped(canvas: Canvas, bmp: Bitmap) {
        val c = PhotoGeometry.crop(bmp.width, bmp.height, dst.width(), dst.height())
        src.set(c[0], c[1], c[2], c[3])
        canvas.drawBitmap(bmp, src, dst, bitmapPaint)
    }
}
