package com.themoon.y1.lists

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.ClassyLists
import com.themoon.y1.views.FocusLook

/**
 * The [RowLook.ART] row: a 52 dp cover at the left, the title and a second line, and the chevron on the focused row.
 * The album rows, the "All Songs" and "Shuffle" rows of the Music lists, and the video rows.
 *
 * An adapter keeps the row for the next item ([of] with the convertView) and calls [bind]; the focus look is in the
 * row (one focus listener for all of them).
 */
class ArtRow private constructor(context: Context, inListView: Boolean) {
    private val density = context.resources.displayMetrics.density

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isFocusable = true
        isClickable = true
        isSoundEffectsEnabled = false
        layoutParams = if (inListView) {
            AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
    }

    val title = TextView(context).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isHorizontalFadingEdgeEnabled = true
    }

    private val subtitle = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * density)
        setTypeface(ThemeManager.getCustomFont(), Typeface.NORMAL)
        isSingleLine = true
        // Cut with "…" in the list; a long line scrolls on the focused row, as the title does
        ellipsize = TextUtils.TruncateAt.END
        marqueeRepeatLimit = -1
        isHorizontalFadingEdgeEnabled = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(1) }
    }

    /** Text at the right edge (a track length). With it the row shows no chevron: the row does not open a list. */
    private val trailing = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * density)
        setTypeface(ThemeManager.getCustomFont(), Typeface.NORMAL)
        isSingleLine = true
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) }
    }

    private val arrow = ClassyLists.arrow(context, leftMarginDp = 6)

    init {
        view.addView(image)
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) }
        }
        text.addView(title)
        text.addView(subtitle)
        view.addView(text)
        view.addView(trailing)
        view.addView(arrow)
        ClassyLists.styleRow(view, title, heightDp = HEIGHT_DP)
        view.tag = this
    }

    /** Sets the texts; an empty [line2] shows no second line. The image stays: set it with [image]. */
    fun bind(line1: String, line2: String, right: String? = null) {
        title.text = line1
        subtitle.text = line2
        subtitle.visibility = if (line2.isEmpty()) View.GONE else View.VISIBLE
        trailing.text = right ?: ""
        trailing.visibility = if (right == null) View.GONE else View.VISIBLE
        // A row with a right-edge text does not open a list: no arrow
        look.arrow = if (right == null) arrow else null
        if (right != null) arrow.visibility = View.GONE
        // The new text has a new layout: set the ellipsis for the focus now (no extra layout later)
        subtitle.ellipsize = if (view.isFocused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        look.refresh()
    }

    /** Sets the right-edge text of a row bound with one (it may come later than the row). */
    fun setTrailing(right: String) {
        trailing.text = right
    }

    /**
     * The focus look ([FocusLook]). A wheel step changes it on two rows, so it requests no layout: the second line
     * changes its ellipsis only when it does not fit.
     */
    private val look = FocusLook.of(view).text(title)
        .text(subtitle) { ThemeManager.getTextColorSecondary() }
        .text(trailing) { ThemeManager.getTextColorSecondary() }
        .onFocus { focused ->
            val ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            if (subtitle.ellipsize != ellipsize && !subtitleFits()) subtitle.ellipsize = ellipsize
            subtitle.isSelected = focused
        }
        .also {
            it.marquee = title
            it.arrow = arrow
            it.refresh()
        }

    /** The second line shows in full (no "…", no scroll): its ellipsis mode changes nothing. */
    private fun subtitleFits(): Boolean {
        val layout = subtitle.layout ?: return false
        val room = subtitle.width - subtitle.compoundPaddingLeft - subtitle.compoundPaddingRight
        return room > 0 && layout.lineCount == 1 && layout.getEllipsisCount(0) == 0 && layout.getLineWidth(0) <= room
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        /** Row height. */
        const val HEIGHT_DP = 64

        /** The row of [convertView] if it is an ART row, else a new row (for a ListView when [inListView]). */
        fun of(convertView: View?, context: Context, inListView: Boolean = true): ArtRow =
            convertView?.tag as? ArtRow ?: ArtRow(context, inListView)
    }
}
