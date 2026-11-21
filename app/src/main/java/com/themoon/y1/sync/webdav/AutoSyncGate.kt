package com.themoon.y1.sync.webdav

object AutoSyncGate {
    const val MIN_INTERVAL_MS = 60 * 60 * 1000L

    fun shouldRun(enabled: Boolean, configured: Boolean, wifi: Boolean, charging: Boolean, running: Boolean, now: Long, lastSync: Long): Boolean =
        enabled && configured && wifi && charging && !running && (now - lastSync >= MIN_INTERVAL_MS || lastSync > now) // lastSync > now: the clock was set back
}
