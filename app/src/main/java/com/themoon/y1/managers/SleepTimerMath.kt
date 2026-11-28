package com.themoon.y1.managers

/**
 * The sleep timer's rules without Android types ([SleepTimer], the Sleep Timer screens): the wheel steps of the
 * custom time, the time text, the rows of the list and the fade-out.
 */
object SleepTimerMath {
    /** The shortest and the longest timer, in minutes. The wheel stops at both. */
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 24 * 60

    /** The preset rows of the list, in minutes: the iPod classic's own (any other time is one Custom… away). */
    val PRESETS = intArrayOf(15, 30, 60, 90, 120)

    /** The custom screen starts here when there is no timer and no earlier custom time. */
    const val DEFAULT_CUSTOM_MINUTES = 30

    /** The volume fades out over the last [FADE_MS] of the timer. */
    const val FADE_MS = 30_000L

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /**
     * The minutes of one wheel detent, from the time since the detent before ([intervalMs]; a slow turn, a turn back
     * or the first detent: -1). The wheel gives about 10 detents a turn: a slow turn sets single minutes, a fast one
     * goes by bigger steps, so 8 hours are a few turns away.
     */
    fun wheelStep(intervalMs: Long): Int = when {
        intervalMs < 0 -> 1
        intervalMs < 50 -> 15
        intervalMs < 80 -> 10
        intervalMs < 120 -> 5
        intervalMs < 170 -> 2
        else -> 1
    }

    /**
     * [minutes] moved by one detent of [step] minutes in the direction of [direction]: to the next multiple of
     * [step] (so the values stay round), between [MIN_MINUTES] and [MAX_MINUTES].
     */
    fun stepped(minutes: Int, direction: Int, step: Int): Int {
        if (direction == 0) return clamp(minutes)
        val s = step.coerceAtLeast(1)
        val next = if (direction > 0) (minutes / s + 1) * s else (minutes - 1).floorDiv(s) * s
        return clamp(next)
    }

    /** Whole minutes left from [nowMs] to [endAtMs], rounded up: 1 in the last minute, 0 when the end has come. */
    fun minutesLeft(endAtMs: Long, nowMs: Long): Int {
        val left = endAtMs - nowMs
        return if (left <= 0) 0 else ((left + 59_999L) / 60_000L).toInt()
    }

    /** The ms from [nowMs] until [minutesLeft] goes down by one (at least 1). */
    fun msToNextMinute(endAtMs: Long, nowMs: Long): Long {
        val left = endAtMs - nowMs
        if (left <= 0) return 1L
        val rest = left % 60_000L
        return if (rest == 0L) 60_000L else rest
    }

    /**
     * "45 min", "2 h", "1 h 05 min": [min], [hours] and [hoursMin] are the translated "%d min", "%d h" and
     * "%d h %02d min".
     */
    fun durationText(minutes: Int, min: String, hours: String, hoursMin: String): String = when {
        minutes < 60 -> String.format(min, minutes)
        minutes % 60 == 0 -> String.format(hours, minutes / 60)
        else -> String.format(hoursMin, minutes / 60, minutes % 60)
    }

    /** The minutes of the list rows: the last custom time first (when it is not a preset), then the presets. */
    fun listMinutes(lastCustom: Int, presets: IntArray = PRESETS): List<Int> {
        val rows = ArrayList<Int>()
        if (lastCustom in MIN_MINUTES..MAX_MINUTES && lastCustom !in presets) rows += lastCustom
        presets.forEach { rows += it }
        return rows
    }

    /** The time the custom screen starts at: the time left of a running timer, else the last custom time, else 30 min. */
    fun customStart(minutesLeft: Int, lastCustom: Int): Int = when {
        minutesLeft > 0 -> clamp(minutesLeft)
        lastCustom in MIN_MINUTES..MAX_MINUTES -> lastCustom
        else -> DEFAULT_CUSTOM_MINUTES
    }

    /**
     * The volume factor [remainingMs] before the end: 1 before the fade, then down to 0 at the end. Squared, so the
     * fade sounds even (the ear hears loudness, not the gain, as it goes down).
     */
    fun fadeFactor(remainingMs: Long, fadeMs: Long = FADE_MS): Float {
        if (remainingMs >= fadeMs) return 1f
        if (remainingMs <= 0) return 0f
        val x = remainingMs.toFloat() / fadeMs
        return x * x
    }
}
