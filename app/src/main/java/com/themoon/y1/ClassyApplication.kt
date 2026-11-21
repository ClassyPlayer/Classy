package com.themoon.y1

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.multidex.MultiDexApplication
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

/**
 * Application class for Classy.
 * Ensures modern TLS/SSL security providers (Conscrypt) are initialized on startup
 * before any networking calls or background services execute.
 */
class ClassyApplication : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        installConscrypt()
        com.themoon.y1.battery.BatteryGuard.start(this)
        com.themoon.y1.battery.BatteryHistory.start(this)
        stopStaleProcesses()
    }

    /**
     * Stops older processes of this app. After `adb install -r` of the Home app, Android 4.4 can keep the old
     * process alive next to the new one (seen on the Y2, 2026-10-26). The old process still gets the screen-on
     * broadcast and starts its own backlight timer, which no key resets: the screen then turned off 60 s after
     * each wake, also during use. The app has one process, so any other process with our name and user is old.
     *
     * After the stop, this process starts the app again ([restartClean]): Android 4.4 removes a dead process from its
     * list by the process name, so the death of the old process also removes this one. A broadcast for the app (for
     * example a media button with the screen off) then starts a new copy, which stops this one and the music.
     */
    private fun stopStaleProcesses() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val running = am.runningAppProcesses ?: return
            val stale = staleProcessIds(
                Process.myPid(), Process.myUid(), packageName,
                running.map { RunningProcess(it.pid, it.uid, it.processName) }
            )
            if (stale.isEmpty()) return
            for (pid in stale) {
                Log.w(TAG, "Stopping an old process of this app: pid $pid")
                Process.killProcess(pid)
            }
            restartClean(stale)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not check for old processes: ${t.message}")
        }
    }

    /** Starts the app again in a new process, which Android knows. At most once in [RESTART_GUARD_MS] (no loop). */
    private fun restartClean(stale: List<Int>) {
        val prefs = getSharedPreferences(RESTART_PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        if (!mayRestart(prefs.getLong(RESTART_KEY, 0L), now)) {
            Log.w(TAG, "Not starting again: the last start for an old process was less than ${RESTART_GUARD_MS / 1000} s ago")
            return
        }
        // The old processes must end first, else the new start finds them again
        val until = SystemClock.uptimeMillis() + 1000
        while (stale.any { File("/proc/$it").exists() } && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
        // commit, not apply: this process ends below
        prefs.edit().putLong(RESTART_KEY, now).commit()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) {
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val alarms = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            // Read the clock again after the wait: the new start must come after this process ends
            alarms?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 500, PendingIntent.getActivity(this, 0, launch, flags))
        }
        Log.w(TAG, "Starting the app again, so that Android knows its process")
        Process.killProcess(Process.myPid())
    }

    data class RunningProcess(val pid: Int, val uid: Int, val name: String?)

    companion object {
        private const val TAG = "ClassyApplication"
        private const val RESTART_PREFS = "stale_process"
        private const val RESTART_KEY = "restart_at"

        /** At most one new start in this time, also when an old process comes back at once. */
        const val RESTART_GUARD_MS = 30_000L

        /** [lastMs] and [nowMs]: elapsed time since boot. A time after now is from before a reboot. */
        fun mayRestart(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || lastMs > nowMs || nowMs - lastMs >= RESTART_GUARD_MS

        /** The application context, for code with no activity (for example the assets of the podcast client). */
        @Volatile
        var appContext: Context? = null
            private set

        /** The other processes with our process name and user: older copies of this app. */
        fun staleProcessIds(myPid: Int, myUid: Int, processName: String, running: List<RunningProcess>): List<Int> =
            running.filter { it.pid != myPid && it.uid == myUid && it.name == processName }.map { it.pid }

        @JvmStatic
        fun installConscrypt() {
            try {
                if (Security.getProvider("Conscrypt") == null) {
                    Security.insertProviderAt(Conscrypt.newProvider(), 1)
                    Log.i(TAG, "Conscrypt provider installed successfully")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to install Conscrypt security provider: ${t.message}")
            }
        }
    }
}

/** Backward compatibility alias for tests and reflection. */
typealias InniClassicApplication = ClassyApplication
