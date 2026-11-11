package com.themoon.y1.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelTrackerTest {
    private val tracker = WheelTracker()

    /** The pads [from] to [to] (clockwise, inclusive) as a mask. */
    private fun pads(from: Int, to: Int = from): Int {
        var mask = 0
        var pad = from
        while (true) {
            mask = mask or (1 shl pad)
            if (pad == to) return mask
            pad = (pad + 1) % WheelPads.PADS
        }
    }

    private fun turnOf(inputs: List<GameInput>) = inputs.filterIsInstance<GameInput.Wheel>().sumOf { it.delta.toDouble() }
    private fun angleOf(inputs: List<GameInput>) = inputs.filterIsInstance<GameInput.WheelTouch>().lastOrNull()?.angle

    @Test
    fun padsAreClockwiseFromTheTop() {
        assertEquals(0.0, WheelPads.turns(pads(4))!!, 1e-9)
        assertEquals(0.25, WheelPads.turns(pads(7, 8))!!, 0.02)
        assertEquals(0.5, WheelPads.turns(pads(11, 12))!!, 0.04)
        // Across the seam between pad 14 and pad 0
        assertEquals(0.75, WheelPads.turns(pads(14, 1))!!, 0.02)
        assertNull(WheelPads.turns(0))
    }

    @Test
    fun guestAngleIsTheCounterClockwiseRingFromTheRight() {
        assertEquals(64, WheelPads.guestAngle(0.0))
        assertEquals(0, WheelPads.guestAngle(0.25))
        assertEquals(192, WheelPads.guestAngle(0.5))
        assertEquals(128, WheelPads.guestAngle(0.75))
    }

    @Test
    fun aTouchReportsWhereTheFingerRestsWithoutTurning() {
        val inputs = tracker.onMask(pads(4), 0)
        assertEquals(0.0, turnOf(inputs), 0.0)
        assertEquals(64, angleOf(inputs))
    }

    @Test
    fun aFullClockwiseTurnIsNinetySixDetents() {
        var time = 0L
        var turn = 0.0
        // Pad, pad pair, pad... twice round: 60 half-pad steps
        for (step in 0..60) {
            val pad = (4 + step / 2) % WheelPads.PADS
            val mask = if (step % 2 == 0) pads(pad) else pads(pad, (pad + 1) % WheelPads.PADS)
            turn += turnOf(tracker.onMask(mask, time))
            time += 10
        }
        // 2 turns = 192 detents = 96 of delta, less the dead band at the start and the part of a detent still kept
        val deadBand = WheelTracker.DEAD_BAND * WheelTracker.DETENTS_PER_TURN / 2
        assertEquals(96.0 - deadBand, turn, 0.5)
    }

    @Test
    fun counterClockwiseIsNegative() {
        tracker.onMask(pads(8), 0)
        val turn = turnOf(tracker.onMask(pads(5), 10))
        // 3 pads = 0.2 turn = 19 detents, less the dead band
        assertTrue(turn < -7)
    }

    @Test
    fun aFingerBetweenTwoPadsDoesNotScroll() {
        tracker.onMask(pads(6), 0)
        var time = 10L
        repeat(20) {
            val inputs = tracker.onMask(if (it % 2 == 0) pads(6, 7) else pads(6), time)
            assertEquals(0.0, turnOf(inputs), 0.0)
            time += 10
        }
    }

    @Test
    fun aMomentWithoutPadsIsNotALift() {
        tracker.onMask(pads(13), 0)
        assertTrue(tracker.onMask(0, 20).isEmpty())
        // The finger slid on: the turn goes on from pad 13
        assertTrue(turnOf(tracker.onMask(pads(1), 40)) > 0)
    }

    @Test
    fun aLiftIsReportedOnce() {
        tracker.onMask(pads(2), 0)
        assertTrue(tracker.onMask(0, 30).isEmpty())
        assertEquals(listOf(GameInput.WheelTouch(null)), tracker.onMask(0, 30 + WheelTracker.LIFT_MS))
        assertTrue(tracker.onMask(0, 200).isEmpty())
        // The next touch starts afresh: no turn from the old position
        val inputs = tracker.onMask(pads(10), 300)
        assertEquals(0.0, turnOf(inputs), 0.0)
    }

    @Test
    fun liftWithoutATouchIsNothing() {
        assertTrue(tracker.lift().isEmpty())
    }
}
