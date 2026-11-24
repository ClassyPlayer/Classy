package com.themoon.y1.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** The locks of a sync run (Jellyfin Sync, WebDAV Sync): the CPU and Wi-Fi stay on while the screen is off. */
object SyncLocks {

    /**
     * Runs [work] with a partial wake lock and a high-performance Wi-Fi lock named [tag], and releases both after it,
     * also after an error. [maxMs] > 0: the wake lock ends by itself after that time, so a run that hangs does not keep
     * the player awake for ever.
     */
    fun <T> hold(context: Context?, tag: String, maxMs: Long = 0L, work: () -> T): T {
        val app = context?.applicationContext // WifiManager of an activity leaks it on Android < 7
        val wake = (app?.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        val wifi = (app?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
        try {
            if (maxMs > 0) wake?.acquire(maxMs) else wake?.acquire()
            wifi?.acquire()
            return work()
        } finally {
            try { if (wifi?.isHeld == true) wifi.release() } catch (_: Exception) {}
            try { if (wake?.isHeld == true) wake.release() } catch (_: Exception) {}
        }
    }
}
