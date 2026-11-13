package com.themoon.y1.controllers.videos

/**
 * Scrub mode of the video player, as the Scrub state of Now Playing: the centre button opens it, each wheel step
 * moves a pending position by [STEP_MS], and the centre button again seeks to it and closes the mode. Back closes
 * it with no seek. Pure state: [VideoPlayerController] gives it the positions and does the seek.
 */
internal class VideoScrub {
    var isActive = false
        private set

    /** True after a wheel step: the timeline shows [pendingMs], not the play position. */
    var isChanged = false
        private set

    var pendingMs = 0L
        private set

    fun open() {
        isActive = true
        isChanged = false
    }

    /** One wheel step from the pending position (the play position before the first step), in 0..[durationMs]. */
    fun step(forward: Boolean, positionMs: Long, durationMs: Long) {
        if (!isActive) return
        if (!isChanged) pendingMs = positionMs
        val target = pendingMs + if (forward) STEP_MS else -STEP_MS
        pendingMs = if (durationMs > 0) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
        isChanged = true
    }

    /** The position that the timeline shows. */
    fun shownMs(positionMs: Long): Long = if (isActive && isChanged) pendingMs else positionMs

    /** Closes the mode. Returns the position to seek to, or null if the wheel did not move. */
    fun commit(): Long? {
        val target = if (isActive && isChanged) pendingMs else null
        cancel()
        return target
    }

    /** Closes the mode with no seek. */
    fun cancel() {
        isActive = false
        isChanged = false
    }

    companion object {
        /** The same step as the Scrub state of Now Playing. */
        const val STEP_MS = 5000L

        /**
         * The scrub diamond in dp: as in Now Playing, 2 dp above and below the 20 dp time bar (activity_main.xml,
         * video_progress). The difference is even, so the diamond centre is on the bar centre.
         */
        const val DIAMOND_WIDTH_DP = 22
        const val DIAMOND_HEIGHT_DP = 24
    }
}
