package com.themoon.y1.sync.webdav

object AutoSyncGate {
    /**
     * The last time that counts for the hour: the latest of [times] that is not in the future. A time after [now] (the
     * clock was set back) is ignored, so the first try after that is allowed and its own time then throttles the next.
     */
    fun lastCounted(now: Long, vararg times: Long): Long = times.filter { it <= now }.maxOrNull() ?: 0L

    const val MIN_INTERVAL_MS = 60 * 60 * 1000L

    fun shouldRun(enabled: Boolean, configured: Boolean, wifi: Boolean, charging: Boolean, running: Boolean, now: Long, lastSync: Long): Boolean =
        enabled && configured && wifi && charging && !running && (now - lastSync >= MIN_INTERVAL_MS || lastSync > now) // lastSync > now: the clock was set back
}
