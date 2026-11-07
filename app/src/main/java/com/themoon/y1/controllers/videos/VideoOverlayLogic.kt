package com.themoon.y1.controllers.videos

/**
 * Pure rules for the video overlay (the title at the top and the time bar at the bottom).
 *
 * - The overlay shows when a video starts and at each button push ([show]).
 * - While the video plays, it hides [hideDelayMs] after the last [show].
 * - While the video is paused (or at the end), it stays: the delay starts again when the video plays.
 *
 * Times are SystemClock.uptimeMillis(). Not thread-safe: call on the main thread.
 */
class VideoOverlayLogic(private val hideDelayMs: Long = HIDE_DELAY_MS) {

    private var shownAtMs = 0L
    private var playing = false

    var visible = true
        private set

    /** A new video or a button push: show the overlay, and start the delay again. */
    fun show(nowMs: Long) {
        visible = true
        shownAtMs = nowMs
    }

    /** Gives if the overlay shows at [nowMs]. */
    fun update(nowMs: Long, isPlaying: Boolean): Boolean {
        playing = isPlaying
        if (!isPlaying) {
            show(nowMs)
        } else if (visible && nowMs - shownAtMs >= hideDelayMs) {
            visible = false
        }
        return visible
    }

    /** Time until the overlay hides, or -1 if it does not hide (hidden already, or paused at the last [update]). */
    fun msUntilHide(nowMs: Long): Long {
        if (!visible || !playing) return -1L
        return (hideDelayMs - (nowMs - shownAtMs)).coerceAtLeast(0L)
    }

    companion object {
        const val HIDE_DELAY_MS = 3_500L
    }
}
