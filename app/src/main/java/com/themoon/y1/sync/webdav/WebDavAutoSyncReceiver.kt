package com.themoon.y1.sync.webdav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * Starts a WebDAV sync when Auto Sync is on, Wi-Fi is up and the device is charging. It is registered by
 * [ClassyApplication] in the running process, not in the manifest: on the Y2 a manifest receiver for a connectivity
 * change starts a second process of the app, and that one stops the running launcher as an old process.
 * shortcut: no sync while the app process is not running, when that matters use a JobScheduler (API 21+, so not on the Y2).
 */
class WebDavAutoSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        evaluate(context.applicationContext)
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /** The one check that waits for the end of the hour (a newer one replaces it). */
        private val recheck = Runnable { app?.let { evaluate(it) } }

        @Volatile
        private var app: Context? = null

        fun register(app: Context) {
            val filter = IntentFilter().apply {
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(Intent.ACTION_POWER_CONNECTED)
            }
            app.registerReceiver(WebDavAutoSyncReceiver(), filter)
        }

        /** Starts an Auto Sync if the gate says so. Refused only by the hour: checks again when the hour ends. */
        fun evaluate(app: Context) {
            this.app = app
            main.removeCallbacks(recheck)
            val provider = WebDavSyncProvider.getInstance(app)
            if (!provider.autoSync) return
            val net = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetworkInfo
            val wifi = net != null && net.isConnected && net.type == ConnectivityManager.TYPE_WIFI
            // A sticky broadcast is read with a null receiver.
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val sync = WebDavSync.getInstance(app)
            val now = System.currentTimeMillis()
            val last = AutoSyncGate.lastCounted(now, provider.lastSync, provider.lastAutoAttempt)
            if (!AutoSyncGate.shouldRun(true, provider.isConfigured(), wifi, charging, sync.isRunning, now, last)) {
                // Still on Wi-Fi and charging, but in the hour after the last sync: no broadcast comes when the hour
                // ends, so check again then (only while the app process runs, like the receiver)
                if (provider.isConfigured() && wifi && charging && !sync.isRunning) {
                    main.postDelayed(recheck, (last + AutoSyncGate.MIN_INTERVAL_MS - now).coerceIn(1_000L, AutoSyncGate.MIN_INTERVAL_MS))
                }
                return
            }
            provider.lastAutoAttempt = now
            sync.start(object : WebDavSync.Listener {
                override fun onProgress(text: String, done: Long, total: Long) {}
                override fun onDone(changed: Boolean) {
                    if (changed) app.sendBroadcast(Intent(WebDavSync.ACTION_SYNCED).setPackage(app.packageName))
                }
                override fun onError(e: Throwable, changed: Boolean) {
                    if (changed) app.sendBroadcast(Intent(WebDavSync.ACTION_SYNCED).setPackage(app.packageName))
                }
            })
        }
    }
}
