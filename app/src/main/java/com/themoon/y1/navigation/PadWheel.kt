package com.themoon.y1.navigation

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.themoon.y1.DeviceModel
import com.themoon.y1.MainActivity
import com.themoon.y1.Prefs
import com.themoon.y1.games.WheelSensor
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max

/**
 * The Y2 wheel from its touch pads (the controller's test mode, /proc/tpd_testAdcCh, as the games use it) instead of
 * its 9 step keys a turn. Settings → Controls → Wheel Mode: Classic (the step keys), Smooth (10 rows a turn, the
 * default) or Quick (15 rows a turn). A turn moves the same rows in all menus and lists, with no speed-up: the letter
 * jump is the fast way through a long list. A value ([WheelKind.VALUE]: volume, sliders, Cover Flow, pages) and the
 * letters ([WheelKind.LETTERS]) get 10 steps a turn.
 *
 * A poll thread reads the mask (one open file, a seek for each read: every [IDLE_POLL_MS] with no thumb,
 * [TOUCH_POLL_MS] with a thumb), decodes it to a position (30 a turn, [PadDecoder]), follows it with a dead band of
 * one position, and posts whole positions to the main thread. There a [RowCounter] makes rows (the same positions for
 * each row, counted again at each touch and reversal), and each row becomes a wheel key (DPAD_LEFT / DPAD_RIGHT, with
 * [SCAN_CODE]) for the screen. While a row is sent, [stepSpeed] gives the wheel speed to the letter jump and
 * [stepMayWrap] stops a turn at the list end. The screen hears each touch and lift ([com.themoon.y1.controllers.ScreenController.onWheelTouch]).
 *
 * - The test mode repeats the last real step key at each mask change: [filterKey] drops the wheel's own step keys. A
 *   step key with no thumb on the pads means that the controller went back to steps (after a screen-on): arm it again
 *   (at most every [REARM_MIN_MS]), and the key acts as a normal step.
 * - A ring button touches the pads up to ~100 ms before its key: no move in the first [HOLD_MS] of a touch, nor before
 *   it moved [SLOP_POSITIONS]. A button key drops the moves until [AFTER_BUTTON_MS] after its release; then the thumb
 *   that stays on the wheel starts a new touch.
 * - The moves of the last [LIFT_GUARD_MS] before a lift are dropped (a lifting thumb rolls). One read with no pad in a
 *   fast slide is not a lift: its moves stay.
 * - Off with the screen off, in the app's own screen-off, outside the app, and during a game (the game owns the test mode).
 */
object PadWheel {
    /** Settings → Controls → Wheel Mode; the index is the pref value. */
    val MODE_LABELS = arrayOf("Classic", "Smooth", "Quick")

    /**
     * Rows a turn for each mode (Classic: the 9 step keys). The pads give 30 positions a turn, so only a rate that
     * divides 30 gives the same turn for each row: with 20, two rows came close together at each third position.
     */
    private val PER_TURN = intArrayOf(9, 10, 15)
    const val DEFAULT_MODE = 1

    /** Positions for each step of a value and of the letters: 10 a turn. */
    private const val VALUE_POSITIONS = 3

