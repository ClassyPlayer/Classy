package com.themoon.y1.navigation

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.widget.AbsListView
import android.widget.LinearLayout
import com.themoon.y1.MainActivity
import com.themoon.y1.Prefs
import com.themoon.y1.showNowPlaying
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.player.PlayerController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AudioSources
import java.lang.ref.WeakReference

/**
 * Handles global key intercepts (wake logic, volume overlay, UMS blocking)
 * and delegates wheel/button events to active ScreenController or NavigationManager.
 */
class InputRouter private constructor() {

    @Volatile
    var isBackSuppressed: Boolean = false
        private set

    @Volatile
    var backDebounceUntil: Long = 0L
        private set

    private val backPress = BackPress()

    // Play/Pause presses that this router handles itself (screens without their own handling)
    private var isPlayPauseTracked = false
    private var isScreenOffPlayPauseDown = false
    private var isScreenOffStopDone = false
    // Set when Next/Previous auto-repeats, so the release does not skip the track
    @Volatile
    var isSkipKeyHeld: Boolean = false
        private set

    // Hold-to-seek state (uptime ms): +1 forward, -1 back, 0 when not seeking
    private var seekDirection = 0
    private var seekStartMs = 0L
    private var lastSeekTickMs = 0L
    private var lastSkipRepeatMs = 0L
    /** Screen off: Android sends one long-press and then the key-up, no repeats, so the key-up ends the seek. */
    private var seekWaitsForKeyUp = false
    // The track when the seek started: a screen-off seek stops when it changes
    private var seekTrackPath: String? = null
    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekTickRunnable = object : Runnable {
        override fun run() {
            if (seekDirection == 0) return
            val now = SystemClock.uptimeMillis()
            if (seekTimedOut(now, lastSkipRepeatMs, seekStartMs, seekWaitsForKeyUp)) {
                // No key repeats any more (or too long without the key-up), so the key-up went elsewhere
                stopSeek()
                return
            }
            if (screenOffSeekEnded()) {
                stopSeek()
                return
            }
            onSeekTick(now)
            if (screenOffSeekEnded()) {
                stopSeek()
                return
            }
            seekHandler.postDelayed(this, SEEK_TICK_MS)
        }
    }

    /**
     * A lost key-up must not seek through the next tracks: a screen-off seek ends at the end margin, the start or a
     * new track. Checked before and after each tick: after a tick the position is the seek target (clamped at 0), so
     * a back seek stops at 0 also while the music plays on.
     */
    private fun screenOffSeekEnded(): Boolean {
        if (!seekWaitsForKeyUp || seekDirection == 0) return false
        val player = AudioPlayerManager.getInstance()
        return screenOffSeekEnds(seekDirection, player.getCurrentPosition(), player.getDuration(),
            player.currentlyPlayingFilePath != seekTrackPath)
    }

    fun suppressBackUntilRelease(debounceMs: Long = 350L) {
        isBackSuppressed = true
        backDebounceUntil = System.currentTimeMillis() + debounceMs
    }

    fun resetBackSuppression() {
        isBackSuppressed = false
        backDebounceUntil = 0L
    }

    fun interface VolumeAdjuster {
        fun adjustVolume(isUp: Boolean)
    }

    // One centre push does one action, on release (see CentrePressTracker)
    private val centrePress = CentrePressTracker()
    // The focused view when the centre press came: it can have taken the press
    private var centrePressView: WeakReference<View>? = null

    private fun isCentreKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    // Extra contacts of one centre push (see KeyBounce)
    private val centreBounce = KeyBounce()

    /** A centre event from a button: keys from `adb shell input` and other software do not bounce. */
    private fun isCentreButton(event: KeyEvent): Boolean =
        isCentreKey(event.keyCode) && event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD

    /** True: [event] is the press of a centre bounce (see KeyBounce). Does not change the state. */
    fun isCentreBounce(event: KeyEvent): Boolean =
        isCentreButton(event) && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            centreBounce.isBounce(event.keyCode, event.downTime)

