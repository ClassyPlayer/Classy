package com.themoon.y1.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock

/**
 * Decodes photos at the size they show, never at full size: a 12 MP JPEG is 48 MB as a bitmap, more than the
 * whole heap of the app on Android 4.4. The bounds come first, then a power-of-two sub-sample
 * ([PhotoGeometry.sampleSize]), then one exact scale with the EXIF turn ([ExifOrientation]).
 *
 * Every read of a photo file goes through here, so a USB share can wait until no photo file is open
 * ([closeForUsbShare]): an open file on the card makes MountService kill the app during the share.
 */
object PhotoDecoder {

    private val lock = Object()
    /** Reads that run now (all decode threads: viewer, thumbnails, art panes). */
    private var reading = 0
    private var closed = false

    /**
     * The card is about to be shared: no new read starts (they return null), and this waits up to [timeoutMs] for the
     * reads that run. True when no photo file is open. Not on the main thread. [reopen] when the share ends.
     */
    fun closeForUsbShare(timeoutMs: Long): Boolean = synchronized(lock) {
        closed = true
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (reading > 0) {
            val left = end - SystemClock.uptimeMillis()
            if (left <= 0) return false
            try {
                lock.wait(left)
            } catch (_: InterruptedException) {
                return false
            }
        }
        true
    }

    /** The share ended (or did not start): photos can be read again. */
    fun reopen() = synchronized(lock) { closed = false }

    private inline fun <T> read(block: () -> T?): T? {
        synchronized(lock) {
            if (closed) return null
            reading++
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                reading--
                if (reading == 0) lock.notifyAll()
            }
        }
    }

    enum class Mode {
        /** The whole photo in the box (full screen; the view adds the black bars). */
        FIT,
        /** The photo covers the box and keeps its shape (the art pane: it crops and moves the photo itself). */
        COVER,
        /** The middle of the photo, exactly the box (thumbnails). */
        CROP
    }

    /** The size of the stored image (before the EXIF turn), or null when it is not a picture that Android reads. */
    fun bounds(path: String): IntArray? = read { boundsOf(path) }

    private fun boundsOf(path: String): IntArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(path, o)
        } catch (_: Throwable) {
            return null
        }
        return if (o.outWidth > 0 && o.outHeight > 0) intArrayOf(o.outWidth, o.outHeight) else null
    }

    /** [path] for a [boxW] x [boxH] box, turned upright ([orientation]); null when it cannot be read. */
    fun decode(path: String, orientation: Int, boxW: Int, boxH: Int, mode: Mode): Bitmap? = read { decodeNow(path, orientation, boxW, boxH, mode) }

    private fun decodeNow(path: String, orientation: Int, boxW: Int, boxH: Int, mode: Mode): Bitmap? {
        val b = boundsOf(path) ?: return null
        val turned = ExifOrientation.swapsSides(orientation)
        // The box in the stored image's own axes
        val tw = if (turned) boxH else boxW
        val th = if (turned) boxW else boxH
        var sample = PhotoGeometry.sampleSize(b[0], b[1], tw, th, mode != Mode.FIT)
        repeat(3) {
            try {
                val raw = decodeSampled(path, sample) ?: return null
                return finish(raw, orientation, tw, th, mode)
            } catch (_: OutOfMemoryError) {
                sample *= 2
            }
        }
        return null
    }

    private fun decodeSampled(path: String, sample: Int): Bitmap? {
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            // JPEG and BMP have no alpha: half the memory. PNG, GIF and WebP can be transparent.
            inPreferredConfig = if (PhotoIndex.hasExif(path) || path.endsWith(".bmp", ignoreCase = true)) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            inDither = true
        }
        return try {
            BitmapFactory.decodeFile(path, o)
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** One scale (down only; FIT never grows the photo, the view does) and the EXIF turn. */
    private fun finish(raw: Bitmap, orientation: Int, tw: Int, th: Int, mode: Mode): Bitmap {
        var x = 0
        var y = 0
        var w = raw.width
        var h = raw.height
        val scale: Float
        when (mode) {
            Mode.FIT -> scale = minOf(1f, minOf(tw / w.toFloat(), th / h.toFloat()))
            Mode.COVER -> scale = minOf(1f, maxOf(tw / w.toFloat(), th / h.toFloat()))
            Mode.CROP -> {
                val r = PhotoGeometry.crop(w, h, tw, th)
                x = r[0]; y = r[1]; w = r[2] - r[0]; h = r[3] - r[1]
                scale = minOf(tw / w.toFloat(), th / h.toFloat())
            }
        }
        val m = Matrix()
        if (scale != 1f) m.postScale(scale, scale)
        if (ExifOrientation.mirror(orientation)) m.postScale(-1f, 1f)
        val deg = ExifOrientation.rotation(orientation)
        if (deg != 0) m.postRotate(deg.toFloat())
        if (m.isIdentity && x == 0 && y == 0 && w == raw.width && h == raw.height) return raw
        val out = Bitmap.createBitmap(raw, x, y, w, h, m, true)
        if (out !== raw) raw.recycle()
        return out
    }
}