    /** No thumb: a short spike of the wheel's own key must be seen (re-arm check); the chip updates every 8–10 ms. */
    private const val IDLE_POLL_MS = 8L
    private const val TOUCH_POLL_MS = 4L
    private const val TICK_MS = 250L
    private const val HOLD_MS = 120L
    /** Travel (positions) before the first move of a touch. */
    private const val SLOP_POSITIONS = 2
    /** A position change of more than this between two reads is another finger, after a read with no pad. */
    private const val JUMP_POSITIONS = 4
    /** The fastest thumb (turns a second): after a longer read gap (a GC pause) the jump limit grows with the gap. */
    private const val MAX_TURNS_PER_SECOND = 4.0
    private const val LIFT_MS = 60L
    /**
     * The letter jump uses the speed over this time, from the times of the position changes ([PadSpeed]): over a
     * fixed window one position more or less changed the speed by 8–16 %.
     */
    private const val SPEED_WINDOW_MS = 160L
    private const val SPEED_MIN_SPAN_MS = 100L
    /**
     * A wheel step key is a real step (the controller went back to steps) when no thumb was on the pads in the
     * [REAL_STEP_MS] before it, checked [CHECK_MS] after it. The test mode also repeats the key when the thumb lifts:
     * that key comes with no thumb after it, but with a thumb just before it.
     */
    private const val REAL_STEP_MS = 150L
    private const val CHECK_MS = 60L
    /** At most one re-arm in this time: a re-arm with a thumb on the wheel can make the chip lose the thumb. */
    private const val REARM_MIN_MS = 500L
    /** The arm at a screen-on can come before the controller is ready: arm again after this time. */
    private const val SCREEN_ON_REARM_MS = 1000L
    /** The moves wait this long; the moves in the last [LIFT_GUARD_MS] before a lift are dropped. */
    private const val LIFT_GUARD_MS = 40L
    /** The moves stay dropped this long after a ring button is released. */
    private const val AFTER_BUTTON_MS = 80L
    /** A button press drops the moves at most this long, also when its release does not come. */
    private const val BUTTON_MAX_MS = 3000L
    /** With Wheel Loop Scroll On, a turn stops at the list end; it wraps on a new touch or after this time at the end. */
    private const val WRAP_PAUSE_MS = 300L
    /** At most this many rows from one batch (after a long main-thread stall). */
    private const val MAX_BATCH_ROWS = 30
    /** Time limit of the screen lock of [keepScreenOn]: longer than its renewal (1 s). */
    private const val SCREEN_LOCK_MS = 2000L
    const val SCAN_CODE = 0x7f01

    private val TEST_MODE = File("/proc/tpd_testAdcCh")
    private val main = Handler(Looper.getMainLooper())

    private var activity: MainActivity? = null

    @Volatile
    private var thread: Thread? = null
    /** Uptime until which the moves are dropped (a ring button press); the touch starts again after it. */
    @Volatile
    private var cancelUntilMs = 0L
    private var cancelKey = 0
    @Volatile
    private var lastArmMs = 0L
    @Volatile
    private var lastThumbMs = 0L
    /** Turns a second of the touch (clockwise positive), for the letter jump; 0 with no thumb. */
    @Volatile
    private var speed = 0.0

    // The moves from the poll thread, not yet taken by the main thread
    private val moveLock = Any()
    private var queuedPositions = 0
    private var queuedTouch = 0
    private var movePosted = false
    private val takeMoves = Runnable {
        val positions: Int
        val touch: Int
        synchronized(moveLock) {
            positions = queuedPositions
            touch = queuedTouch
            queuedPositions = 0
            movePosted = false
        }
        onMove(positions, touch)
    }

    // The touch and lift events for the screen (one post each, no allocation)
    @Volatile
    private var touchEventMs = 0L
    private val touchDown = Runnable { activity?.getActiveScreenController()?.onWheelTouch(true, touchEventMs) }
    @Volatile
    private var liftEventMs = 0L
    private val lift = Runnable { activity?.getActiveScreenController()?.onWheelTouch(false, liftEventMs) }

    // Main thread
    private val rows = RowCounter()
    private var rowsTouch = -1
    private var lastKind: WheelKind? = null
    private var lastRowMs = 0L
    private var touchRows = 0
    private var sending = false
    private var sendingMayWrap = true

    /**
     * The wheel speed (turns a second, clockwise positive) while a pad-wheel row is sent to the screen; null for all
     * other keys. The letter jump starts from it ([LetterJump.Mode.onStep]).
     */
    val stepSpeed: Double? get() = if (sending) speed else null

    /**
     * False while a row is sent that would wrap round the list end in the middle of a turn: with Wheel Loop Scroll On a
     * turn stops at the end, and wraps on a new touch or after a pause ([RowFocus.isLoopOn]).
     */
    val stepMayWrap: Boolean get() = !sending || sendingMayWrap

