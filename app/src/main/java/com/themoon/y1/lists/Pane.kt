package com.themoon.y1.lists

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.ClassyMetrics
import com.themoon.y1.views.CoverPane

/**
 * The split screen of a list ([ListSpec.pane]): the list pane at the left ([ClassyMetrics.PANE_WIDTH]
 * wide) and the art pane at the right ([CoverPane]). The status bar keeps the full width.
 * A screen makes one [Pane] for its views; [ListScreen] shows it for the lists that have it and hides it for the others.
 *
 * [onShow] runs each time the pane shows (for example: the art of the song that plays).
 */
class Pane(
    private val activity: Activity,
    private val listPane: View,
    private val frame: View,
    private val image: ImageView?,
    private val onShow: (() -> Unit)? = null
) {
    private var imageSetUp = false

    /** True while the art pane shows. */
    val isShown: Boolean get() = frame.visibility == View.VISIBLE

    fun show() {
        split(true)
        if (!imageSetUp && image != null) {
            imageSetUp = true
            CoverPane.attach(image)
        }
        onShow?.invoke()
    }

    fun hide() = split(false)

    private fun split(on: Boolean) {
        if (on) frame.visibility = View.VISIBLE
        val density = activity.resources.displayMetrics.density
        listPane.setPadding(0, (ClassyMetrics.STATUS_BAR_HEIGHT * density).toInt(), 0, 0)
        val lp = listPane.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.width = if (on) (ClassyMetrics.PANE_WIDTH * density).toInt() else 0
            lp.weight = if (on) 0f else 1f
            listPane.layoutParams = lp
        }
        activity.findViewById<View>(R.id.layout_status_bar)?.background = ThemeManager.createStatusBarBackground()
        if (!on) frame.visibility = View.GONE
    }
}
