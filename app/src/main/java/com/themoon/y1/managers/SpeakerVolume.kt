package com.themoon.y1.managers

/**
 * The volume of a Bluetooth speaker or headphones (A2DP), and the safe start level.
 *
 * With AVRCP absolute volume (Y2 ROM: tools/patch_bluetooth_absolute_volume.py), Android sends its music index to the
 * speaker and moves the index when the speaker's own buttons change the volume. So on A2DP the step uses
 * [VolumeSplit.INDEX] (the index carries the volume, as with FM radio), and a change of the index that is not ours is
 * the speaker's: the step follows it, without writing it back. Without absolute volume, INDEX still works: Android
 * then lowers the A2DP audio by the index itself.
 *
 * Safe start (hearing protection): the step is at most [SAFE_STEP] for headphones and Bluetooth (not the speaker of
 * the player) when Classy starts, when the output changes (headphones in, a Bluetooth device connects, also a
 * different one), when a Bluetooth device reports a louder volume of its own just after it connected, and when music
 * starts after a pause of [LONG_PAUSE_MS] in which nobody changed the volume. It only lowers the volume.
 */
object SpeakerVolume {
    /** 10 of 30 steps: -40 dB; on A2DP index 2 of 15, which the speaker gets as 16 of 127 (about 13 %). */
    const val SAFE_STEP = 10

    /** A pause this long (or longer) without a volume change starts the music at [SAFE_STEP] at most. */
    const val LONG_PAUSE_MS = 15L * 60L * 1000L

    /** After the output changes to A2DP, AVRCP connects and the speaker reports its own volume within this time. */
    const val CONNECT_WINDOW_MS = 10_000L

    /** A change of the index this soon after our own index write is the speaker confirming it, not a new volume. */
    const val ECHO_WINDOW_MS = 1_500L

    /** Bluetooth carries the volume in the Android index (absolute volume); other outputs keep it at the top. */
    fun split(fmVolumeMode: Boolean, output: AudioOutput?): VolumeSplit =
        if (fmVolumeMode || output == AudioOutput.A2DP) VolumeSplit.INDEX else VolumeSplit.SOFTWARE

    /** The step to start [output] with: [SAFE_STEP] at most, except on the player's own speaker. */
    fun safeStep(step: Int, output: AudioOutput?): Int =
        if (output == null || output == AudioOutput.SPEAKER) step else minOf(step, SAFE_STEP)

    /** True when a speaker's report of [step] comes so soon after the output changed that it is capped. */
    fun capsSpeakerReport(step: Int, msSinceOutputChange: Long): Boolean =
        step > SAFE_STEP && msSinceOutputChange in 0 until CONNECT_WINDOW_MS

    /** True when a change of the A2DP index to [newIndex] is ours (our target, or the speaker confirming our write). */
    fun isOwnChange(newIndex: Int, targetIndex: Int, msSinceOwnWrite: Long): Boolean =
        newIndex == targetIndex || msSinceOwnWrite in 0 until ECHO_WINDOW_MS

    /**
     * True when music that starts at [nowMs] starts at [SAFE_STEP] at most: it was paused at [pausedAtMs] and the
     * volume last changed at [volumeChangedAtMs] (both 0 when unknown), and the later of the two is [LONG_PAUSE_MS] ago.
     * The times are [android.os.SystemClock.elapsedRealtime], which also counts while the player sleeps.
     */
    fun capsOnPlay(nowMs: Long, pausedAtMs: Long, volumeChangedAtMs: Long): Boolean {
        val since = maxOf(pausedAtMs, volumeChangedAtMs)
        return since > 0L && nowMs - since >= LONG_PAUSE_MS
    }
}
