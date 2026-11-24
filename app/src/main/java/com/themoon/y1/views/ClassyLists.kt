package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import java.util.Collections
import java.util.WeakHashMap

/** List rows in the Classy layout (see [ClassyLayout]). */
object ClassyLists {
    // Recycled list rows can come back with another height; this set tells which titles to put back.
    private val centred: MutableSet<TextView> = Collections.newSetFromMap(WeakHashMap())

    /** Called by [ClassicMenuStyle.apply] for each Classic list row. */
    fun styleClassicRow(row: LinearLayout, title: TextView, heightDp: Int) {
        val d = row.resources.displayMetrics.density
        row.setPadding(px(ClassyMetrics.ROW_PADDING_LEFT, d), 0, px(ClassyMetrics.ROW_PADDING_RIGHT, d), 0)
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, ClassyMetrics.LIST_TEXT_SP * d)
        // Rows with cover art (64 dp) or thumbnails stack two lines of text; only one-line rows are centred.
        if (heightDp == ClassicMenuStyle.ROW_HEIGHT_DP) {
            centreInRow(title, heightDp * d)
        } else if (centred.remove(title)) {
            uncentre(title)
        }
    }

    /**
     * Called at the end of the ListItemViewFactory row builders. [row] can be the title itself (a Button).
     * The row gets the Classy row metrics and cap-centred text.
     */
    fun styleListRow(row: View, title: TextView, value: TextView?) {
        val d = row.resources.displayMetrics.density
        val rowHeight = px(ClassyMetrics.ROW_HEIGHT, d)
        val textSize = ClassyMetrics.LIST_TEXT_SP * d
        val padLeft = px(ClassyMetrics.ROW_PADDING_LEFT, d)
        val padRight = px(ClassyMetrics.ROW_PADDING_RIGHT, d)
        row.layoutParams?.let {
            it.height = rowHeight
            row.layoutParams = it
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        title.typeface = ThemeManager.getCustomFontBold()
        title.includeFontPadding = false
        if (row === title) {
            title.gravity = ClassyLayout.horizontalGravity(title) or Gravity.TOP
            title.setPadding(padLeft, ClassyLayout.capCentredPaddingTop(title, rowHeight.toFloat()), padRight, 0)
            return
        }
        row.setPadding(padLeft, 0, padRight, 0)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        centreInRow(title, rowHeight.toFloat())
        if (value != null) {
            value.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            centreInRow(value, rowHeight.toFloat())
        }
    }

    private fun centreInRow(tv: TextView, rowHeight: Float) {
        centred.add(tv)
        tv.includeFontPadding = false
        val lp = tv.layoutParams
        if (lp != null && lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            tv.layoutParams = lp
        }
        tv.gravity = ClassyLayout.horizontalGravity(tv) or Gravity.TOP
        tv.setPadding(tv.paddingLeft, ClassyLayout.capCentredPaddingTop(tv, rowHeight), tv.paddingRight, 0)
    }

    private fun uncentre(tv: TextView) {
        val lp = tv.layoutParams
        if (lp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            tv.layoutParams = lp
        }
        tv.gravity = ClassyLayout.horizontalGravity(tv) or Gravity.CENTER_VERTICAL
        tv.setPadding(tv.paddingLeft, 0, tv.paddingRight, 0)
    }

    /**
     * The disclosure arrow of a row that opens a list: the bold "›" ([ClassyChevronDrawable]) at the right of the
     * row, [leftMarginDp] after the text. All lists use it; it shows only on the selected row ([FocusLook.arrow]).
     * [R.id.list_arrow] marks it for List Glide ([ListGlide.arrowOf]). GONE until the row has its look.
     */
    fun arrow(context: Context, leftMarginDp: Int): TextView = TextView(context).apply {
        val d = context.resources.displayMetrics.density
        id = R.id.list_arrow
        includeFontPadding = false
        // The text size gives the height of the view (the arrow is centred in it), as the rows had with the text arrow
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ListItemViewFactory.LIST_TEXT_SIZE * d)
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = (leftMarginDp * d).toInt() }
        val chevron = ClassyChevronDrawable(ThemeManager.getListButtonFocusedTextColor(), ClassyMetrics.CHEVRON_STROKE * d)
        chevron.setBounds(0, 0, px(ClassyMetrics.CHEVRON_WIDTH, d), px(ClassyMetrics.CHEVRON_HEIGHT, d))
        setCompoundDrawables(null, null, chevron, null)
        compoundDrawablePadding = 0
    }

    private fun px(dp: Int, d: Float): Int = ClassyLayout.px(dp, d)
}

/** The bold "›" chevron of the selected row, drawn to the bounds (10 x 16 px). */
class ClassyChevronDrawable(color: Int, strokeWidth: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.strokeWidth = strokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = paint.strokeWidth / 2f
        path.reset()
        path.moveTo(b.left + inset, b.top + inset)
        path.lineTo(b.right - inset * 1.5f, b.exactCenterY())
        path.lineTo(b.left + inset, b.bottom - inset)
        canvas.drawPath(path, paint)
    }

    override fun getIntrinsicWidth(): Int = bounds.width()

    override fun getIntrinsicHeight(): Int = bounds.height()

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Backward compatibility aliases. */
typealias IpodClassicLists = ClassyLists
