package com.themoon.y1.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Draws a QR code (zxing core) for the screen, with the quiet zone of 4 modules that a phone camera needs. */
object QrBitmap {

    /**
     * A square bitmap of the QR code of [text] (error correction M), about [maxSize] pixels wide, with whole pixels
     * for each module (at least 2 px per module). Of the two colours, the darker one is for the dark modules: a camera reads dark modules on a
     * light background best. Null when the text does not fit in a QR code.
     */
    fun create(text: String, maxSize: Int, colorA: Int, colorB: Int): Bitmap? {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        val matrix = try {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints) // One pixel for each module
        } catch (_: WriterException) {
            return null
        }
        val aIsDarker = luminance(colorA) <= luminance(colorB)
        val dark = if (aIsDarker) colorA else colorB
        val light = if (aIsDarker) colorB else colorA
        val modules = matrix.width
        val scale = maxOf(2, maxSize / modules)
        val side = modules * scale
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) pixels[y * side + x] = if (matrix.get(x / scale, y / scale)) dark else light
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(color: Int): Int =
        Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114
}
