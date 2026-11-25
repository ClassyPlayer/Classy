package com.themoon.y1.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Audiobook sleep timer: after the chosen minutes (at most [MAX_MINUTES]) it pauses the book, saves the place and
 * powers the device off (as the Power Off row of the Power screen does). A partial wake lock keeps the CPU awake
 * until then, so the timer does not stop in deep sleep.
 */
object SleepTimer {
    const val MAX_MINUTES = 120
    /** The choices of the menu, in minutes. */
    val CHOICES = intArrayOf(5, 10, 15, 30, 45, 60, 90, MAX_MINUTES)

    private val handler = Handler(Looper.getMainLooper())
    private var endAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var fire: Runnable? = null

    /** The minutes in [minutes], never more than [MAX_MINUTES] and at least 1. */
    fun clamp(minutes: Int): Int = minutes.coerceIn(1, MAX_MINUTES)

    @Synchronized
    fun isActive(): Boolean = fire != null

    /** Whole minutes left (rounded up), 0 when no timer runs. */
    @Synchronized
    fun minutesLeft(): Int {
        if (fire == null) return 0
        val left = endAt - SystemClock.elapsedRealtime()
        return if (left <= 0) 1 else ((left + 59_999L) / 60_000L).toInt()
    }

    @Synchronized
    fun start(context: Context, minutes: Int) {
        cancel()
        val ms = clamp(minutes) * 60_000L
        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "classy:sleeptimer").apply {
            setReferenceCounted(false)
            acquire(ms + 60_000L)
        }
        endAt = SystemClock.elapsedRealtime() + ms
        val task = Runnable { expire(app) }
        fire = task
        handler.postDelayed(task, ms)
    }

    @Synchronized
    fun cancel() {
        fire?.let { handler.removeCallbacks(it) }
        fire = null
        endAt = 0L
        releaseLock()
    }

    private fun releaseLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: RuntimeException) {}
        wakeLock = null
    }

    private fun expire(context: Context) {
        // This timer's lock leaves the field: a timer started before a failed power off keeps its own lock
        val lock = synchronized(this) {
            fire = null
            endAt = 0L
            wakeLock.also { wakeLock = null }
        }
        // Pauses the book, saves the place, powers off. The wake lock stays until the power off (or a failure) is done.
        PowerMenu.run(context, PowerMenu.Action.POWER_OFF) {
            try { lock?.takeIf { it.isHeld }?.release() } catch (_: RuntimeException) {}
        }
    }
}
