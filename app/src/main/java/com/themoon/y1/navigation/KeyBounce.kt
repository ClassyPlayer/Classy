package com.themoon.y1.navigation

/**
 * Drops the extra contacts of one button push. On the Y2 the centre button comes from the touch controller of
 * the wheel (mtk-tpd), and one push can give more presses of about 20 ms each, less than [bounceMs] after the
 * last release of the same key. Such a press is not a new push: its press, repeats, long press and release are all
 * dropped. A push that bounces at its start acts on the release of its first contact, as a short push.
 *
 * Times are the key event times (uptime ms), so a busy main thread does not change the result. Pure state:
 * InputRouter gives it the events of the centre keys in their order; each key code has its own state.
 */
internal class KeyBounce(private val bounceMs: Long = BOUNCE_MS) {
    // For each key code: the event time of its last release, and whether its current press is dropped
    private val lastReleaseMs = HashMap<Int, Long>()
    private val bouncing = HashSet<Int>()

    /** True: a press of [keyCode] at [downTimeMs] is a bounce. Does not change the state. */
    fun isBounce(keyCode: Int, downTimeMs: Long): Boolean {
        val last = lastReleaseMs[keyCode] ?: return false
        return downTimeMs - last in 0 until bounceMs
    }

    /** First press event (no repeat). True: it is a bounce, drop it. */
    fun onPress(keyCode: Int, downTimeMs: Long): Boolean {
        val isBounce = isBounce(keyCode, downTimeMs)
        if (isBounce) bouncing.add(keyCode) else bouncing.remove(keyCode)
        return isBounce
    }

    /** Key repeat or long press of [keyCode]. True: it belongs to a dropped press. */
    fun onRepeat(keyCode: Int): Boolean = keyCode in bouncing

    /** Release. True: it belongs to a dropped press. Each release (also a dropped one) starts a new bounce time. */
    fun onRelease(keyCode: Int, eventTimeMs: Long): Boolean {
        lastReleaseMs[keyCode] = eventTimeMs
        return bouncing.remove(keyCode)
    }

    companion object {
        /**
         * Shorter than the gap of a fast double click (about 100 ms on the Y2, used for Double-Click to Favorite),
         * longer than the bounce gaps of the touch controller (one or two scans of about 20 ms).
         */
        const val BOUNCE_MS = 60L
    }
}
