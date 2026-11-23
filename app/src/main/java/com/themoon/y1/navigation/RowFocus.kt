package com.themoon.y1.navigation

import com.themoon.y1.AppPrefs
import com.themoon.y1.Prefs
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ListView
import com.themoon.y1.views.EdgeFadeListView
import com.themoon.y1.views.ListEdges
import com.themoon.y1.views.ListGlide

/**
 * One wheel step in a list of rows, the same for all row lists: the next selectable row (visible and focusable,
 * so headers and info rows are skipped) in the wheel direction. At an end of the list the step goes round to the
 * other end when Settings → Controls → Wheel Loop Scroll is On, else it stays. With no selected row, the first
 * selectable row is selected. With Settings → Controls → List Glide On, a step glides ([ListGlide]).
 */
object RowFocus {

    /**
     * Settings → Controls → Wheel Loop Scroll (default On). With the Y2 pad wheel, a turn stops at the list end
     * ([PadWheel.stepMayWrap]).
     */
    fun isLoopOn(context: Context): Boolean =
        Prefs.LOOP_SCROLL.get(AppPrefs.get(context)) && PadWheel.stepMayWrap

    /** Settings → Controls → Letter Jump (default On) and Letter Jump Sensitivity; null when Letter Jump is Off. */
    fun letterJumpSensitivity(context: Context): LetterJump.Sensitivity? {
        val prefs = AppPrefs.get(context)
        return LetterJump.sensitivity(Prefs.LETTER_JUMP.get(prefs), Prefs.LETTER_JUMP_SENSITIVITY.get(prefs))
    }

    fun isSelectable(row: View?): Boolean = row != null && row.visibility == View.VISIBLE && row.isFocusable

    /** The row after one step from [current] (-1 = no row) in the direction of [delta], or null to stay. */
    fun next(count: Int, current: Int, delta: Int, loop: Boolean, selectable: (Int) -> Boolean): Int? {
        if (current !in 0 until count) return (0 until count).firstOrNull(selectable)
        val direction = if (delta < 0) -1 else 1
        for (n in 1 until count) {
            var i = current + direction * n
            if (i !in 0 until count) {
                if (!loop) return null
                i = i.mod(count)
            }
            if (selectable(i)) return i
        }
        return null
    }

    /** Moves the focus one step in the rows of [list]. Returns the index of the new row, or null if it did not move. */
    fun step(list: ViewGroup, delta: Int, loop: Boolean = isLoopOn(list.context)): Int? {
        val current = (0 until list.childCount).firstOrNull { list.getChildAt(it)?.hasFocus() == true } ?: -1
        val i = next(list.childCount, current, delta, loop) { isSelectable(list.getChildAt(it)) } ?: return null
        ListGlide.beforeStep(list, current, i)
        list.getChildAt(i).requestFocus()
        return i
    }

    /**
     * The same step in an [AbsListView] (song lists): it has views only for the rows on the screen, so a disabled row
     * of the adapter is the row that cannot be selected. Returns the position of the new row, or null if it did not move.
     */
    fun step(list: AbsListView, delta: Int, loop: Boolean = isLoopOn(list.context)): Int? {
        val focus = list.findFocus()
        val current = if (focus == null || focus === list) -1 else list.getPositionForView(focus)
        val adapter = list.adapter
        val i = next(list.count, current, delta, loop) { adapter?.isEnabled(it) != false } ?: return null
        ListGlide.beforeStep(list, current, i)
        showRow(list, i)
        return i
    }

    /**
     * A step to the next row with the ListView's own D-pad step (ListView.arrowScroll): it moves the focus and the
     * selection and scrolls the rows by a few pixels with no layout pass (a selection change by
     * [ListView.setSelectionFromTop] lays out the list and the window again, ~15 ms on the Y2). With List Edges =
     * Fade, a row that stays under a fade is then moved clear of it ([ListEdges.revealRow]). Only Android 4.4 (Y2): the Y1 keeps
     * the path below. False when it cannot do the step (not the next row, the selection is not on the focused
     * row): then the caller does it.
     */
    private fun arrowStep(list: EdgeFadeListView, position: Int): Boolean {
        if (Build.VERSION.SDK_INT < 19) return false
        val focus = list.findFocus() ?: return false
        if (focus === list) return false
        val current = list.getPositionForView(focus)
        if (current == AdapterView.INVALID_POSITION || list.selectedItemPosition != current) return false
        val key = when (position) {
            current + 1 -> KeyEvent.KEYCODE_DPAD_DOWN
            current - 1 -> KeyEvent.KEYCODE_DPAD_UP
            else -> return false
        }
        list.inArrowStep = true
        try {
            // ListView.arrowScroll is not public: the D-pad key of the list runs it
            list.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key))
        } finally {
            list.inArrowStep = false
        }
        val row = list.getChildAt(position - list.firstVisiblePosition)
        if (row == null || !row.hasFocus()) return false
        // The D-pad step scrolls only when the row goes past the list edge: a row inside the list but under a fade
        // stays there. List Edges = Fade: move it clear of the fade (a layout only in that case)
        ListEdges.revealRow(list, row)
        return true
    }

    /**
     * Selects row [position] of [list]. A row that is not on the screen comes into the list first. With
     * List Edges = Fade the row stays clear of the fades ([ListEdges.focusRow]).
     */
    fun showRow(list: AbsListView, position: Int) {
        if (list is EdgeFadeListView && arrowStep(list, position)) return
        if (list is ListView && ListEdges.focusRow(list, position)) return
        val first = list.firstVisiblePosition
        val row = if (position in first..list.lastVisiblePosition) list.getChildAt(position - first) else null
        if (row?.requestFocus() == true) {
            // Android 4.2 does not scroll a ListView to a row that only got the focus: with List Edges = Cut
            // a step down stopped at the row that was half on the screen. Fade mode scrolls in ListEdges.revealRow.
            // Android 4.4 (Y2) scrolls by itself: keep its behaviour.
            // A ListView-typed call: on the smart-cast list the call goes to AbsListView.setSelectionFromTop (API 21)
            val listView: ListView? = list as? ListView
            if (android.os.Build.VERSION.SDK_INT < 19 && listView != null) {
                val delta = ListEdges.revealDelta(row.top, row.bottom, list.paddingTop, list.height - list.paddingBottom, 0, false, false)
                // Also when nothing has to scroll: the selection must follow the focus, or the next layout puts the
                // focus back on the old selected row and the wheel step down stays on the same row (as in ListEdges.revealRow).
                if (delta != 0 || position != listView.selectedItemPosition) {
                    listView.setSelectionFromTop(position, row.top - delta - list.paddingTop)
                }
            }
            return
        }
        list.setSelection(position)
        // The row view exists after the next layout
        list.post { list.getChildAt(position - list.firstVisiblePosition)?.requestFocus() }
    }
}
