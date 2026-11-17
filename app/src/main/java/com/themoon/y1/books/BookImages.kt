package com.themoon.y1.books

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.style.ReplacementSpan
import android.util.LruCache
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The images of an open book: their sizes (read once, from the image header) and their bitmaps at the size of the
 * page, decoded on a worker thread into an LRU. [onReady] runs on the main thread when a bitmap that was drawn
 * missing is ready (the view draws again).
 */
class BookImages(private val source: BookSource, private val onReady: () -> Unit) {
    private val sizes = HashMap<String, IntArray>()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.rowBytes * value.height
    }
    private val pending = HashSet<String>()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "BookImages").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var closed = false

    /** The pixel size of [path] (width, height), or null when it is not an image that decodes. Worker thread. */
    fun size(path: String): IntArray? {
        synchronized(sizes) { sizes[path]?.let { return it.takeIf { s -> s[0] > 0 } } }
        val bytes = source.image(path)
        val size = if (bytes == null) intArrayOf(0, 0) else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            intArrayOf(maxOf(o.outWidth, 0), maxOf(o.outHeight, 0))
        }
        synchronized(sizes) { sizes[path] = size }
        return size.takeIf { it[0] > 0 && it[1] > 0 }
    }

    /** The bitmap of [path] at [w] x [h], or null: then it decodes now on the worker, and [onReady] follows. Main thread. */
    fun bitmap(path: String, w: Int, h: Int): Bitmap? {
        val key = "$path@$w"
        cache.get(key)?.let { return it }
        if (closed || !pending.add(key)) return null
        worker.execute {
            val bmp = if (closed) null else decode(path, w, h)
            main.post {
                pending.remove(key)
                if (bmp != null && !closed) {
                    cache.put(key, bmp)
                    onReady()
                }
            }
        }
        return null
    }

    private fun decode(path: String, w: Int, h: Int): Bitmap? = try {
        val bytes = source.image(path)
        if (bytes == null) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val jpeg = bounds.outMimeType == "image/jpeg"
            val o = BitmapFactory.Options().apply {
                inSampleSize = sample
                // A JPEG has no alpha: half the memory
                inPreferredConfig = if (jpeg) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (raw == null || (raw.width == w && raw.height == h)) raw
            else Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
        }
    } catch (_: Throwable) {
        // OutOfMemoryError of a huge image: no picture, the book goes on
        null
    }

    fun close() {
        closed = true
        worker.shutdownNow()
        cache.evictAll()
    }

    companion object {
        /** About ten page-size pictures. */
        const val CACHE_BYTES = 6 * 1024 * 1024
    }
}

/**
 * An image in the text. A large image takes a line of its own, centred ([block]); a small one sits on the baseline
 * in the line. The size is known when the text is laid out; the bitmap comes from [images] when it is drawn.
 * [darkPage] draws a white card under an image with transparency, so black line art shows on a dark page.
 */
class BookImageSpan(
    private val images: BookImages,
    private val path: String,
    private val w: Int,
    private val h: Int,
    private val lineWidth: Int,
    private val block: Boolean
) : ReplacementSpan() {
    private val dst = Rect()
    private val frame = RectF()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            fm.ascent = -h
            fm.top = -h
            fm.descent = 0
            fm.bottom = 0
        }
        return if (block) lineWidth else w
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val left = if (block) (x + (lineWidth - w) / 2f).toInt() else x.toInt()
        dst.set(left, y - h, left + w, y)
        val bmp = images.bitmap(path, w, h)
        if (bmp == null) {
            // A frame while the picture decodes
            val old = paint.style
            val oldAlpha = paint.alpha
            paint.style = Paint.Style.STROKE
            paint.alpha = 60
            frame.set(dst)
            canvas.drawRect(frame, paint)
            paint.style = old
            paint.alpha = oldAlpha
            return
        }
        if (darkPage && bmp.hasAlpha()) {
            val old = paint.color
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawRect(dst, paint)
            paint.color = old
        }
        canvas.drawBitmap(bmp, null, dst, null)
    }

    companion object {
        /** Set by the reader before it draws: the page is dark. */
        @Volatile
        var darkPage = false
    }
}
