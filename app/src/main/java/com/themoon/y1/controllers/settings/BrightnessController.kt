package com.themoon.y1.controllers.settings

import android.app.Activity
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState

/**
 * Controller for Display Brightness screen:
 * Displays progress bar, handles wheel/dpad left-right stepping (1 % a step), and writes to system brightness.
 */
class BrightnessController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit
) : ScreenController {

    private val pbBrightness: ProgressBar = rootView.findViewById(R.id.pb_brightness)
    private val tvBrightnessVal: TextView = rootView.findViewById(R.id.tv_brightness_val)

    var currentSystemBrightness: Int = 255

    override fun onEnter(state: ScreenState) {
        loadBrightnessUI()
    }

    override fun onRefreshUI() {
        loadBrightnessUI()
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        adjustBrightness(delta)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == 21) {
            adjustBrightness(-1)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == 22) {
            adjustBrightness(1)
            return true
        }
        return false
    }

    fun loadBrightnessUI() {
        try {
            currentSystemBrightness = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                255
            )
        } catch (_: Exception) {}

        pbBrightness.progress = currentSystemBrightness
        tvBrightnessVal.text = "${percent(currentSystemBrightness)}%"
        // The layout has white text: in a light theme it does not show. Use the theme text colour (as the title).
        tvBrightnessVal.setTextColor(ThemeManager.getTextColorPrimary())
    }

    /** [steps] of 1 %. */
    fun adjustBrightness(steps: Int) {
        val target = stepped(currentSystemBrightness, steps)
        if (target != currentSystemBrightness) {
            updateBrightness(target)
            clickFeedback()
        }
    }

    fun updateBrightness(newBrightness: Int) {
        currentSystemBrightness = newBrightness
        pbBrightness.progress = currentSystemBrightness
        tvBrightnessVal.text = "${percent(currentSystemBrightness)}%"

        try {
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                currentSystemBrightness
            )
            val lp: WindowManager.LayoutParams = activity.window.attributes
            lp.screenBrightness = currentSystemBrightness / 255.0f
            activity.window.attributes = lp
        } catch (_: Exception) {}
    }

    companion object {
        /** The lowest system level (0–255): a darker screen can look off. */
        const val MIN_LEVEL = 10

        fun percent(level: Int): Int = (level * 100 + 127) / 255

        fun level(percent: Int): Int = (percent * 255 + 50) / 100

        /** The system level [steps] percent from [level], from [MIN_LEVEL] to 255. */
        fun stepped(level: Int, steps: Int): Int = level(percent(level) + steps).coerceIn(MIN_LEVEL, 255)
    }
}
