package com.themoon.y1.views

import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.ThemeManager

/** Shared metrics of the Classy layout ([ClassyMetrics]), 480 x 360 reference. */
object ClassicMenuStyle {
    const val ROW_HEIGHT_DP: Int = ClassyMetrics.ROW_HEIGHT
    const val STATUS_HEIGHT_DP: Int = ClassyMetrics.STATUS_BAR_HEIGHT
    const val PANE_WIDTH_DP: Int = ClassyMetrics.PANE_WIDTH
    fun apply(row: LinearLayout, title: TextView, heightDp: Int) {
        val density = row.resources.displayMetrics.density
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        row.minimumHeight = (heightDp * density).toInt()
        val params = row.layoutParams
        if (params != null) {
            params.height = (heightDp * density).toInt()
            if (params is ViewGroup.MarginLayoutParams) {
                params.setMargins(0, 0, 0, 0)
            }
            row.layoutParams = params
        }
        title.includeFontPadding = false
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21 * density)
        title.typeface = ThemeManager.getCustomFontBold()
        ClassyLists.styleClassicRow(row, title, heightDp)
    }
}
