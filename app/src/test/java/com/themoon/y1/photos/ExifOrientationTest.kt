package com.themoon.y1.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifOrientationTest {

    @Test
    fun allEightValues() {
        val rotation = (1..8).map { ExifOrientation.rotation(it) }
        val mirror = (1..8).map { ExifOrientation.mirror(it) }
        assertEquals(listOf(0, 0, 180, 180, 270, 90, 90, 270), rotation)
        assertEquals(listOf(false, true, false, true, true, false, true, false), mirror)
    }

    /** Where the stored pixel (x, y) of a [w] x [h] image goes: mirror first, then the clockwise turn. */
    private fun place(o: Int, x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> {
        var px = if (ExifOrientation.mirror(o)) w - 1 - x else x
        var py = y
        var cw = w
        var ch = h
        repeat(ExifOrientation.rotation(o) / 90) {
            val nx = ch - 1 - py
            val ny = px
            px = nx; py = ny
            val t = cw; cw = ch; ch = t
        }
        return px to py
    }

    @Test
    fun transposeAndTransverse() {
        // 5 = transpose: (x, y) -> (y, x); 7 = transverse: (x, y) -> (h-1-y, w-1-x)
        assertEquals(2 to 1, place(5, 1, 2, 4, 3))
        assertEquals((3 - 1 - 2) to (4 - 1 - 1), place(7, 1, 2, 4, 3))
        // 6 = turn 90 clockwise: the top-left goes to the top-right
        assertEquals(2 to 0, place(6, 0, 0, 4, 3))
    }

    @Test
    fun quarterTurnsSwapTheSides() {
        assertFalse(ExifOrientation.swapsSides(1))
        assertFalse(ExifOrientation.swapsSides(3))
        assertTrue(ExifOrientation.swapsSides(6))
        assertTrue(ExifOrientation.swapsSides(8))
    }

    @Test
    fun unknownValuesAreUpright() {
        assertEquals(1, ExifOrientation.normalize(0))
        assertEquals(1, ExifOrientation.normalize(9))
        assertEquals(6, ExifOrientation.normalize(6))
    }
}
