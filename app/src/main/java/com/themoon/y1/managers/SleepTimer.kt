package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.themoon.y1.AppPrefs
import com.themoon.y1.MainActivity
import com.themoon.y1.Prefs
import com.themoon.y1.cast.CastManager
import com.themoon.y1.core.ApiCompat
import com.themoon.y1.views.Notice

/**
 * The sleep timer (Power screen → Sleep Timer) for everything that plays. Over its last [SleepTimerMath.FADE_MS]
 * the volume fades out; then music, video and radio pause ([PlaybackPause]) and, by Settings → Playback → After
 * Sleep Timer, the screen turns off or the player powers off.
 *
 * No wake lock while it runs: an exact [AlarmManager.ELAPSED_REALTIME_WAKEUP] alarm wakes the player at the start
 * of the fade, also when nothing plays and the CPU sleeps; a wake lock holds it awake only for the fade.
 * The fade also turns down a Play On speaker. At the end everything pauses at the faded volume, and the volume comes
 * back [RESTORE_MS] later (libVLC and a speaker pause a moment after the call), or at the next key.
 * The timer lives in memory: a restart ends it. Everything runs on the main thread.
 */
object SleepTimer {
    private const val TAG = "SleepTimer"
    private const val ACTION_FADE = "com.themoon.y1.SLEEP_TIMER_FADE"
    private const val EXTRA_TOKEN = "token"
    /** An alarm may come a little early: the fade starts if the alarm comes at most this much before its time. */
    private const val EARLY_MS = 2_000L
    private const val FADE_TICK_MS = 250L
    /** The fade's wake lock lasts this much past the fade (a slow power off or screen off). */
    private const val LOCK_EXTRA_MS = 15_000L
    /** After the end, the volume comes back this much later: after the Play On sync (1 s) and libVLC paused. */
    private const val RESTORE_MS = 3_000L

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    /** End time ([SystemClock.elapsedRealtime]); 0 = no timer. */
    private var endAt = 0L
    private var fading = false
    /** A new random number for each timer: the alarm brings it, a broadcast from another app cannot know it. */
    private var token = 0L
    /** The end paused everything and the volume is still at 0: [restoreGain] brings it back. */
    private var restorePending = false
    // The application context (start() takes it), not an activity: nothing to leak
    @SuppressLint("StaticFieldLeak")
    private var app: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun isActive(): Boolean = endAt > 0L

    /** Whole minutes left (rounded up), 0 when no timer runs. */
    fun minutesLeft(): Int = if (endAt > 0L) SleepTimerMath.minutesLeft(endAt, SystemClock.elapsedRealtime()).coerceAtLeast(1) else 0

    /** [listener] runs at a start, a cancel and the end, and each time [minutesLeft] changes (main thread). */
    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Starts the timer for [minutes] (a running one starts again with the new time). */
    fun start(context: Context, minutes: Int) {
        stop()
        val c = context.applicationContext
        app = c
        endAt = SystemClock.elapsedRealtime() + SleepTimerMath.clamp(minutes) * 60_000L
        token = java.security.SecureRandom().nextLong()
        register(c)
        setAlarm(c, endAt - SleepTimerMath.FADE_MS)
        scheduleTick()
        changed()
    }

    /** Stops the timer (and its fade: the volume comes back). */
    fun cancel() {
        if (!isActive()) return
        stop()
        changed()
    }

    /**
     * A key press: during the fade it stops the timer and the volume comes back (the listener can tell). Returns
     * true then, and the key does nothing else.
     */
    fun onUserKey(): Boolean {
        if (restorePending) {
            // After the end: the volume comes back now, and the key acts as normal (for example Play)
            handler.removeCallbacks(restoreGain)
            restoreGain.run()
            return false
        }
        if (!fading) return false
        Log.w(TAG, "Key during the fade: timer stopped")
        cancel()
        return true
    }