    /** True on a Y2 with the pad test mode: Settings shows Wheel Mode. */
    fun isSupported(): Boolean = DeviceModel.isY2(Build.MODEL) && TEST_MODE.exists()

    fun attach(activity: MainActivity) {
        this.activity = activity
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** The activity closes (only the attached one: a new one can be attached already). */
    fun detach(activity: MainActivity) {
        if (this.activity !== activity) return
        main.removeCallbacks(tick)
        stop(write = true)
        this.activity = null
    }

    /** Screen on: the controller is back to steps; arm it again now and a little later (the tick also starts the poll). */
    fun onScreenOn() {
        if (thread != null) arm()
        main.removeCallbacks(rearmLater)
        main.postDelayed(rearmLater, SCREEN_ON_REARM_MS)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private val rearmLater = Runnable { if (thread != null) arm() }

    private fun arm() {
        lastArmMs = SystemClock.uptimeMillis()
        setTestMode(false)
        setTestMode(true)
    }

    private val tick = object : Runnable {
        override fun run() {
            val a = activity ?: return
            val run = shouldRun(a)
            if (run && thread == null) start() else if (!run && thread != null) stop(write = !isGame(a))
            if (isScreenOn(a)) main.postDelayed(this, TICK_MS)
        }
    }

    private fun mode(a: MainActivity): Int = Prefs.WHEEL_MODE.get(a.prefs).let { if (it in PER_TURN.indices) it else DEFAULT_MODE }

    private fun isGame(a: MainActivity): Boolean =
        a.getActiveScreenController() is com.themoon.y1.controllers.games.GamePlayerController

    private fun isScreenOn(a: MainActivity): Boolean {
        val pm = a.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn
    }

    private fun shouldRun(a: MainActivity): Boolean =
        mode(a) != 0 && isSupported() && a.hasWindowFocus() && isScreenOn(a) && !a.isFakeScreenOff && !isGame(a)

    private fun start() {
        lastArmMs = SystemClock.uptimeMillis()
        if (!setTestMode(false) || !setTestMode(true)) return
        thread = Thread({ poll() }, "PadWheel").also { it.start() }
    }

    private fun stop(write: Boolean) {
        val t = thread ?: return
        thread = null
        t.interrupt()
        try {
            t.join(100)
        } catch (_: InterruptedException) {}
        if (write) setTestMode(false)
        speed = 0.0
        rows.reset()
    }

    /**
     * A key from MainActivity.dispatchKeyEvent, before all else. True = drop it: a step key of the wheel (the repeat
     * of the test mode). A step key with no thumb is a real step: arm again, and it acts as a normal step.
     */
    fun filterKey(event: KeyEvent): Boolean {
        if (thread == null || event.scanCode == SCAN_CODE) return false
        if (event.device?.name != "mtk-tpd") return false
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val delta = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                val keyMs = event.eventTime
                // The repeat comes up to ~20 ms before the poll reads its mask: decide a little later
                main.postDelayed({
                    if (thread != null && lastThumbMs < keyMs - REAL_STEP_MS) {
                        if (SystemClock.uptimeMillis() - lastArmMs >= REARM_MIN_MS) arm()
                        sendStep(delta)
                    }
                }, CHECK_MS)
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) return false
        // A ring button (not the centre): its press touched the pads; no moves until just after its release
        val now = SystemClock.uptimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN) {
            cancelKey = code
            cancelUntilMs = now + BUTTON_MAX_MS
            dropMoves()
        } else if (event.action == KeyEvent.ACTION_UP && code == cancelKey) {
            cancelUntilMs = now + AFTER_BUTTON_MS
            dropMoves()
        }
        return false
    }

    // --- poll thread ---

