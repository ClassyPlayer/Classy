package com.themoon.y1.managers

/**
 * What a short Previous press does (issue #141). With Settings → Playback → Previous Restarts Song on, as on an
 * iPod: once the song has played [RESTART_AFTER_MS], Previous goes back to its start; pressed again right away, it
 * goes to the previous song. Off: Previous always goes to the previous song.
 */
object PreviousButton {

    /** Past this position Previous goes back to the start of the song (the iPod and ExoPlayer use 3 s). */
    const val RESTART_AFTER_MS = 3000L

    fun restarts(enabled: Boolean, positionMs: Long): Boolean = enabled && positionMs > RESTART_AFTER_MS
}
