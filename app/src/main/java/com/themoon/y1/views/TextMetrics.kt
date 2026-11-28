package com.themoon.y1.views

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.BoringLayout
import android.text.TextPaint

/**
 * The metrics of a font at a text size that the layout code needs, measured once per font and size and then kept
 * (the app uses a few sizes only).
 *
 * The ascent and descent are those that the text layout uses for a single line without font padding, not those of
 * [Paint.getFontMetricsInt]: on Android 4.2 (Y1) they are not the same. Nimbus Sans Bold at 23 px: the paint gives
 * an ascent of -17, the layout puts the baseline 21 px below the top of the text, so a text placed with the paint
 * value was 4 px low. On Android 4.4 (Y2) the two are the same.
 */
object TextMetrics {

    /**
     * @property capHeight height of "H" above the baseline, in px: from its outline (getTextBounds rounds to whole px).
     * @property ascent top of a one-line text view (no font padding) above its baseline, in px (negative).
     * @property descent bottom of that text view below its baseline, in px.
     */
    class Values(val capHeight: Float, val ascent: Int, val descent: Int) {
        /** Height of a one-line text view without font padding. */
        val lineHeight: Int get() = descent - ascent
    }

    private data class Key(val typeface: Typeface?, val size: Float, val fakeBold: Boolean, val skewX: Float)

    private val cache = HashMap<Key, Values>()

    /** The metrics of the font, size and style of [paint]. */
    @JvmStatic
    @Synchronized
    fun of(paint: Paint): Values {
        val key = Key(paint.typeface, paint.textSize, paint.isFakeBoldText, paint.textSkewX)
        return cache.getOrPut(key) { measure(paint) }
    }

    private fun measure(paint: Paint): Values {
        val textPaint = paint as? TextPaint ?: TextPaint(paint)
        val outline = Path()
        textPaint.getTextPath("H", 0, 1, 0f, 0f, outline)
        val bounds = RectF()
        outline.computeBounds(bounds, true)
        // The metrics that BoringLayout (one line, as in a single-line TextView) gives the line of "H"
        val line = BoringLayout.isBoring("H", textPaint)
        val fm = textPaint.fontMetricsInt
        return Values(
            capHeight = -bounds.top,
            ascent = line?.ascent ?: fm.ascent,
            descent = line?.descent ?: fm.descent
        )
    }
}
