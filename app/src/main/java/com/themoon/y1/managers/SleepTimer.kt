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
import com.themoon.y1.core.ApiCompat
import com.themoon.y1.views.Notice

/**
 * The sleep timer (Power screen → Sleep Timer) for everything that plays. Over its last [SleepTimerMath.FADE_MS]
 * the volume fades out; then music, video and radio pause ([PlaybackPause]) and, by Settings → Playback → After
 * Sleep Timer, the screen turns off or the player powers off.
 *
 * No wake lock while it runs: an exact [AlarmManager.ELAPSED_REALTIME_WAKEUP] alarm wakes the player at the start
 * of the fade, also when nothing plays and the CPU sleeps; a wake lock holds it awake only for the fade.
 * The timer lives in memory: a restart ends it. Everything runs on the main thread.
 */
object SleepTimer {
    private const val TAG = "SleepTimer"
    private const val ACTION_FADE = "com.themoon.y1.SLEEP_TIMER_FADE"
    private const val FADE_TICK_MS = 250L
    /** The fade's wake lock lasts this much past the fade (a slow power off or screen off). */
    private const val LOCK_EXTRA_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    /** End time ([SystemClock.elapsedRealtime]); 0 = no timer. */
    private var endAt = 0L
    private var fading = false
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
        if (fading) VirtualVolumeManager.getInstance().setFadeFactor(1f)
        fading = false
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
            VirtualVolumeManager.getInstance().setFadeFactor(SleepTimerMath.fadeFactor(left))
            if (left <= 0) finish() else handler.postDelayed(this, minOf(FADE_TICK_MS, left))
        }
    }

    /** The end: everything pauses at the faded volume, the volume comes back for the next play, then the end action. */
    private fun finish() {
        val c = app ?: return
        // The wake lock stays until the end action is done
        val lock = wakeLock.also { wakeLock = null }
        stop()
        PlaybackPause.all()
        VirtualVolumeManager.getInstance().setFadeFactor(1f)
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

    // The broadcast of the alarm: only this app gets it (setPackage), and a broadcast from elsewhere can only start
    // the fade of a timer that runs. The players run API 17 and 19, which have no receiver flags.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun register(c: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            // Main thread. The fade's wake lock comes here: after onReceive the CPU may sleep again
            override fun onReceive(context: Context, intent: Intent) {
                if (isActive()) startFade()
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
        val intent = Intent(ACTION_FADE).setPackage(c.packageName)
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
