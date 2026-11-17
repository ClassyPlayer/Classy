package com.themoon.y1.controllers.books

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.themoon.y1.views.TablerIcons

/** Covers for books without one: a coloured card with the book icon, the title and the author. */
object BookCovers {

    /** Muted colours; a title always gets the same one. */
    private val COLORS = intArrayOf(
        0xFF35506B.toInt(), 0xFF5B3A55.toInt(), 0xFF3F5B47.toInt(), 0xFF6B4A35.toInt(),
        0xFF404A6B.toInt(), 0xFF5E5A3A.toInt(), 0xFF3A5E5E.toInt(), 0xFF6B3A3F.toInt()
    )

    fun colorOf(title: String): Int = COLORS[(title.hashCode() and 0x7FFFFFFF) % COLORS.size]

    /** A [w] x [h] cover for the art pane. The text keeps to the middle: the pane crops and zooms the art. */
    @Suppress("DEPRECATION")
    fun placeholder(title: String, author: String, w: Int, h: Int, typeface: Typeface?): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(colorOf(title))
        val icon = TablerIcons.render(TablerIcons.BOOK, w / 5, 0xCCFFFFFF.toInt())
        c.drawBitmap(icon, (w - icon.width) / 2f, h * 0.22f, null)
        val textW = (w * 0.62f).toInt()
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = w / 11f
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
        }
        val shortTitle = if (title.length > 80) title.substring(0, 80) + "…" else title
        val titleLayout = StaticLayout(shortTitle, titlePaint, textW, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        var y = h * 0.22f + icon.height + h * 0.05f
        c.save()
        c.translate((w - textW) / 2f, y)
        titleLayout.draw(c)
        c.restore()
        y += titleLayout.height + h * 0.03f
        if (author.isNotEmpty()) {
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCCFFFFFF.toInt()
                textSize = w / 15f
                this.typeface = typeface
            }
            val authorLayout = StaticLayout(author, authorPaint, textW, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
            c.save()
            c.translate((w - textW) / 2f, y)
            authorLayout.draw(c)
            c.restore()
        }
        return bmp
    }
}
