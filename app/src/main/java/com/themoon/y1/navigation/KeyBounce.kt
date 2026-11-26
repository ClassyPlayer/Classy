package com.themoon.y1.navigation

/**
 * Drops the extra contacts of one button push. On the Y2 the centre button comes from the touch controller of
 * the wheel (mtk-tpd), and one push can give more presses of about 20 ms each, less than [bounceMs] after the
 * last release of the same key. Such a press is not a new push: its press, repeats, long press and release are all
 * dropped. A push that bounces at its start acts on the release of its first contact, as a short push.
 *
 * Times are the key event times (uptime ms), so a busy main thread does not change the result. Pure state:
 * InputRouter gives it the events of one key in their order.
 */
internal class KeyBounce(private val bounceMs: Long = BOUNCE_MS) {
    private var lastReleaseKey = 0
    private var lastReleaseMs: Long? = null
    private var isBouncing = false

    /** True: a press of [keyCode] at [downTimeMs] is a bounce. Does not change the state. */
    fun isBounce(keyCode: Int, downTimeMs: Long): Boolean {
        if (keyCode != lastReleaseKey) return false
        val gap = lastReleaseMs?.let { downTimeMs - it } ?: return false
        return gap in 0 until bounceMs
    }

    /** First press event (no repeat). True: it is a bounce, drop it. */
    fun onPress(keyCode: Int, downTimeMs: Long): Boolean {
        isBouncing = isBounce(keyCode, downTimeMs)
        return isBouncing
    }

    /** Key repeat or long press. True: it belongs to a dropped press. */
    fun onRepeat(): Boolean = isBouncing

    /** Release. True: it belongs to a dropped press. Each release (also a dropped one) starts a new bounce time. */
    fun onRelease(keyCode: Int, eventTimeMs: Long): Boolean {
        val wasBouncing = isBouncing
        isBouncing = false
        lastReleaseKey = keyCode
        lastReleaseMs = eventTimeMs
        return wasBouncing
    }

    companion object {
        /**
         * Shorter than the gap of a fast double click (about 100 ms on the Y2, used for Double-Click to Favorite),
         * longer than the bounce gaps of the touch controller (one or two scans of about 20 ms).
         */
        const val BOUNCE_MS = 60L
    }
}
