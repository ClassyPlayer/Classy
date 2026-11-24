package com.themoon.y1.views

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager

/**
 * The focus look of a list row, for all row builders ([ListItemViewFactory], [com.themoon.y1.lists.ArtRow]): the
 * selected row has the blue gradient, its texts in the focused text colour, its long text scrolls, and its arrow
 * ([ClassyLists.arrow]) shows. The other rows have the normal background and colours, and keep the room of the arrow
 * (INVISIBLE): a wheel step changes no size, so it needs no layout (in a ListView, a layout request in its focus
 * change is lost, and the arrow did not show).
 *
 * [of] gives the look of a row (one for each row, kept in the row) and makes it the row's focus listener. The two
 * backgrounds are made once for each row: a wheel step allocates nothing (except the progress background).
 */
class FocusLook private constructor(private val row: View) : View.OnFocusChangeListener {
    private val texts = ArrayList<TextView>(3)
    private val normals = ArrayList<() -> Int>(3)
    private val extras = ArrayList<(Boolean) -> Unit>(1)

    /** The colour of the texts on the selected row. */
    var focusedColor: () -> Int = { ThemeManager.getListButtonFocusedTextColor() }

    /** The arrow of a row that opens a list: only on the selected row. */
    var arrow: View? = null

    /** The text that scrolls (marquee) on the selected row. */
    var marquee: TextView? = null

    /** Corner radius of the backgrounds, in px (dialog buttons). */
    var corners = 0f

    /** An audiobook or podcast row: the background shows the position [0] of the length [1] (ms). Null: plain. */
    var progress: IntArray? = null

    /** A row that the wheel moves ([com.themoon.y1.lists.ListSpec.reorder]): a red background in place of the highlight. */
    var picked = false
        set(value) {
            field = value
            refresh()
        }

    private var normalBg: GradientDrawable? = null
    private var focusedBg: GradientDrawable? = null
    private var pickedBg: GradientDrawable? = null
    private var normalBgColor = 0

    /** [view] gets the focused colour on the selected row, else [normal]. */
    fun text(view: TextView, normal: () -> Int = { ThemeManager.getTextColorPrimary() }): FocusLook {
        texts.add(view)
        normals.add(normal)
        return this
    }

    /** More of the look (icons, a second colour), after the texts: [block] gets true on the selected row. */
    fun onFocus(block: (Boolean) -> Unit): FocusLook {
        extras.add(block)
        return this
    }

    /** Removes all parts (a recycled row that gets another look). The arrow stays hidden, with its room. */
    fun reset(): FocusLook {
        texts.clear()
        normals.clear()
        extras.clear()
        arrow?.visibility = View.INVISIBLE
        arrow = null
        marquee = null
        progress = null
        return this
    }

    /** Shows the look of the row's focus now (after a change of the parts or of the theme). */
    fun refresh(): FocusLook {
        show(row.isFocused || picked)
        return this
    }

    // A picked row has the focused texts on its own background
    override fun onFocusChange(v: View, hasFocus: Boolean) = show(hasFocus || picked)

    private fun show(focused: Boolean) {
        val p = progress
        if (p != null) {
            ListItemViewFactory.applyProgressBackground(row.context, row, p[0], p[1], focused)
        } else {
            val bg = if (picked) pickedBg() else if (focused) focusedBg() else normalBg()
            if (row.background !== bg) row.background = bg
        }
        val focusedText = focusedColor()
        for (i in texts.indices) texts[i].setTextColor(if (focused) focusedText else normals[i]())
        marquee?.isSelected = focused
        arrow?.visibility = if (focused) View.VISIBLE else View.INVISIBLE
        for (extra in extras) extra(focused)
    }

    private fun normalBg(): GradientDrawable {
        val color = ThemeManager.getListButtonNormalBg()
        val bg = normalBg
        if (bg != null && color == normalBgColor) return bg
        normalBgColor = color
        return ListItemViewFactory.createButtonBackground(row.context, color).also {
            if (corners > 0f) it.cornerRadius = corners
            normalBg = it
        }
    }

    private fun pickedBg(): GradientDrawable = pickedBg ?: GradientDrawable().apply { setColor(PICKED_COLOR) }.also { pickedBg = it }

    private fun focusedBg(): GradientDrawable = focusedBg ?: ListItemViewFactory.createFocusedButtonBackground(row.context).also {
        if (corners > 0f) it.cornerRadius = corners
        focusedBg = it
    }

    companion object {
        /** The background of a [picked] row. */
        const val PICKED_COLOR = 0xFFD32F2F.toInt()

        /** The look of [row]; the first call makes it and makes it the focus listener of the row. */
        fun of(row: View): FocusLook = row.getTag(R.id.focus_look) as? FocusLook ?: FocusLook(row).also {
            row.setTag(R.id.focus_look, it)
            row.onFocusChangeListener = it
        }

        /** The look of a button that has one text (Scan, a dialog button): the button text is the text. */
        fun ofButton(button: TextView): FocusLook = of(button).also { if (it.texts.isEmpty()) it.text(button) }
    }
}
