package com.themoon.y1.managers

/**
 * The listeners of the library scan. A start request while a scan runs joins that scan: its listener gets the
 * progress and the completion of the running scan. Without this, a MainActivity made again during a scan stayed in
 * its scanning state, because the scan told only the old activity.
 */
internal class ScanListeners {
    private val listeners = ArrayList<MediaLibraryManager.ScanProgressListener>()

    @Volatile
    var isRunning = false
        private set

    /** Adds [listener]. True when no scan runs: the caller must then start one. */
    @Synchronized
    fun join(listener: MediaLibraryManager.ScanProgressListener?): Boolean {
        if (listener != null) listeners.add(listener)
        if (isRunning) return false
        isRunning = true
        return true
    }

    @Synchronized
    fun current(): List<MediaLibraryManager.ScanProgressListener> = ArrayList(listeners)

    /** Ends the scan and gives the listeners to tell. A [join] after this starts a new scan. */
    @Synchronized
    fun finish(): List<MediaLibraryManager.ScanProgressListener> {
        isRunning = false
        return ArrayList(listeners).also { listeners.clear() }
    }
}