    private fun poll() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        PadDecoder.prepare()
        val file = try {
            RandomAccessFile(TEST_MODE, "r")
        } catch (_: Exception) {
            null
        }
        val reader = PadDecoder.Reader()
        val padSpeed = PadSpeed()
        val buffer = ByteArray(32)
        var touching = false
        var touch = 0 // touch id: the main thread counts rows again for each touch
        var raw = PadDecoder.NONE // the last position read
        var rawU = 0 // raw, unwrapped
        var followed = 0 // follows rawU one position behind (dead band)
        var lastSeenMs = 0L
        var lastReadMs = 0L
        var afterZero = false
        var touchMs = 0L
        var travel = 0 // since the touch (or the button restart)
        var held = 0 // moves not sent yet (hold time, slop)
        var released = false
        val pendingMs = LongArray(64)
        val pendingMove = IntArray(64)
        var pending = 0
        try {
            while (thread === Thread.currentThread()) {
                val mask = try {
                    file?.seek(0)
                    val n = file?.read(buffer) ?: -1
                    if (n > 0) WheelSensor.parseMask(buffer, n) else 0
                } catch (_: Exception) {
                    0
                }
                val now = SystemClock.uptimeMillis()
                val pos = reader.read(mask, if (touching) raw else PadDecoder.NONE)
                if (pos == PadDecoder.NONE) {
                    if (touching) {
                        if (now - lastSeenMs >= LIFT_MS) {
                            // The lift: drop the moves of the thumb's last LIFT_GUARD_MS (a lifting thumb rolls)
                            pending = 0
                            touching = false
                            speed = 0.0
                            liftEventMs = now
                            main.post(lift)
                        }
                        afterZero = true // one read with no pad in a fast slide: the moves stay
                    }
                } else {
                    lastThumbMs = now
                    if (!touching) {
                        touching = true
                        touch++
                        raw = pos
                        rawU = 0
                        followed = 0
                        touchMs = now
                        travel = 0
                        held = 0
                        released = false
                        pending = 0
                        padSpeed.reset()
                        touchEventMs = now
                        main.post(touchDown)
                    } else {
                        val gap = now - lastReadMs
                        val d = PadDecoder.shortest(raw, pos)
                        // A longer read gap (a GC pause also stops this thread) allows a longer real move
                        val limit = if (afterZero) JUMP_POSITIONS
                        else max(JUMP_POSITIONS, (MAX_TURNS_PER_SECOND * PadDecoder.POSITIONS * gap / 1000.0).toInt())
                        raw = pos
                        if (abs(d) > limit) {
                            // Another finger (or the thumb came back somewhere else): follow it from here, with no move
                            touch++
                            rawU = 0
                            followed = 0
                            travel = 0
                            held = 0
                            pending = 0
                            padSpeed.reset()
                        } else if (d != 0) {
                            rawU += d
                            val diff = rawU - followed
                            if (abs(diff) >= 2) {
                                // Follow one position behind: a thumb that rocks one position moves nothing
                                val step = diff - Integer.signum(diff)
                                followed += step
                                travel += step
                                held += step
                                padSpeed.change(now, followed)
                            }
                        }
                    }
                    lastSeenMs = now
                    afterZero = false
                    speed = padSpeed.turnsPerSecond(now, SPEED_WINDOW_MS, SPEED_MIN_SPAN_MS)
                    if (now < cancelUntilMs) {
                        // A button press: the thumb that stays starts a new touch after it (hold time and slop again)
                        touchMs = now
                        travel = 0
                        held = 0
                        released = false
                        pending = 0
                        touch++
                    } else {
                        if (!released && now - touchMs >= HOLD_MS && abs(travel) >= SLOP_POSITIONS) released = true
                        if (released && held != 0) {
                            if (pending == pendingMs.size) pending-- // cannot happen at 4 ms polls; keep the newest
                            pendingMs[pending] = now
                            pendingMove[pending] = held
                            pending++
                            held = 0
                        }
                    }
                }
                // The moves older than LIFT_GUARD_MS go; not during a read with no pad (it can be the start of a lift)
                if (touching && !afterZero && pending > 0) {
                    var move = 0
                    var sent = 0
                    while (sent < pending && now - pendingMs[sent] >= LIFT_GUARD_MS) move += pendingMove[sent++]
                    if (sent > 0) {
                        System.arraycopy(pendingMs, sent, pendingMs, 0, pending - sent)
                        System.arraycopy(pendingMove, sent, pendingMove, 0, pending - sent)
                        pending -= sent
                        queueMove(move, touch)
                    }
                }
                lastReadMs = now
                try {
                    Thread.sleep(if (touching) TOUCH_POLL_MS else IDLE_POLL_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        } finally {
            try {
                file?.close()
            } catch (_: Exception) {}
        }
    }

    private fun queueMove(positions: Int, touch: Int) {
        if (positions == 0) return
        synchronized(moveLock) {
            if (queuedTouch != touch) {
                queuedPositions = 0
                queuedTouch = touch
            }
            queuedPositions += positions
            if (!movePosted) {
                movePosted = true
                main.post(takeMoves)
            }
        }
    }

    // --- main thread ---

    private fun dropMoves() {
        synchronized(moveLock) { queuedPositions = 0 }
        rows.reset()
    }

    private fun onMove(positions: Int, touch: Int) {
        val a = activity ?: return
        if (thread == null || SystemClock.uptimeMillis() < cancelUntilMs) return
        val kind = a.getActiveScreenController()?.wheelKind() ?: WheelKind.LIST
        if (touch != rowsTouch) {
            rows.reset()
            rowsTouch = touch
            touchRows = 0
        }
        if (kind != lastKind) {
            rows.reset()
            lastKind = kind
        }
        val k = if (kind == WheelKind.LIST) PadDecoder.POSITIONS / PER_TURN[mode(a)] else VALUE_POSITIONS
        val n = rows.add(positions, k).coerceIn(-MAX_BATCH_ROWS, MAX_BATCH_ROWS)
        val delta = Integer.signum(n)
        repeat(abs(n)) {
            val now = SystemClock.uptimeMillis()
            sending = true
            sendingMayWrap = touchRows == 0 || now - lastRowMs >= WRAP_PAUSE_MS
            try {
                sendStep(delta)
            } finally {
                sending = false
            }
            lastRowMs = now
            touchRows++
            // The letter jump started (or ended) at this row: the rest was counted at the other rate
            if (a.getActiveScreenController()?.wheelKind() != kind) {
                rows.reset()
                return
            }
        }
    }

    /** Sends one wheel step to the screen, as a key of the wheel. */
    private fun sendStep(delta: Int) {
        val a = activity ?: return
        val now = SystemClock.uptimeMillis()
        keepScreenOn(a, now)
        val code = if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, SCAN_CODE, 0,
            InputDevice.SOURCE_KEYBOARD)
        a.dispatchKeyEvent(down)
        a.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
    }

    private var screenLock: PowerManager.WakeLock? = null
    private var lastScreenLockMs = 0L

    /**
     * The steps do not go through Android's input, so Android does not see user activity, and the screen dims and goes
     * off while the thumb scrolls. A screen-bright wake lock with a time limit, renewed at most once a second, keeps the
     * screen bright (and makes a dimmed screen bright again). Its release (ON_AFTER_RELEASE) starts the screen timeout
     * again, as after a key. Not a dim lock released at once: Android 4.4 then keeps a dimmed screen dim.
     */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK: the only screen lock without a window flag (API 19)
    private fun keepScreenOn(a: MainActivity, now: Long) {
        if (now - lastScreenLockMs < 1000) return
        lastScreenLockMs = now
        try {
            val lock = screenLock ?: (a.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "Y1:PadWheel")
                .also {
                    it.setReferenceCounted(false)
                    screenLock = it
                }
            lock.acquire(SCREEN_LOCK_MS)
        } catch (_: Exception) {
        }
    }

    private fun setTestMode(on: Boolean): Boolean = try {
        FileOutputStream(TEST_MODE).use { it.write(if (on) byteArrayOf('1'.code.toByte()) else byteArrayOf('0'.code.toByte())) }
        true
    } catch (_: Exception) {
        false
    }
}
