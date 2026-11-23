package com.themoon.y1.controllers.coverflow

/**
 * The position of the Cover Flow cards: one model for the wheel steps and for the fly-by at the end of a letter jump.
 * No Android dependencies, so it can be unit-tested.
 *
 * A wheel step changes only [target]. The controller calls [frame] one time for each display frame and puts the cards
 * at [pos]. Thus several steps between two frames cost only index changes, and the covers move continuously at all
 * step rates (no jumps, no animators).
 *
 * Positions are album numbers. [pos] is fractional during a move. Both can be outside 0 until the album count (the
 * cards show them modulo the count): [shift] moves them back.
 *
 * Wheel steps: [pos] chases [target] as a critically damped spring with the rate (1/s) [rate]:
 * - The first step of a run (the wheel was still for [RUN_GAP_MS], or turns the other way) also gives the spring the
 *   speed that makes its move a pure ease-out: fast at the start, slow at the end. At [REST_RATE] a step takes about
 *   150 ms (45 % of the move in the first frame, 90 % in 65 ms).
 * - In a run, the rate follows the time between the steps ([GLIDE]): the covers glide at the speed of the wheel,
 *   about one album behind it, with a speed that changes little from step to step.
 * - When the wheel stops (no step for [LAND_GAPS] step times), or the covers are more than [CATCH_UP_DISTANCE] albums
 *   behind, the rate goes back to [REST_RATE]: the covers land, or catch up, fast.
 * - The spring never moves past the target (the speed toward the target is at most rate × distance).
 *
 * Fly-by ([flyTo]): [CoverFlowFlyBy] gives the position for the time since the start; the texts fade out
 * ([textLevel]) and fade in again at the end. A wheel step during a fly-by moves its target one album, or changes to
 * the spring when the covers are near the target.
 */
class CoverFlowMotion {

    /** The position on the screen (album number). */
    var pos = 0f
        private set

    /** The album number that the wheel or the letter jump asked for: the status title and select use it. */
    var target = 0
        private set

    /** Speed of the position (albums a second, + to higher numbers). */
    var velocity = 0f
        private set

    /** True during a fly-by. */
    var flying = false
        private set

    /** Opacity of the card texts (0 to 1): 0 during a fly-by. Multiply with [textAlpha]. */
    var textLevel = 1f
        private set

    /** The spring rate (1/s) now. */
    var rate = REST_RATE
        private set

    private var gapMs = RUN_GAP_MS.toFloat()
    private var lastStepMs = NO_TIME
    private var lastDir = 0
    private var lastFrameMs = NO_TIME
    private var flyStartPos = 0f
    private var flyStartMs = NO_TIME
    private var flyDurationMs = 0L

    /** True while [frame] must be called again. */
    val isMoving: Boolean
        get() = flying || pos != target.toFloat() || velocity != 0f || textLevel < 1f

    /** At rest at [album], texts shown. */
    fun reset(album: Int) {
        pos = album.toFloat()
        target = album
        velocity = 0f
        flying = false
        textLevel = 1f
        rate = REST_RATE
        gapMs = RUN_GAP_MS.toFloat()
        lastStepMs = NO_TIME
        lastDir = 0
        lastFrameMs = NO_TIME
    }

    /** One wheel step of [dir] (-1 or 1) at [timeMs] (the key event time). */
    fun step(dir: Int, timeMs: Long) {
        val gap = if (lastStepMs == NO_TIME) Long.MAX_VALUE else timeMs - lastStepMs
        val reversed = lastDir != 0 && dir != lastDir
        val atRest = !flying && pos == target.toFloat() && velocity == 0f
        lastStepMs = timeMs
        lastDir = dir
        if (flying) {
            if (Math.abs(target + dir - pos) > CATCH_UP_DISTANCE) {
                // Far from the target: the fly-by continues to one album past it.
                flyTo(target + dir)
                return
            }
            // Near the target: the spring takes over, with the speed of the fly-by.
            flying = false
            target += dir
            startRun()
        } else {
            target += dir
            if (atRest || reversed || gap > RUN_GAP_MS) {
                startRun()
                if (reversed) velocity = 0f
                velocity += dir * REST_RATE
            } else {
                gapMs += GAP_SMOOTHING * (gap.toFloat() - gapMs)
                rate = (GLIDE * 1000f / gapMs.coerceAtLeast(1f)).coerceIn(MIN_RATE, REST_RATE)
            }
        }
        if (Math.abs(target - pos) > CATCH_UP_DISTANCE) rate = REST_RATE
        limitVelocity()
    }

    /**
     * Starts a fly-by from the position on the screen to album [to], or changes the target of the running fly-by
     * (it continues from where the covers are).
     */
    fun flyTo(to: Int) {
        flying = true
        flyStartPos = pos
        target = to
        flyStartMs = NO_TIME
        flyDurationMs = CoverFlowFlyBy.durationMs(to - pos)
    }

