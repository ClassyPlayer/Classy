package com.themoon.y1.managers

/**
 * Pauses everything that plays: music (also audiobooks, podcasts and Play On; their places are saved), video (its
 * resume point is saved) and the radio ([radio]; by default paused as with Play/Pause). Used by the sleep timer and
 * a USB share. Call it on the main thread: [AudioPlayerManager.isPlaying] is only up to date there.
 */
object PlaybackPause {
    fun all(radio: () -> Unit = { AudioSources.app.pauseRadio() }) {
        try {
            val am = AudioPlayerManager.getInstance()
            if (am.isPlaying()) am.pauseMusic()
        } catch (_: Exception) {}
        try {
            val vm = VideoPlayerManager.getInstance()
            if (vm.isPlaying()) vm.togglePlayPause()
        } catch (_: Exception) {}
        try {
            radio()
        } catch (_: Exception) {}
    }
}
