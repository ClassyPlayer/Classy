package com.themoon.y1.controllers.settings

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.TextView
import com.themoon.y1.ClockSettings
import com.themoon.y1.MainActivity
import com.themoon.y1.Prefs
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.SleepTimer
import com.themoon.y1.managers.SleepTimerMath
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.WheelKind
import com.themoon.y1.views.ClassyMetrics
import com.themoon.y1.views.Notice
import java.util.Date

/** The sleep timer's texts and start, shared by the Power screen, its list and the custom screen. */
object SleepTimerText {
    /** "45 min", "2 h", "1 h 05 min", translated. */
    fun duration(minutes: Int, t: (String) -> String): String =
        SleepTimerMath.durationText(minutes, t("%d min"), t("%d h"), t("%d h %02d min"))

    /** Starts the timer for [minutes] and tells it. */
    fun start(context: Context, minutes: Int, t: (String) -> String) {
        SleepTimer.start(context, minutes)
        Notice.show(context, t("Sleep timer set for ") + duration(minutes, t))
    }
}

/**
 * Power → Sleep Timer → Custom…: a time set with the wheel, no bar. The time shows big, with the clock time it ends
 * under it. The wheel moves single minutes when it turns slowly and bigger, round steps when it turns fast
 * ([SleepTimerMath.wheelStep]), from 1 minute to 24 hours. Centre starts the timer (and keeps the time as the last
 * custom one, on top of the list) and goes back to the Power screen; Back goes back to the list without a change.
 */
class SleepTimerController(
    private val main: MainActivity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit
) : ScreenController {

    private val tvValue: TextView = rootView.findViewById(R.id.tv_sleep_timer_value)
    private val tvEnds: TextView = rootView.findViewById(R.id.tv_sleep_timer_ends)

    private var minutes = SleepTimerMath.DEFAULT_CUSTOM_MINUTES
    /** The time and direction of the last wheel detent (for the fast steps); a turn back starts slow. */
    private var lastWheelMs = -1L
    private var lastDirection = 0

    override fun onEnter(state: ScreenState) {
        minutes = SleepTimerMath.customStart(SleepTimer.minutesLeft(), Prefs.SLEEP_TIMER_CUSTOM.get(main.prefs))
        lastWheelMs = -1L
        lastDirection = 0
        show()
    }

    override fun onRefreshUI() = show()

    override fun title(): String = main.t("Sleep Timer")

    override fun wheelKind(): WheelKind = WheelKind.VALUE

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (delta == 0) return true
        val direction = if (delta > 0) 1 else -1
        val interval = if (direction == lastDirection && lastWheelMs >= 0) event.eventTime - lastWheelMs else -1L
        lastWheelMs = event.eventTime
        lastDirection = direction
        val step = SleepTimerMath.wheelStep(interval)
        var next = minutes
        repeat(Math.abs(delta)) { next = SleepTimerMath.stepped(next, direction, step) }
        setMinutes(next)
        return true
    }

    // Next/Previous: one minute (also when held, each repeat); their release must not skip a song
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> setMinutes(SleepTimerMath.stepped(minutes, 1, 1))
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> setMinutes(SleepTimerMath.stepped(minutes, -1, 1))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {}
            else -> return false
        }
        return true
    }

    // Centre on release: the release of a press must not reach the Power screen under this one
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            if (!event.isCanceled) startTimer()
            true
        }
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
        else -> false
    }

    private fun setMinutes(next: Int) {
        if (next == minutes) return
        minutes = next
        clickFeedback()
        show()
    }

    private fun startTimer() {
        Prefs.SLEEP_TIMER_CUSTOM.set(main.prefs, minutes)
        SleepTimerText.start(main, minutes, main::t)
        main.clickFeedback()
        // Back to the list (its row stays as it was), then the list closes: Power with Sleep Timer selected
        NavigationManager.getInstance().popBackStack()
        main.settingsController.closeSleepTimerList()
    }

    private fun show() {
        tvValue.text = SleepTimerText.duration(minutes, main::t)
        val endsAt = Date(System.currentTimeMillis() + minutes * 60_000L)
        tvEnds.text = String.format(main.t("Ends at %s"), ClockSettings.formatStatusBarTime(endsAt, main.is24HourFormat))
        tvValue.setTextColor(ThemeManager.getTextColorPrimary())
        tvEnds.setTextColor(ThemeManager.getTextColorSecondary())
        tvValue.typeface = ThemeManager.getCustomFontBold()
        tvEnds.typeface = ThemeManager.getCustomFont()
        rootView.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        // The status bar is drawn over the screens: centre the time in the space under it
        val top = (ClassyMetrics.STATUS_BAR_HEIGHT * main.resources.displayMetrics.density).toInt()
        if (rootView.paddingTop != top) rootView.setPadding(0, top, 0, 0)
    }
}