    /**
     * Moves to the display frame at [nowMs]. Returns true while it moves: then call it again at the next frame.
     * The first frame after a rest counts as [FIRST_FRAME_MS] (a step then shows in the next frame), and a long
     * frame counts as at most [MAX_FRAME_MS] (after a pause the covers do not jump).
     */
    fun frame(nowMs: Long): Boolean {
        val dt = if (lastFrameMs == NO_TIME) FIRST_FRAME_MS else (nowMs - lastFrameMs).coerceIn(0L, MAX_FRAME_MS)
        lastFrameMs = nowMs
        if (flying) {
            if (flyStartMs == NO_TIME) flyStartMs = nowMs - FIRST_FRAME_MS
            val elapsed = nowMs - flyStartMs
            val old = pos
            if (elapsed >= flyDurationMs) {
                pos = target.toFloat()
                velocity = 0f
                flying = false
            } else {
                pos = flyStartPos + CoverFlowFlyBy.offsetAt(elapsed, flyDurationMs, target - flyStartPos)
                // For a wheel step that takes over (limitVelocity removes the jump of the skipped albums).
                if (dt > 0L) velocity = (pos - old) * 1000f / dt
            }
        } else if (pos != target.toFloat() || velocity != 0f) {
            if (lastStepMs == NO_TIME || nowMs - lastStepMs > LAND_GAPS * gapMs ||
                Math.abs(target - pos) > CATCH_UP_DISTANCE
            ) rate = REST_RATE
            spring(dt / 1000f)
            if (Math.abs(target - pos) < REST_DISTANCE && Math.abs(velocity) < REST_SPEED) {
                pos = target.toFloat()
                velocity = 0f
            }
        }
        val goal = if (flying) 0f else 1f
        val fade = dt.toFloat() / CoverFlowFlyBy.TEXT_FADE_MS
        textLevel = if (textLevel < goal) Math.min(goal, textLevel + fade) else Math.max(goal, textLevel - fade)
        val moving = isMoving
        if (!moving) lastFrameMs = NO_TIME
        return moving
    }

    /** Adds [albums] to all positions (the cards show the same albums). */
    fun shift(albums: Int) {
        pos += albums
        target += albums
        flyStartPos += albums
    }

    private fun startRun() {
        rate = REST_RATE
        gapMs = RUN_GAP_MS.toFloat()
    }

    /** At most the speed of a pure ease-out toward the target: the spring then never moves past it. */
    private fun limitVelocity() {
        val e = target - pos
        val max = rate * Math.abs(e)
        if (velocity * Math.signum(e) > max || e == 0f) velocity = Math.signum(e) * max
    }

    /** The exact critically damped spring for [t] seconds (the same for each frame rate). */
    private fun spring(t: Float) {
        val w = rate
        val e = pos - target
        val c = velocity + w * e
        val decay = Math.exp((-w * t).toDouble()).toFloat()
        pos = target + (e + c * t) * decay
        velocity = (velocity - w * c * t) * decay
    }

    companion object {
        /**
         * Spring rate (1/s) of a single step, of the landing and of a catch-up. Higher = faster. A step from rest takes
         * about 5.3 / [REST_RATE] s to land (36: ~150 ms); 90 % of the move takes 2.3 / [REST_RATE] s.
         */
        const val REST_RATE = 36f

        /**
         * In a run of steps, the rate is [GLIDE] / (time between the steps, s). The covers then glide about
         * 2 / [GLIDE] albums behind the wheel. Lower = smoother but further behind; higher = closer but more
         * step-by-step. 2.5: ~0.8 albums behind, the speed changes by ~2× in each step.
         */
        const val GLIDE = 2.5f

        /** Lowest rate (1/s) in a run of slow steps. */
        const val MIN_RATE = 12f

        /** A step more than this time (ms) after the last one starts a new run (ease-out at [REST_RATE]). */
        const val RUN_GAP_MS = 150L

        /** How fast the measured time between steps follows a new value (0 to 1; 1 = only the last gap). */
        const val GAP_SMOOTHING = 0.5f

        /** With no step for this many step times, the covers land at [REST_RATE]. */
        const val LAND_GAPS = 2f

        /** More than this many albums behind the target: the rate is [REST_RATE] (catch-up grows with the distance). */
        const val CATCH_UP_DISTANCE = 2f

        /** Distance (albums) and speed (albums/s) under which the covers stop exactly at the target. ~0.5 px. */
        const val REST_DISTANCE = 0.005f
        const val REST_SPEED = 0.5f

        /** Time (ms) of the first frame after a rest, and the longest frame time that the motion uses. */
        const val FIRST_FRAME_MS = 16L
        const val MAX_FRAME_MS = 50L

        /** The text of a card shows when it is less than this many albums from the centre. */
        const val TEXT_DISTANCE = 0.5f

        private const val NO_TIME = Long.MIN_VALUE

        /** Text opacity of a card [offset] albums from the centre: 1 in the centre, 0 from [TEXT_DISTANCE]. */
        fun textAlpha(offset: Float, level: Float): Float =
            level * (1f - Math.abs(offset) / TEXT_DISTANCE).coerceIn(0f, 1f)
    }
}
