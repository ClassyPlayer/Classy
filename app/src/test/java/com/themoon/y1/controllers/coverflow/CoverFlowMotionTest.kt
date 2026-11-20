package com.themoon.y1.controllers.coverflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowMotionTest {

    private val frameMs = 16L

    /** Runs frames from [startMs] until [endMs] (or until rest), and calls [each] after each frame. Returns the last time. */
    private fun run(m: CoverFlowMotion, startMs: Long, endMs: Long, each: (Long) -> Unit = {}): Long {
        var t = startMs
        while (t < endMs) {
            t += frameMs
            val moving = m.frame(t)
            each(t)
            if (!moving) break
        }
        return t
    }

    @Test
    fun oneStepFromRestIsAnEaseOutOfAbout150ms() {
        val m = CoverFlowMotion()
        m.reset(10)
        m.step(1, 0L)
        assertEquals(11, m.target)
        var last = 10f
        var firstMove = -1f
        val end = run(m, 0L, 1000L) {
            val move = m.pos - last
            assertTrue("never back", move >= 0f)
            assertTrue("never past the target", m.pos <= 11f)
            if (firstMove < 0f) firstMove = move
            else assertTrue("slows down", move <= firstMove + 1e-6f)
            last = m.pos
        }
        assertEquals(11f, m.pos, 0f)
        assertFalse(m.isMoving)
        assertTrue("first frame shows the move: $firstMove", firstMove > 0.3f)
        assertTrue("lands in about 150 ms: $end", end in 120L..180L)
    }

    @Test
    fun stepsBeforeAFrameOnlyChangeTheTarget() {
        val m = CoverFlowMotion()
        m.reset(0)
        repeat(5) { m.step(-1, 0L) }
        assertEquals(-5, m.target)
        assertEquals(0f, m.pos, 0f)
        var last = 0f
        run(m, 0L, 2000L) {
            assertTrue("continuous: ${last - m.pos}", last - m.pos < 2f)
            assertTrue(m.pos >= -5f)
            last = m.pos
        }
        assertEquals(-5f, m.pos, 0f)
    }

    /** Steps every [gapMs] for 3 s: the covers stay near the wheel, never pass it, and glide (the speed changes little). */
    private fun steadyRun(gapMs: Long, maxBehind: Float, maxRipple: Float) {
        val m = CoverFlowMotion()
        m.reset(0)
        var nextStep = 0L
        var t = 0L
        var last = 0f
        var minMove = Float.MAX_VALUE
        var maxMove = 0f
        while (t < 3000L) {
            while (nextStep <= t) {
                m.step(1, nextStep)
                nextStep += gapMs
            }
            t += frameMs
            m.frame(t)
            val move = m.pos - last
            last = m.pos
            assertTrue("never back at $t", move >= 0f)
            assertTrue("never past the target at $t", m.pos <= m.target)
            if (t > 1000L) {
                assertTrue("at most $maxBehind behind at $t: ${m.target - m.pos}", m.target - m.pos <= maxBehind)
                minMove = Math.min(minMove, move)
                maxMove = Math.max(maxMove, move)
            }
        }
        assertTrue("glides at $gapMs ms: $maxMove / $minMove", maxMove <= maxRipple * minMove)
    }

    @Test
    fun aRunGlidesNearTheWheel() {
        steadyRun(gapMs = 120L, maxBehind = 1.6f, maxRipple = 4f)
        steadyRun(gapMs = 80L, maxBehind = 1.6f, maxRipple = 4f)
        steadyRun(gapMs = 50L, maxBehind = 1.8f, maxRipple = 4f)
    }

    @Test
    fun theCoversLandSoonAfterTheLastStep() {
        val m = CoverFlowMotion()
        m.reset(0)
        var t = 0L
        for (i in 0 until 20) {
            m.step(1, t)
            t = run(m, t, t + 100L)
        }
        val end = run(m, t, t + 2000L) { assertTrue(m.pos <= 20f) }
        assertEquals(20f, m.pos, 0f)
        assertTrue("lands in ${end - t} ms", end - t <= 450L)
    }

    @Test
    fun theSameMotionAtEachFrameRate() {
        fun posAt(frame: Long, atMs: Long): Float {
            val m = CoverFlowMotion()
            m.reset(0)
            m.step(1, 0L)
            m.step(1, 40L)
            var t = 0L
            m.frame(t)
            while (t < atMs) {
                t += frame
                m.frame(t)
            }
            return m.pos
        }
        assertEquals(posAt(16L, 96L), posAt(32L, 96L), 0.02f)
        assertEquals(posAt(16L, 96L), posAt(8L, 96L), 0.02f)
    }

    @Test
    fun aStepTheOtherWayTurnsAtOnceWithoutABounce() {
        val m = CoverFlowMotion()
        m.reset(0)
        m.step(1, 0L)
        var t = run(m, 0L, 40L)
        m.step(-1, t)
        assertEquals(0, m.target)
        assertTrue("moves back at once", m.velocity <= 0f)
        run(m, t, t + 1000L) { assertTrue(m.pos >= 0f) }
        assertEquals(0f, m.pos, 0f)
    }

    @Test
    fun aFlyByFollowsTheFlyByCurveAndHidesTheTexts() {
        val m = CoverFlowMotion()
        m.reset(0)
        m.flyTo(50)
        assertTrue(m.flying)
        val ms = CoverFlowFlyBy.durationMs(50f)
        var minLevel = 1f
        val end = run(m, 0L, 3000L) { minLevel = Math.min(minLevel, m.textLevel) }
        assertEquals(50f, m.pos, 0f)
        assertFalse(m.flying)
        assertEquals(0f, minLevel, 0f)
        assertEquals(1f, m.textLevel, 0f)
        // The fly-by time, then the text fade.
        assertTrue("$end", end >= ms + CoverFlowFlyBy.TEXT_FADE_MS - frameMs && end <= ms + CoverFlowFlyBy.TEXT_FADE_MS + 3 * frameMs)
    }

    @Test
    fun aStepDuringAFarFlyByMovesItsTarget() {
        val m = CoverFlowMotion()
        m.reset(0)
        m.flyTo(100)
        run(m, 0L, 64L)
        m.step(1, 64L)
        assertTrue(m.flying)
        assertEquals(101, m.target)
        run(m, 64L, 5000L)
        assertEquals(101f, m.pos, 0f)
    }

    @Test
    fun aStepNearTheEndOfAFlyByChangesToTheSpring() {
        val m = CoverFlowMotion()
        m.reset(0)
        m.flyTo(10)
        val ms = CoverFlowFlyBy.durationMs(10f)
        var t = run(m, 0L, ms - 100L)
        assertTrue(m.flying && 10f - m.pos < CoverFlowMotion.CATCH_UP_DISTANCE)
        m.step(1, t)
        assertFalse(m.flying)
        assertEquals(11, m.target)
        var last = m.pos
        t = run(m, t, t + 2000L) {
            assertTrue("continuous", m.pos - last in 0f..0.5f)
            assertTrue(m.pos <= 11f)
            last = m.pos
        }
        assertEquals(11f, m.pos, 0f)
        assertEquals(1f, m.textLevel, 0f)
    }

    @Test
    fun shiftKeepsTheMotion() {
        val m = CoverFlowMotion()
        m.reset(305)
        m.step(1, 0L)
        m.frame(16L)
        val pos = m.pos
        m.shift(-300)
        assertEquals(6, m.target)
        assertEquals(pos - 300f, m.pos, 1e-4f)
        run(m, 16L, 1000L)
        assertEquals(6f, m.pos, 0f)
    }

    @Test
    fun textShowsOnlyNearTheCentre() {
        assertEquals(1f, CoverFlowMotion.textAlpha(0f, 1f), 0f)
        assertEquals(0.5f, CoverFlowMotion.textAlpha(-0.25f, 1f), 1e-6f)
        assertEquals(0f, CoverFlowMotion.textAlpha(0.5f, 1f), 0f)
        assertEquals(0f, CoverFlowMotion.textAlpha(3f, 1f), 0f)
        assertEquals(0.25f, CoverFlowMotion.textAlpha(0f, 0.25f), 1e-6f)
    }
}