    private fun stop() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(fadeTick)
        app?.let { cancelAlarm(it) }
        unregister()
        handler.removeCallbacks(restoreGain)
        if (fading || restorePending) setFade(1f)
        fading = false
        restorePending = false
        endAt = 0L
        releaseLock()
    }

    private fun changed() {
        for (l in ArrayList(listeners)) {
            try { l() } catch (e: Exception) { Log.w(TAG, "listener: ${e.message}") }
        }
    }

    /** The minute tick of the UI; at the start of the fade it starts the fade (when the alarm did not come yet). */
    private val tick = object : Runnable {
        override fun run() {
            if (!isActive()) return
            if (endAt - SystemClock.elapsedRealtime() <= SleepTimerMath.FADE_MS) {
                startFade()
                changed()
                return
            }
            changed()
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val now = SystemClock.elapsedRealtime()
        val toFade = endAt - SleepTimerMath.FADE_MS - now
        val next = SleepTimerMath.msToNextMinute(endAt, now)
        handler.postDelayed(tick, if (toFade > 0) minOf(next, toFade) else 0L)
    }

    private fun startFade() {
        if (fading || !isActive()) return
        fading = true
        handler.removeCallbacks(tick)
        app?.let { acquireLock(it) }
        Log.w(TAG, "Fade out")
        handler.post(fadeTick)
    }

    private val fadeTick = object : Runnable {
        override fun run() {
            if (!fading) return
            val left = endAt - SystemClock.elapsedRealtime()
            setFade(SleepTimerMath.fadeFactor(left))
            if (left <= 0) finish() else handler.postDelayed(this, minOf(FADE_TICK_MS, left))
        }
    }

    /** The fade-out of the app's players and of a Play On speaker (1 = none). */
    private fun setFade(factor: Float) {
        VirtualVolumeManager.getInstance().setFadeFactor(factor)
        CastManager.setFade(factor)
    }

    private val restoreGain = Runnable {
        restorePending = false
        setFade(1f)
    }

    /**
     * The end: everything pauses at the faded volume (also the Music Quiz clip and a game), the volume comes back
     * [RESTORE_MS] later for the next play, then the end action.
     */
    private fun finish() {
        val c = app ?: return
        // The wake lock stays until the end action is done
        val lock = wakeLock.also { wakeLock = null }
        // Not faded any more for stop(), so that it does not bring the volume back before the pause
        fading = false
        stop()
        PlaybackPause.all()
        MainActivity.instance?.pauseOwnSoundsForSleepTimer()
        restorePending = true
        handler.postDelayed(restoreGain, RESTORE_MS)
        changed()
        val release = { try { lock?.takeIf { it.isHeld }?.release() } catch (_: RuntimeException) {} }
        if (Prefs.SLEEP_TIMER_END.get(AppPrefs.get(c)) == Prefs.SLEEP_END_POWER_OFF) {
            Log.w(TAG, "End: power off")
            PowerMenu.run(c, PowerMenu.Action.POWER_OFF) {
                release()
                MainActivity.instance?.let { Notice.error(it, it.t("System security prevents powering off directly from the app.")) }
            }
        } else {
            Log.w(TAG, "End: pause, screen off")
            MainActivity.instance?.turnOffScreen()
            release()
        }
    }

    // The broadcast of the alarm: only this app gets it (setPackage). Android 4.2 and 4.4 have no receiver flags, so
    // another app can send the action too: the fade starts only with this timer's token and at its time.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun register(c: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            // Main thread. The fade's wake lock comes here: after onReceive the CPU may sleep again
            override fun onReceive(context: Context, intent: Intent) {
                if (!isActive() || intent.getLongExtra(EXTRA_TOKEN, 0L) != token) return
                if (SystemClock.elapsedRealtime() < endAt - SleepTimerMath.FADE_MS - EARLY_MS) return
                startFade()
            }
        }
        c.registerReceiver(r, IntentFilter(ACTION_FADE))
        receiver = r
    }

    private fun unregister() {
        val r = receiver ?: return
        receiver = null
        try { app?.unregisterReceiver(r) } catch (_: Exception) {}
    }

    private fun alarmIntent(c: Context): PendingIntent {
        val intent = Intent(ACTION_FADE).setPackage(c.packageName).putExtra(EXTRA_TOKEN, token)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(c, 0, intent, flags)
    }

    private fun setAlarm(c: Context, atElapsed: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        ApiCompat.setExactAlarm(am, AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, alarmIntent(c))
    }

    private fun cancelAlarm(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(alarmIntent(c))
    }

    private fun acquireLock(c: Context) {
        releaseLock()
        val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "classy:sleeptimer").apply {
            setReferenceCounted(false)
            acquire(SleepTimerMath.FADE_MS + LOCK_EXTRA_MS)
        }
    }

    private fun releaseLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: RuntimeException) {}
        wakeLock = null
    }
}
