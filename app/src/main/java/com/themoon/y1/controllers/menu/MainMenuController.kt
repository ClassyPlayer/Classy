package com.themoon.y1.controllers.menu

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.themoon.y1.R
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.photos.PhotoPreviewView
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.Pane
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.navigation.ScreenState

/**
 * The main menu: a [ListScreen] of the [MainMenuItems] rows (order and hidden rows from Settings → Menus → Main Menu
 * Items) with the cover pane at the right ([Pane]). While the Photos row is selected, random photos show over the
 * cover ([PhotoPreviewView]). A hold on a row turns the screen off.
 */
class MainMenuController(
    activity: Activity,
    override val rootView: ViewGroup,
    clickFeedback: () -> Unit,
    private val onAction: (action: String) -> Unit,
    private val onHold: () -> Unit,
    private val localize: (String) -> String,
    /** Puts the art into the cover pane (each time it shows). */
    onShowPane: () -> Unit
) : ScreenController {

    private val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    private val rows: LinearLayout = rootView.findViewById(R.id.container_main_menu_items)
    private val frame: FrameLayout = rootView.findViewById(R.id.frame_main_menu_cover)

    private val photos = PhotoPreviewView(activity).apply { visibility = View.GONE }

    init {
        frame.addView(photos, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private val pane = Pane(activity, rows.parent.parent as View, frame, rootView.findViewById<ImageView>(R.id.iv_main_menu_cover), onShowPane)

    /** The key of the row that was selected last: the menu opens at it again. */
    private var lastKey: String? = null

    override val listScreen: ListScreen get() = screen

    private val screen = ListScreen(rows, null, clickFeedback, onRowFocus = { key ->
        lastKey = key
        photos.visibility = if (key == MainMenuItems.PHOTOS) View.VISIBLE else View.GONE
    })

    private fun spec(): ListSpec = ListSpec(null, MainMenuItems.shown(prefs).map { item ->
        RowSpec(localize(item.label), key = item.id, onClick = { onAction(item.action) }, onHold = onHold)
    }, focus = Focus.LAST, id = LIST_ID, pane = pane)

    override fun onEnter(state: ScreenState) {
        // The menu opens at the row that was selected last (no focus on the first row before it)
        lastKey?.let { screen.remember(LIST_ID, it) }
        screen.show(spec())
    }

    override fun onExit() {
        screen.clear()
        // Shown again when its row gets the focus
        photos.visibility = View.GONE
    }

    override fun onRefreshUI() = screen.update(spec())

    // Back on the main menu stays here (the router gives the click)
    override fun onBackPressed(): Boolean = true

    private companion object {
        const val LIST_ID = "main menu"
    }
}
