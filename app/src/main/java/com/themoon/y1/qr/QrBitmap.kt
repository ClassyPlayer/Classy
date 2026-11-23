package com.themoon.y1.qr

import android.graphics.Bitmap
import android.graphics.Color

/** Draws a [QrCode] for the screen, with the quiet zone that a phone camera needs. */
object QrBitmap {

    /** Light modules around the code, as the standard tells. */
    const val QUIET_ZONE = 4

    /** The width and height in pixels of the bitmap of [code] at [moduleSize] pixels for each module. */
    fun sizeFor(code: QrCode, moduleSize: Int): Int = (code.size + 2 * QUIET_ZONE) * moduleSize

    /**
     * A square bitmap of [code], [moduleSize] pixels for each module, with the quiet zone. Of the two colours, the
     * darker one is for the dark modules: a camera reads dark modules on a light background best.
     */
    fun create(code: QrCode, moduleSize: Int, colorA: Int, colorB: Int): Bitmap {
        val aIsDarker = luminance(colorA) <= luminance(colorB)
        val dark = if (aIsDarker) colorA else colorB
        val light = if (aIsDarker) colorB else colorA
        val side = sizeFor(code, moduleSize)
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            val moduleY = y / moduleSize - QUIET_ZONE
            for (x in 0 until side) {
                pixels[y * side + x] = if (code.isDark(x / moduleSize - QUIET_ZONE, moduleY)) dark else light
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(color: Int): Int =
        Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114
}
