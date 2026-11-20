package com.themoon.y1.books

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/** How the text is laid out: the page size and the reader settings that change line breaks. */
data class PageStyle(
    val width: Int,
    val height: Int,
    val textSize: Float,
    val lineSpacing: Float,
    val typeface: Typeface
)

/** One laid-out part of a section: its first char in the section, its layout and its pages. */
class LaidOut(
    val section: Int,
    val part: Int,
    val partCount: Int,
    /** The char offset of the part in its section. */
    val start: Int,
    val sectionLength: Int,
    val layout: StaticLayout,
    val paint: TextPaint,
    val pages: IntArray
) : LineSource {
    override val lineCount: Int get() = layout.lineCount
    override fun top(line: Int): Int = layout.getLineTop(line)
    override fun bottom(line: Int): Int = layout.getLineBottom(line)

    val isEmpty: Boolean get() = layout.text.isEmpty()
    val isLastPart: Boolean get() = part == partCount - 1

    /** The section offset of the start of [line]. */
    fun offsetOf(line: Int): Int = start + layout.getLineStart(line.coerceIn(0, maxOf(lineCount - 1, 0)))

    /** The line that has the section offset [offset]. */
    fun lineOf(offset: Int): Int = layout.getLineForOffset((offset - start).coerceIn(0, layout.text.length))

    /** The first line of the page that ends this part ([PageMath.pageStarts]); for Back from the next part. */
    fun lastPage(): Int = pages.size - 1
}

/** [StyledText] as spans for [StaticLayout]. */
object SpannedFactory {

    fun build(text: StyledText, style: PageStyle, images: BookImages?): Spanned {
        val sb = SpannableStringBuilder(text.text)
        val size = style.textSize
        val indent = (size * 1.5f).toInt()
        // An image line also gets the extra space of the line spacing and of a paragraph end: keep it inside the page
        val maxImageH = ((style.height - size) / style.lineSpacing).toInt()
        for (r in text.runs) {
            val s = r.start
            val e = r.end
            if (s < 0 || e > sb.length || s >= e) continue
            when (r.kind) {
                StyledText.Run.BOLD -> sb.setSpan(StyleSpan(Typeface.BOLD), s, e, FLAGS)
                StyledText.Run.ITALIC -> sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, FLAGS)
                StyledText.Run.HEADING -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, e, FLAGS)
                    sb.setSpan(RelativeSizeSpan(HEADING_SIZES[(r.value - 1).coerceIn(0, HEADING_SIZES.size - 1)]), s, e, FLAGS)
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, e, FLAGS)
                    sb.setSpan(SpaceAbove((size * 0.8f).toInt()), s, e, FLAGS)
                }
                StyledText.Run.CENTER -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, e, FLAGS)
                StyledText.Run.RIGHT -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), s, e, FLAGS)
                StyledText.Run.QUOTE -> sb.setSpan(LeadingMarginSpan.Standard(indent), s, e, FLAGS)
                StyledText.Run.LIST_ITEM -> sb.setSpan(BulletSpan(indent / 2), s, e, FLAGS)
                StyledText.Run.SUP -> {
                    sb.setSpan(SuperscriptSpan(), s, e, FLAGS)
                    sb.setSpan(RelativeSizeSpan(0.7f), s, e, FLAGS)
                }
                StyledText.Run.SUB -> {
                    sb.setSpan(SubscriptSpan(), s, e, FLAGS)
                    sb.setSpan(RelativeSizeSpan(0.7f), s, e, FLAGS)
                }
                StyledText.Run.MONO -> sb.setSpan(TypefaceSpan("monospace"), s, e, FLAGS)
                StyledText.Run.SMALL -> sb.setSpan(RelativeSizeSpan(0.85f), s, e, FLAGS)
                StyledText.Run.UNDERLINE -> sb.setSpan(UnderlineSpan(), s, e, FLAGS)
                StyledText.Run.STRIKE -> sb.setSpan(StrikethroughSpan(), s, e, FLAGS)
                // No indent before a picture: a large one has the full line
                StyledText.Run.PARAGRAPH -> if (sb[s] != StyledText.IMAGE_CHAR) sb.setSpan(LeadingMarginSpan.Standard(indent, 0), s, e, FLAGS)
                StyledText.Run.IMAGE -> {
                    val px = r.href?.let { images?.size(it) }
                    if (px == null || images == null) {
                        // An image that does not decode: nothing (not the box glyph of U+FFFC)
                        sb.setSpan(NoImage(), s, e, FLAGS)
                        continue
                    }
                    val img = images
                    val path = r.href
                    var w = px[0]
                    var h = px[1]
                    // Fit the page: the width of a line, the height of a page
                    if (w > style.width) {
                        h = (h.toLong() * style.width / w).toInt()
                        w = style.width
                    }
                    if (h > maxImageH && maxImageH > 0) {
                        w = (w.toLong() * maxImageH / h).toInt()
                        h = maxImageH
                    }
                    w = maxOf(w, 1)
                    h = maxOf(h, 1)
                    val block = w > style.width / 2 || h > size * 2
                    sb.setSpan(BookImageSpan(img, path, w, h, style.width, block), s, e, FLAGS)
                }
            }
        }
        // A little space after each paragraph
        sb.setSpan(SpaceBelow((size * 0.35f).toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return sb
    }

    /** Lays out [text] (one part of a section) for [style]. Any thread: the layout and its paint are its own. */
    @Suppress("DEPRECATION")
    fun layout(text: CharSequence, style: PageStyle): Pair<StaticLayout, TextPaint> {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.textSize = style.textSize
        paint.typeface = style.typeface
        val layout = StaticLayout(text, paint, style.width, Layout.Alignment.ALIGN_NORMAL, style.lineSpacing, 0f, false)
        return layout to paint
    }

    private const val FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    private val HEADING_SIZES = floatArrayOf(1.45f, 1.3f, 1.18f, 1.1f, 1.05f, 1.0f)

    /** The place of an image that does not decode: no width, nothing drawn. */
    private class NoImage : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = 0
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    /** Space under the last line of each paragraph. */
    private class SpaceBelow(private val gap: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
            if (end >= text.length || text[end - 1] == '\n') {
                fm.descent += gap
                fm.bottom += gap
            }
        }
    }

    /** Space over the first line of a heading (not at the top of a section). */
    private class SpaceAbove(private val gap: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
            if (start > 0 && text[start - 1] == '\n') {
                fm.ascent -= gap
                fm.top -= gap
            }
        }
    }
}
