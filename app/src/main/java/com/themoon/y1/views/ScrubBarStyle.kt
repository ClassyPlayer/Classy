package com.themoon.y1.views

import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar

/**
 * Makes a SeekBar a scrub bar: the bar at the place and with the height of the progress bar that it replaces, and the
 * diamond centred on the end of the fill ([ScrubBarGeometry], [ScrubDiamondDrawable]). Now Playing and the video
 * player use it.
 *
 * The view is larger than the bar by the outsets: the bar is inside the padding, and negative margins keep it at the
 * progress bar position. The default SeekBar padding (16 px each side) and thumb position made the bar shorter and put
 * the diamond off the end of the fill.
 */
internal object ScrubBarStyle {

    /** How the view properties change. [ClassyLayout] records each change, so that a theme change can undo it. */
    interface Edits {
        fun setHeight(v: View, height: Int)
        /** Sets the margins that are not null. */
        fun setMargins(v: View, left: Int, top: Int?, right: Int, bottom: Int?)
        fun setPadding(v: View, left: Int, top: Int, right: Int, bottom: Int)
        /** Called before the thumb and the thumb offset change. */
        fun beforeThumb(bar: SeekBar) {}
    }

    /** Changes the views directly, for views that keep the style in all themes. */
    object Direct : Edits {
        override fun setHeight(v: View, height: Int) {
            val lp = v.layoutParams ?: return
            if (lp.height != height) {
                lp.height = height
                v.layoutParams = lp
            }
        }

        override fun setMargins(v: View, left: Int, top: Int?, right: Int, bottom: Int?) {
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            lp.leftMargin = left
            lp.rightMargin = right
            if (top != null) lp.topMargin = top
            if (bottom != null) lp.bottomMargin = bottom
            v.layoutParams = lp
        }

        override fun setPadding(v: View, left: Int, top: Int, right: Int, bottom: Int) {
            v.setPadding(left, top, right, bottom)
        }
    }

    /**
     * Sets the height, the margins, the padding and the thumb of [bar] for a bar of [barHeight] and a diamond of
     * [diamondWidth] x [diamondHeight] (px). The caller sets the width: the bar width plus 2 x [ScrubBarGeometry.outsetX],
     * or a layout weight (the negative margins then add the outsets).
     * [marginsY]: negative margins also above and below, so that the row measures only the bar height.
     */
    fun apply(bar: SeekBar, barHeight: Int, diamondWidth: Int, diamondHeight: Int, edits: Edits, marginsY: Boolean = false) {
        val ox = ScrubBarGeometry.outsetX(diamondWidth)
        val oy = ScrubBarGeometry.outsetY(diamondHeight, barHeight)
        edits.setHeight(bar, barHeight + 2 * oy)
        edits.setMargins(bar, left = -ox, top = if (marginsY) -oy else null, right = -ox, bottom = if (marginsY) -oy else null)
        edits.setPadding(bar, ox, oy, ox, oy)

        edits.beforeThumb(bar)
        val thumb = bar.thumb as? ScrubDiamondDrawable
        if (thumb == null || thumb.diamondWidth != diamondWidth || thumb.diamondHeight != diamondHeight) {
            bar.thumb = ScrubDiamondDrawable(bar, diamondWidth, diamondHeight)
        }
        // The thumb is drawn with the origin at the bar's top-left corner (paddingLeft - thumbOffset, paddingTop).
        bar.thumbOffset = 0
    }
}
