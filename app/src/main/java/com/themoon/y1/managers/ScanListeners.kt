package com.themoon.y1.managers

/**
 * The listeners of the library scan. A start request while a scan runs joins that scan: its listener gets the
 * progress and the completion of the running scan. Without this, a MainActivity made again during a scan stayed in
 * its scanning state, because the scan told only the old activity.
 */
internal class ScanListeners {
    private val listeners = ArrayList<MediaLibraryManager.ScanProgressListener>()
    private var scanId = 0

    @Volatile
    var isRunning = false
        private set

    /** Adds [listener]. Returns the id of a new scan that the caller must start, or null when it joined the running scan. */
    @Synchronized
    fun join(listener: MediaLibraryManager.ScanProgressListener?): Int? {
        if (listener != null) listeners.add(listener)
        if (isRunning) return null
        isRunning = true
        return ++scanId
    }

    @Synchronized
    fun current(): List<MediaLibraryManager.ScanProgressListener> = ArrayList(listeners)

    /**
     * Ends scan [id] and gives the listeners to tell. A call for a scan that has ended does nothing, so it cannot
     * end or clear a newer scan. A [join] after this starts a new scan.
     */
    @Synchronized
    fun finish(id: Int): List<MediaLibraryManager.ScanProgressListener> {
        if (!isRunning || id != scanId) return emptyList()
        isRunning = false
        return ArrayList(listeners).also { listeners.clear() }
    }
}
