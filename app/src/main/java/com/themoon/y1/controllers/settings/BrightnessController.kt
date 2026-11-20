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
import com.themoon.y1.navigation.LetterJump
import com.themoon.y1.navigation.RowFocus
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.WheelKind

/**
 * Controller for Display Brightness screen:
 * Displays progress bar, handles wheel/dpad left-right stepping (1 % a step; a fast wheel turn: to the next 5 %, with
 * the Letter Jump settings), and writes to system brightness.
 */
class BrightnessController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit
) : ScreenController {

    private val pbBrightness: ProgressBar = rootView.findViewById(R.id.pb_brightness)
    private val tvBrightnessVal: TextView = rootView.findViewById(R.id.tv_brightness_val)

    var currentSystemBrightness: Int = 255

    /** A fast wheel turn, by the rule that starts the letter jump. */
    private val wheelSpeed = LetterJump.WheelSpeed()

    override fun onEnter(state: ScreenState) {
        loadBrightnessUI()
    }

    override fun onRefreshUI() {
        loadBrightnessUI()
    }

    override fun wheelKind(): WheelKind = WheelKind.VALUE

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        val fast = wheelSpeed.onStep(delta, event.eventTime, RowFocus.letterJumpSensitivity(activity))
        val target = if (fast) fastStepped(currentSystemBrightness, delta) else stepped(currentSystemBrightness, delta)
        setLevel(target)
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

        pbBrightness.progress = percent(currentSystemBrightness)
        tvBrightnessVal.text = "${percent(currentSystemBrightness)}%"
        // The layout has white text: in a light theme it does not show. Use the theme text colour (as the title).
        tvBrightnessVal.setTextColor(ThemeManager.getTextColorPrimary())
    }

    /** [steps] of 1 %. */
    fun adjustBrightness(steps: Int) = setLevel(stepped(currentSystemBrightness, steps))

    private fun setLevel(target: Int) {
        if (target != currentSystemBrightness) {
            updateBrightness(target)
            clickFeedback()
        }
    }

    fun updateBrightness(newBrightness: Int) {
        currentSystemBrightness = newBrightness
        pbBrightness.progress = percent(currentSystemBrightness)
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
        fun percent(level: Int): Int = ((level * 100 + 127) / 255).coerceIn(0, 100)

        fun level(percent: Int): Int = ((percent * 255 + 50) / 100).coerceIn(0, 255)

        /** The system level [steps] percent from [level], from 0 to 255. */
        fun stepped(level: Int, steps: Int): Int = level(percent(level) + steps)

        /** The system level of the next multiple of 5 % from [level], in the direction of [direction]. */
        fun fastStepped(level: Int, direction: Int): Int {
            val p = percent(level)
            return level(if (direction > 0) (p / 5 + 1) * 5 else (p - 1).floorDiv(5) * 5)
        }
    }
}
