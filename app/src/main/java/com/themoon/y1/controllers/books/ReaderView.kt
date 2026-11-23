package com.themoon.y1.controllers.books

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.themoon.y1.books.BookImageSpan
import com.themoon.y1.books.LaidOut
import com.themoon.y1.books.PageMath

/**
 * The page of the reader: whole lines of one laid-out part from [topLine], and a footer with the chapter at the left
 * and the book % at the right. With no part (loading, an error) it shows [message] in the middle.
 */
class ReaderView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    /** Margins of the text: the page width and height for the layout come from the view size minus these. */
    val marginX = (14 * density).toInt()
    val marginTop = (6 * density).toInt()
    val footerHeight = (22 * density).toInt()

    var part: LaidOut? = null
        private set
    /** The first line on the page. */
    var topLine = 0
        private set

    var message: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private var chapter = ""
    private var percent = ""
    private var dark = false
    private var bg = ReaderColors.LIGHT_BG
    private var fg = ReaderColors.LIGHT_TEXT
    private var dim = ReaderColors.LIGHT_DIM

    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * density }
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18 * density
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint().apply { strokeWidth = maxOf(1f, density) }

    /** The text area: width and height of a page for the layout. */
    fun pageWidth(): Int = width - 2 * marginX
    fun pageHeight(): Int = height - marginTop - footerHeight

    fun show(part: LaidOut, top: Int) {
        this.part = part
        this.topLine = top.coerceIn(0, maxOf(part.lineCount - 1, 0))
        message = null
    }

    fun clear() {
        part = null
        topLine = 0
        invalidate()
    }

    fun setFooter(chapter: String, percent: String) {
        if (chapter == this.chapter && percent == this.percent) return
        this.chapter = chapter
        this.percent = percent
        invalidate()
    }

    fun setColors(dark: Boolean, typeface: Typeface?) {
        this.dark = dark
        bg = if (dark) ReaderColors.DARK_BG else ReaderColors.LIGHT_BG
        fg = if (dark) ReaderColors.DARK_TEXT else ReaderColors.LIGHT_TEXT
        dim = if (dark) ReaderColors.DARK_DIM else ReaderColors.LIGHT_DIM
        footerPaint.typeface = typeface
        messagePaint.typeface = typeface
        invalidate()
    }

    /** The line after the last whole line on the page. */
    fun pageEnd(): Int {
        val p = part ?: return 0
        return PageMath.pageEnd(p, topLine, pageHeight())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        val p = part
        val msg = message
        if (p == null || msg != null) {
            if (msg != null) {
                messagePaint.color = dim
                val y = height / 2f - (messagePaint.descent() + messagePaint.ascent()) / 2
                canvas.drawText(msg, width / 2f, y, messagePaint)
            }
            return
        }
        if (p.lineCount > 0) {
            val end = pageEnd()
            val lineTop = p.top(topLine)
            val lineBottom = p.bottom(end - 1)
            p.paint.color = fg
            BookImageSpan.darkPage = dark
            canvas.save()
            canvas.translate(marginX.toFloat(), (marginTop - lineTop).toFloat())
            // Only the whole lines of this page (a line at the bottom that does not fit waits for the next page).
            // One pixel short of the next line's top: Layout.draw draws each line that the clip touches, and the
            // tall letters of a font reach a little above their line
            canvas.clipRect(-marginX, lineTop, width, lineBottom - 1)
            p.layout.draw(canvas)
            canvas.restore()
        }
        drawFooter(canvas)
    }

    private fun drawFooter(canvas: Canvas) {
        val y0 = (height - footerHeight).toFloat()
        linePaint.color = dim
        linePaint.alpha = 70
        canvas.drawLine(marginX.toFloat(), y0, (width - marginX).toFloat(), y0, linePaint)
        footerPaint.color = dim
        val baseline = y0 + (footerHeight - footerPaint.descent() - footerPaint.ascent()) / 2
        val pctW = footerPaint.measureText(percent)
        canvas.drawText(percent, width - marginX - pctW, baseline, footerPaint)
        val room = width - 2 * marginX - pctW - 12 * density
        if (room > 0 && chapter.isNotEmpty()) {
            val shown = TextUtils.ellipsize(chapter, footerPaint, room, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, marginX.toFloat(), baseline, footerPaint)
        }
    }
}
