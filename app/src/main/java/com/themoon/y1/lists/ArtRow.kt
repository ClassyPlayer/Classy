package com.themoon.y1.lists

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.ListItemViewFactory

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
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        isSoundEffectsEnabled = false
        setPadding(dp(8), dp(6), dp(10), dp(6))
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
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * density)
        typeface = ThemeManager.getCustomFontBold()
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

    private val arrow = TextView(context).apply {
        text = "〉"
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * density)
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) }
        setTextColor(0xFFFFFFFF.toInt())
    }

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
        ClassicMenuStyle.apply(view, title, arrow, HEIGHT_DP)
        view.tag = this
        view.setOnFocusChangeListener { _, hasFocus -> showFocus(hasFocus) }
        showFocus(false)
    }

    /** Sets the texts; an empty [line2] shows no second line. The image stays: set it with [image]. */
    fun bind(line1: String, line2: String, right: String? = null) {
        title.text = line1
        subtitle.text = line2
        subtitle.visibility = if (line2.isEmpty()) View.GONE else View.VISIBLE
        trailing.text = right ?: ""
        trailing.visibility = if (right == null) View.GONE else View.VISIBLE
        showFocus(view.isFocused)
    }

    /** Sets the right-edge text of a row bound with one (it may come later than the row). */
    fun setTrailing(right: String) {
        trailing.text = right
    }

    private fun showFocus(focused: Boolean) {
        val context = view.context
        view.background = if (focused) {
            ListItemViewFactory.createFocusedButtonBackground(context)
        } else {
            ListItemViewFactory.createButtonBackground(context, ThemeManager.getListButtonNormalBg())
        }
        val focusedText = ThemeManager.getListButtonFocusedTextColor()
        title.setTextColor(if (focused) focusedText else ThemeManager.getTextColorPrimary())
        subtitle.setTextColor(if (focused) focusedText else ThemeManager.getTextColorSecondary())
        trailing.setTextColor(if (focused) focusedText else ThemeManager.getTextColorSecondary())
        arrow.visibility = if (focused && trailing.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        title.isSelected = focused
        subtitle.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        subtitle.isSelected = focused
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        /** Row height in the Classic themes. */
        const val HEIGHT_DP = 64

        /** The row of [convertView] if it is an ART row, else a new row (for a ListView when [inListView]). */
        fun of(convertView: View?, context: Context, inListView: Boolean = true): ArtRow =
            convertView?.tag as? ArtRow ?: ArtRow(context, inListView)
    }
}
