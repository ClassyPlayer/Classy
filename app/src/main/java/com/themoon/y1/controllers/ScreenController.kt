package com.themoon.y1.controllers

import android.view.KeyEvent
import android.view.ViewGroup
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.navigation.RowKey
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.WheelKind
import com.themoon.y1.views.ListRows

/**
 * Common lifecycle and hardware input interface implemented by every screen view.
 */
interface ScreenController {
    val rootView: ViewGroup

    /**
     * The list of this screen, or null. The defaults below give it the wheel, the keys (letter mode) and the pad
     * wheel touches, and take the status bar title from it.
     */
    val listScreen: ListScreen? get() = null

    fun onEnter(state: ScreenState) {}
    fun onExit() {}
    fun onRefreshUI() {}

    /** The status bar title of this screen, or null: then MainActivity gives the title of the screen kind. */
    fun title(): String? = listScreen?.title

    /** The library cache finished loading after the screen was built: show the library data again. */
    fun onLibraryLoaded() {}

    /** The theme changed while this screen shows: make it again in the new theme, with the same focus. */
    fun onThemeChanged() {
        onRefreshUI()
    }

    /**
     * The selected row of the list on this screen, or null. [com.themoon.y1.navigation.NavigationManager]
     * keeps it when a deeper screen opens, and gives it to [selectRow] when Back comes back here.
     */
    fun selectedRow(): RowKey? = ListRows.selected(rootView)

    /** Back came back to this screen (after [onEnter]): select [row], the row that opened the deeper screen. */
    fun selectRow(row: RowKey) = ListRows.select(rootView, row)

    fun dispatchKeyEvent(event: KeyEvent): Boolean = listScreen?.key(event) ?: false
    fun onWheel(delta: Int, event: KeyEvent): Boolean = listScreen?.wheel(delta, event.eventTime) ?: false

    /**
     * What a wheel turn moves on this screen now: the Y2 pad wheel ([com.themoon.y1.navigation.PadWheel]) gives the
     * Wheel Mode rate to a list, and 10 steps a turn to a value or letters. Asked before each pad-wheel step.
     */
    fun wheelKind(): WheelKind = listScreen?.wheelKind ?: WheelKind.LIST

    /** The thumb touched ([isTouching]) or left the Y2 pad wheel at [timeMs] (uptime): letter mode ends after a lift. */
    fun onWheelTouch(isTouching: Boolean, timeMs: Long) {
        listScreen?.wheelTouch(isTouching, timeMs)
    }
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean = false
    fun onBackPressed(): Boolean = false
}
