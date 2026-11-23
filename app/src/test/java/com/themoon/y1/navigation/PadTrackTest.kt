package com.themoon.y1.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadTrackTest {
    private fun pads(vararg p: Int) = p.fold(0) { m, i -> m or (1 shl i) }

    @Test
    fun knownPositions() {
        val r = PadDecoder.Reader()
        // Pad 4 is at 12 o'clock; half a pad = one position
        assertEquals(0, r.read(pads(4), PadDecoder.NONE))
        assertEquals(1, r.read(pads(4, 5), PadDecoder.NONE))
        assertEquals(2, r.read(pads(4, 5, 6), PadDecoder.NONE))
        assertEquals(28, r.read(pads(3), PadDecoder.NONE))
        // Pad 15 is next to pad 1 (bits 14 and 0)
        assertEquals(21, r.read(pads(14, 0), PadDecoder.NONE))
        assertEquals(PadDecoder.NONE, r.read(0, PadDecoder.NONE))
    }

    @Test
    fun everyRealTouchHasAPosition() {
        // A thumb covers 1–5 pads. A mask all round the wheel has no centre: no thumb.
        val r = PadDecoder.Reader()
        for (mask in 1 until (1 shl 15)) {
            if (Integer.bitCount(mask) > 5) continue
            val p = r.read(mask, PadDecoder.NONE)
            assertTrue("mask $mask -> $p", p in 0 until PadDecoder.POSITIONS)
        }
    }

    @Test
    fun aGapOfOnePadJoinsTwoGroupsDoNot() {
        val r = PadDecoder.Reader()
        assertEquals(2, r.read(pads(4, 6), PadDecoder.NONE))
        assertEquals(1, r.lastGroups)
        // Two fingers: the group near the followed position, or the largest one
        val two = pads(4, 5, 6, 10)
        assertEquals(12, r.read(two, 12))
        assertEquals(2, r.lastGroups)
        assertEquals(2, r.read(two, 1))
        assertEquals(2, r.read(two, PadDecoder.NONE))
    }

    @Test
    fun shortestWay() {
        assertEquals(2, PadDecoder.shortest(29, 1))
        assertEquals(-2, PadDecoder.shortest(1, 29))
        assertEquals(15, PadDecoder.shortest(0, 15))
    }

    @Test
    fun eachRowNeedsTheSamePositions() {
        val c = RowCounter()
        val rows = (1..9).map { c.add(1, 3) }
        assertEquals(listOf(0, 0, 1, 0, 0, 1, 0, 0, 1), rows)
        assertEquals(listOf(0, 1, 0, 1), (1..4).map { c.add(1, 2) })
    }

    @Test
    fun aReversalCountsAgain() {
        val c = RowCounter()
        assertEquals(0, c.add(2, 3))
        // Back: a full row of travel, not the rest of the row before
        assertEquals(0, c.add(-1, 3))
        assertEquals(0, c.add(-1, 3))
        assertEquals(-1, c.add(-1, 3))
    }

    @Test
    fun aNewTouchCountsAgain() {
        val c = RowCounter()
        assertEquals(0, c.add(2, 3))
        c.reset()
        assertEquals(0, c.add(2, 3))
        assertEquals(1, c.add(1, 3))
    }

    @Test
    fun aBurstGivesAllItsRows() {
        assertEquals(5, RowCounter().add(15, 3))
        assertEquals(-7, RowCounter().add(-15, 2))
    }

    @Test
    fun speedFromPositionChanges() {
        val s = PadSpeed()
        // One position each 10 ms (with chip frames of 8–12 ms) = 1/30 turn in 10 ms = 3.33 turns a second
        var t = 0L
        for (i in 1..20) {
            t += if (i % 2 == 0) 8 else 12
            s.change(t, i)
        }
        assertEquals(3.33, s.turnsPerSecond(t, 160, 100), 0.2)
        // Too short a span: no speed yet
        val short = PadSpeed()
        short.change(0, 0)
        short.change(50, 3)
        assertEquals(0.0, short.turnsPerSecond(50, 160, 100), 0.0)
    }

    @Test
    fun aThumbThatStopsSlowsTheSpeed() {
        val s = PadSpeed()
        for (i in 0..15) s.change(i * 10L, i)
        val moving = s.turnsPerSecond(150, 160, 100)
        val stopped = s.turnsPerSecond(300, 400, 100)
        assertTrue("$stopped < $moving", stopped < moving / 4)
    }
}
