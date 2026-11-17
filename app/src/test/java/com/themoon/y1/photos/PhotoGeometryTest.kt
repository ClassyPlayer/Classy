package com.themoon.y1.photos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoGeometryTest {

    @Test
    fun theBaseLayoutScalesToTheY2Screen() {
        // The layout in base units (scale 1) and on the 480 x 360 Y2 (scale 1.5)
        val base = PhotoGeometry(320, 240, 20)
        val y2 = PhotoGeometry(480, 360, 30)
        assertEquals(1.0f, base.scale)
        assertEquals(1.5f, y2.scale)
        // 5 x 4 thumbnails at both scales
        assertEquals(5, base.columns)
        assertEquals(4, base.rows)
        assertEquals(4, y2.rows)
        assertEquals(20, y2.pageSize)
        // The same cell, 1.5 times larger
        val a = base.thumbRect(6, 0)
        val b = y2.thumbRect(6, 0)
        assertEquals((a[2] - a[0]) * 1.5f, (b[2] - b[0]).toFloat(), 1.5f)
        assertEquals((a[3] - a[1]) * 1.5f, (b[3] - b[1]).toFloat(), 1.5f)
    }

    @Test
    fun theGridFillsTheScreenUnderTheStatusBar() {
        val g = PhotoGeometry(480, 360, 30)
        val first = g.thumbRect(0, 0)
        val last = g.thumbRect(19, 0)
        assertEquals(30 + 5, first[1]) // 3 points of gap = 4.5 px
        // 4.5 px rounds up on both sides
        assertEquals(480 - 4, last[2])
        assertEquals(360 - 4, last[3])
        // The next page starts at the top again
        assertArrayEquals(g.thumbRect(0, 0), g.thumbRect(20, 4))
    }

    @Test
    fun thePageMovesOneRowAtATime() {
        val g = PhotoGeometry(480, 360, 30)
        assertEquals(0, g.firstRowFor(19, 0, 100))
        assertEquals(1, g.firstRowFor(20, 0, 100))
        assertEquals(1, g.firstRowFor(5, 1, 100))
        assertEquals(0, g.firstRowFor(4, 1, 100))
        // Wheel loop from the first to the last photo: the last page
        assertEquals(16, g.firstRowFor(99, 0, 100))
        // A short album never scrolls
        assertEquals(0, g.firstRowFor(7, 0, 8))
    }

    @Test
    fun fitKeepsTheWholePhoto() {
        // A 4:3 photo fills the 4:3 screen of the Y2
        assertArrayEquals(intArrayOf(480, 360), PhotoGeometry.fit(4000, 3000, 480, 360))
        // 3:2 and portrait photos get bars
        assertArrayEquals(intArrayOf(480, 320), PhotoGeometry.fit(6000, 4000, 480, 360))
        assertArrayEquals(intArrayOf(270, 360), PhotoGeometry.fit(3000, 4000, 480, 360))
    }

    @Test
    fun cropTakesTheMiddle() {
        assertArrayEquals(intArrayOf(500, 0, 3500, 3000), PhotoGeometry.crop(4000, 3000, 4, 4))
        assertArrayEquals(intArrayOf(0, 500, 3000, 3500), PhotoGeometry.crop(3000, 4000, 4, 4))
        assertArrayEquals(intArrayOf(0, 0, 4000, 3000), PhotoGeometry.crop(4000, 3000, 128, 96))
    }

    @Test
    fun sampleSizeNeverGoesBelowTheBox() {
        // 12 MP to the screen: 4000 / 8 = 500 >= 480, 4000 / 16 = 250 < 480
        assertEquals(8, PhotoGeometry.sampleSize(4000, 3000, 480, 360, cover = false))
        assertEquals(1, PhotoGeometry.sampleSize(640, 480, 480, 360, cover = false))
        // A panorama fits by its width: 12000 x 2000 shows 480 x 80
        assertEquals(16, PhotoGeometry.sampleSize(12000, 2000, 480, 360, cover = false))
        // Thumbnails cover the cell: the short side counts
        assertEquals(16, PhotoGeometry.sampleSize(12000, 2000, 128, 96, cover = true))
        assertEquals(1, PhotoGeometry.sampleSize(0, 0, 128, 96, cover = true))
    }
}