    /** Gives each centre button event to [centreBounce]. True: the event belongs to a bounce, drop it. */
    private fun dropsCentreBounce(event: KeyEvent): Boolean {
        if (!isCentreButton(event)) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN ->
                if (event.repeatCount == 0) centreBounce.onPress(event.keyCode, event.downTime) else centreBounce.onRepeat(event.keyCode)
            KeyEvent.ACTION_UP -> centreBounce.onRelease(event.keyCode, event.eventTime)
            else -> false
        }
    }

    /**
     * A dropped release: the view that had the press does not get it (or did not use it). Stop its pending
     * long press and its pressed state; Android 4.4 cancels them only in the view's own key-up of a click.
     */
    private fun releaseCentrePressView() {
        val view = centrePressView?.get() ?: return
        centrePressView = null
        view.cancelLongPress()
        if (view.isPressed) view.isPressed = false
    }

    fun dispatchKeyEvent(
        event: KeyEvent,
        context: Context,
        isFakeScreenOff: Boolean,
        isScreenOffControlEnabled: Boolean = false,
        onWakeFromFakeScreenOff: Runnable,
        onResetBacklightTimer: Runnable,
        isUsbStorageActive: Boolean,
        onStopUsbStorage: Runnable,
        onAdjustVolume: VolumeAdjuster,
        activeController: ScreenController?
    ): Boolean {
        // The extra contacts of one centre push do nothing (see KeyBounce)
        if (dropsCentreBounce(event)) return true
        val keyCode = event.keyCode
        val action = event.action
        // A press kept below (wake, USB storage screen) must not pair with its release
        if (isCentreKey(keyCode) && action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) centrePress.reset()
        // A Back press kept below (wake, USB storage screen, suppression) must not pair with its release
        if (isBackKey(keyCode) && action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backPress.reset()

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = if (android.os.Build.VERSION.SDK_INT >= 20) {
            pm?.isInteractive ?: true
        } else {
            pm?.isScreenOn ?: true
        }

        if (isScreenOn && !isFakeScreenOff) {
            onResetBacklightTimer.run()
        }

        // Intercept while USB mass storage modal is active
        if (isUsbStorageActive) {
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onStopUsbStorage.run()
                }
                return true
            }
        }

        // Global volume button interception
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 || (event.repeatCount % 2 == 0)) {
                    onAdjustVolume.adjustVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                }
            }
            return true
        }

        // Screen is off: block or allow only media controls based on isScreenOffControlEnabled
        if (!isScreenOn) {
            // Media keys from a remote (Bluetooth, wired headset) always act; Screen-Off Control is for the Y2's own buttons
            if (!RemoteMediaKeys.actsWithScreenOff(event, isScreenOffControlEnabled)) {
                return true
            }
            // The same media keys as RemoteMediaKeys, also the wired headset button (HEADSETHOOK: play/pause)
            val isMediaKey = RemoteMediaKeys.isMediaKey(keyCode)

            if (isMediaKey) {
                if (isPlayPauseKey(keyCode) || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                    // Short press plays or pauses on release; a hold stops
                    if (action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount == 0) {
                            isScreenOffPlayPauseDown = true
                            isScreenOffStopDone = false
                        } else if (event.isLongPress && isScreenOffPlayPauseDown && !isScreenOffStopDone) {
                            isScreenOffStopDone = true
                            com.themoon.y1.managers.AudioPlayerManager.getInstance().stopMusic()
                        }
                    } else if (action == KeyEvent.ACTION_UP) {
                        if (isScreenOffPlayPauseDown && !isScreenOffStopDone && !event.isCanceled) {
                            AudioSources.app.playPauseKey()
                        }
                        isScreenOffPlayPauseDown = false
                        isScreenOffStopDone = false
                    }
                    return true
                }
                if (isSkipKey(keyCode)) {
                    // Same press/hold behaviour as with the screen on
                    if (action == KeyEvent.ACTION_DOWN) {
                        onSkipKeyDown(keyCode, event.repeatCount, event.eventTime)
                    } else if (action == KeyEvent.ACTION_UP) {
                        onSkipKeyUp(keyCode, event.eventTime - event.downTime)
                    }
                }
                return true
            }
            return true
        }

        // Wake from fake screen off
        if (isFakeScreenOff) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onWakeFromFakeScreenOff.run()
            }
            return true
        }

        if (isCentreKey(keyCode)) {
            // The real screen controller: MainActivity can give a wrapper (event log) that is new for each event
            val screen = NavigationManager.getInstance().activeController
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                centrePress.onPress(screen)
                centrePressView = (context as? Activity)?.currentFocus?.let { WeakReference(it) }
            } else if (action == KeyEvent.ACTION_UP && !centrePress.onRelease(screen)) {
                releaseCentrePressView()
                return true
            }
        }

        // Allow active controller to intercept raw dispatchKeyEvent if needed (when screen is on)
        if (isScreenOn && activeController?.dispatchKeyEvent(event) == true) {
            return true
        }

        // Suppress back key repeat / debounce after modal or long-press dismissal
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4) {
            if (isBackSuppressed || System.currentTimeMillis() < backDebounceUntil) {
                if (action == KeyEvent.ACTION_UP) {
                    isBackSuppressed = false
                }
                return true
            }
        }

        return false
    }

    fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        // A focused row did not take this press (it takes it for its own click or long press)
        if (isCentreKey(keyCode) && event.repeatCount == 0) centrePress.onPressRouted()
        if (keyCode == 21 || keyCode == 22) {
            val delta = if (keyCode == 22) 1 else -1
            if (activeController?.onWheel(delta, event) == true) {
                return true
            }

            // A screen with no own wheel code: one step in the list of the selected row
            val focus = activeController?.rootView?.findFocus()
            val rows = focus?.parent
            val moved = when {
                focus is AbsListView -> RowFocus.step(focus, delta) // the list has the focus, not a row
                rows is AbsListView -> RowFocus.step(rows, delta)
                rows is LinearLayout -> RowFocus.step(rows, delta)
                else -> null
            }
            if (moved != null) {
                (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
                return true
            }
        }
        if (activeController?.onKeyDown(keyCode, event) == true) {
            return true
        }
        if (isBackKey(keyCode)) {
            // Back on release (onKeyUp), like the other buttons: a held press does nothing yet
            val now = System.currentTimeMillis()
            if (!isBackSuppressed && now >= backDebounceUntil) backPress.onPress(event.repeatCount)
            // Held (the first key repeat): to Now Playing, when Settings > Controls > Hold Back for Now Playing is On
            val main = MainActivity.instance
            val nav = NavigationManager.getInstance()
            if (main != null && holdOpensNowPlaying(event.repeatCount, backPress.isArmed, Prefs.BACK_HOLD_NOW_PLAYING.get(main.prefs), nav.currentState ?: ScreenState.MainMenu)) {
                suppressBackUntilRelease()
                main.clickFeedback()
                main.showNowPlaying()
            }
            return true
        }
        if (isPlayPauseKey(keyCode)) {
            // Play/pause on release, so that a hold can stop instead (see onKeyLongPress)
            if (event.repeatCount == 0) {
                isPlayPauseTracked = true
                MainActivity.instance?.isMediaLongPressConsumed = false
                event.startTracking()
            }
            return true
        }
        if (isSkipKey(keyCode)) {
            onSkipKeyDown(keyCode, event.repeatCount, event.eventTime)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            val main = (activeController?.rootView?.context as? MainActivity) ?: MainActivity.instance
            if (event.repeatCount == 0) {
                main?.isLongPressConsumed = false
                event.startTracking()
            }
        }
        return false
    }

    fun isBackKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4

    /** The one Back path: one click, then the screen's own Back (a level up in its lists), else the previous screen. */
    fun goBack(activeController: ScreenController?) {
        (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
        if (activeController?.onBackPressed() != true) NavigationManager.getInstance().popBackStack()
    }

    fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        // A focused row had the press and did not use the release (its long press acted): no click
        if (isCentreKey(keyCode) && !centrePress.onReleaseRouted()) {
            releaseCentrePressView()
            return true
        }
        if (activeController?.onKeyUp(keyCode, event) == true) {
            return true
        }
        if (isBackKey(keyCode)) {
            isBackSuppressed = false
            if (backPress.onRelease(event.isCanceled)) goBack(activeController)
            return true
        }
        if (isPlayPauseKey(keyCode) && isPlayPauseTracked) {
            isPlayPauseTracked = false
            val main = MainActivity.instance
            if (main?.isMediaLongPressConsumed == true || (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) != 0) {
                main?.isMediaLongPressConsumed = false
                return true
            }
            if (!event.isCanceled) {
                AudioSources.app.playPauseKey()
            }
            return true
        }
        if (isSkipKey(keyCode)) {
            onSkipKeyUp(keyCode, event.eventTime - event.downTime)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            val main = (activeController?.rootView?.context as? MainActivity) ?: MainActivity.instance
            if (main?.isLongPressConsumed == true) {
                main.isLongPressConsumed = false
                return true
            }
            if ((event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                val focused = activeController?.rootView?.findFocus()
                if (focused != null && focused !is android.widget.Button && focused.isClickable) {
                    focused.performClick()
                    return true
                }
            }
        }
        return false
    }

    fun onKeyLongPress(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        if (activeController?.onKeyLongPress(keyCode, event) == true) {
            return true
        }
        // Screens without their own Play/Pause hold: hold = Stop
        if (isPlayPauseKey(keyCode) && isPlayPauseTracked) {
            val main = MainActivity.instance
            main?.isMediaLongPressConsumed = true
            main?.clickFeedback()
            AudioPlayerManager.getInstance().stopMusic()
            return true
        }
        return false
    }

    private fun isPlayPauseKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == 85

    internal fun isSkipKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87 ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88
    }

    /**
     * The skip key whose press the screen-off receiver passed on: its repeat and release are passed on too, others
     * are ignored. Main thread only.
     */
    private var screenOffSkipKey = 0

    /**
     * Screen off, from the media-button receiver. A second path exists next to [onKeyEvent]'s screen-off branch
     * because that one only runs when the activity gets the key; a media button (car, Bluetooth, wired remote) with
     * the screen off arrives as a broadcast. The broadcast has no key repeats, so the release ends a hold
     * ([onSkipKeyDown] with waitForKeyUp). A press that began with the screen on already acted, and a repeat or
     * release without a press seen here is ignored. The event time can be forged, so the seek runs on this
     * device's clock.
     */
    fun onScreenOffSkipKey(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) screenOffSkipKey = event.keyCode
            if (screenOffSkipKey == event.keyCode) {
                onSkipKeyDown(event.keyCode, event.repeatCount, SystemClock.uptimeMillis(), waitForKeyUp = true)
            }
        } else if (event.action == KeyEvent.ACTION_UP && screenOffSkipKey == event.keyCode) {
            onSkipKeyUp(event.keyCode, event.eventTime - event.downTime)
        }
    }

    /**
     * Next/Previous key down. The first key repeat starts a seek that runs on a timer and speeds up
     * the longer the button is held (see [seekSpeed]); a short press changes the track on release
     * (see [onSkipKeyUp]). Returns the offset of the first seek tick in ms, or 0.
     */
    internal fun onSkipKeyDown(keyCode: Int, repeatCount: Int, eventTimeMs: Long, waitForKeyUp: Boolean = false): Int {
        if (repeatCount == 0) {
            stopSeek()
            isSkipKeyHeld = false
            return 0
        }
        isSkipKeyHeld = true
        lastSkipRepeatMs = eventTimeMs
        if (seekDirection != 0) {
            return 0
        }
        seekDirection = if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) 1 else -1
        seekWaitsForKeyUp = waitForKeyUp
        seekStartMs = eventTimeMs
        seekTrackPath = AudioPlayerManager.getInstance().currentlyPlayingFilePath
        lastSeekTickMs = eventTimeMs - SEEK_TICK_MS
        // One click when the seek starts; a click on every tick would buzz
        MainActivity.instance?.clickFeedback()
        val offsetMs = onSeekTick(eventTimeMs)
        if (screenOffSeekEnded()) stopSeek() else seekHandler.postDelayed(seekTickRunnable, SEEK_TICK_MS)
        return offsetMs
    }

    /** One seek tick: moves by the seek speed times the time since the last tick. Returns the offset in ms. */
    internal fun onSeekTick(nowMs: Long): Int {
        if (seekDirection == 0) return 0
        val stepMs = seekStepMs(nowMs - seekStartMs, nowMs - lastSeekTickMs)
        lastSeekTickMs = nowMs
        val player = AudioPlayerManager.getInstance()
        val offsetMs = if (seekDirection > 0) {
            forwardSeekMs(player.getCurrentPosition(), player.getDuration(), stepMs)
        } else {
            -stepMs
        }
        if (offsetMs != 0) {
            // Save the position once on release, not on every tick
            player.seekRelative(offsetMs, savePosition = false)
            (NavigationManager.getInstance().activeController as? PlayerController)?.updatePlaybackProgress()
        }
        return offsetMs
    }

    private fun stopSeek() {
        if (seekDirection == 0) return
        seekDirection = 0
        seekHandler.removeCallbacks(seekTickRunnable)
        AudioPlayerManager.getInstance().savePlaybackPositionOnly(sync = true)
    }

    internal val isSeeking: Boolean
        get() = seekDirection != 0

    /**
     * Next/Previous key up. Stops a seek, and changes the track only after a short press. A long press counts
     * as a hold even if a controller (e.g. FM radio station seek) consumed the key repeats. Returns true if it skipped.
     */
    internal fun onSkipKeyUp(keyCode: Int, heldMs: Long): Boolean {
        // Also a release that came to the activity (the screen came on during the hold): a later press starts clean
        screenOffSkipKey = 0
        stopSeek()
        if (isSkipKeyHeld || heldMs >= SKIP_KEY_HOLD_MS) {
            return false
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            AudioSources.app.skipKey(true)
        } else {
            AudioSources.app.skipKey(false)
        }
        MainActivity.instance?.clickFeedback()
        return true
    }

    companion object {
        /**
         * A hold of Back (its first key repeat) opens Now Playing: when the router got the press ([backArmed]: not a
         * wake, the USB screen or a debounce), the setting is On, and the screen is not Now Playing itself or one
         * that has its own exit and state (Music Quiz pauses the music for its round, FM Radio is the source).
         */
        internal fun holdOpensNowPlaying(repeatCount: Int, backArmed: Boolean, enabled: Boolean, state: ScreenState): Boolean =
            repeatCount == 1 && backArmed && enabled && state != ScreenState.Player && state != ScreenState.MusicQuiz &&
                state != ScreenState.FmRadio && state != ScreenState.FmRadioMenu && state != ScreenState.FmRadioSaved

        // Android's default long-press / key-repeat timeout
        private const val SKIP_KEY_HOLD_MS = 500L

        // Hold-to-seek speeds up smoothly, like the iPod's fast-forward. Every 100 ms the position moves by the
        // seek speed times the time since the last tick. The speed starts at 4x and grows exponentially to 120x
        // over 7 s of seeking (it doubles about every 1.4 s): ~5 s of audio in the first second, ~14 s in two,
        // a song in ~7 s, then 2 minutes per second. If no key repeat comes for 1 s, the key-up was lost: stop.
        private const val SEEK_TICK_MS = 100L
        private const val SEEK_MIN_SPEED = 4.0
        private const val SEEK_MAX_SPEED = 120.0
        private const val SEEK_RAMP_MS = 7_000L
        private const val SEEK_REPEAT_TIMEOUT_MS = 1_000L
        private const val SEEK_KEY_UP_CAP_MS = 300_000L

        /**
         * True when a seek must stop. Screen on: no key repeat for 1 s (the key-up was lost). Screen off, no repeats
         * come: only a 5 min cap, in case the key-up never arrives.
         */
        internal fun seekTimedOut(nowMs: Long, lastRepeatMs: Long, seekStartMs: Long, waitForKeyUp: Boolean): Boolean =
            if (waitForKeyUp) nowMs - seekStartMs > SEEK_KEY_UP_CAP_MS else nowMs - lastRepeatMs > SEEK_REPEAT_TIMEOUT_MS

        // Forward seeks stop this far before the end, so seeking alone never skips to the next track
        private const val SEEK_END_MARGIN_MS = 1_000

        /**
         * True when a screen-off seek must stop before its key-up: forward at the end margin, back at the start, or
         * the track changed. Then a lost key-up changes one track at most. A forward seek with an unknown duration
         * (0) stops only when the track changes.
         */
        internal fun screenOffSeekEnds(direction: Int, positionMs: Int, durationMs: Int, trackChanged: Boolean): Boolean {
            if (trackChanged) return true
            return if (direction > 0) durationMs > 0 && positionMs >= durationMs - SEEK_END_MARGIN_MS else positionMs <= 0
        }

        /** Seek speed (x real time) after seeking for [seekingMs]. */
        internal fun seekSpeed(seekingMs: Long): Double {
            val ramp = seekingMs.coerceIn(0L, SEEK_RAMP_MS).toDouble() / SEEK_RAMP_MS
            return SEEK_MIN_SPEED * Math.pow(SEEK_MAX_SPEED / SEEK_MIN_SPEED, ramp)
        }

        /** Seek step in ms for a tick [elapsedMs] after the last one, after seeking for [seekingMs]. */
        internal fun seekStepMs(seekingMs: Long, elapsedMs: Long): Int {
            // Capped at a few ticks: a stale time (a forged event) must not overflow the Int or give a negative step
            return Math.round(seekSpeed(seekingMs) * elapsedMs.coerceIn(0L, SEEK_TICK_MS * 5)).toInt()
        }

        /** Forward seek offset of up to [stepMs], clamped to stop [SEEK_END_MARGIN_MS] before the end. */
        internal fun forwardSeekMs(positionMs: Int, durationMs: Int, stepMs: Int): Int {
            if (durationMs <= 0) return stepMs
            return (durationMs - SEEK_END_MARGIN_MS - positionMs).coerceIn(0, stepMs)
        }

        @Volatile
        private var instance: InputRouter? = null

        fun getInstance(): InputRouter {
            return instance ?: synchronized(this) {
                instance ?: InputRouter().also { instance = it }
            }
        }
    }
}
