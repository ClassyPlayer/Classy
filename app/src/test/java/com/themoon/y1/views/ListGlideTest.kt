package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListGlideTest {

    @Test
    fun approach_movesPartOfTheWay() {
        val v = ListGlide.approach(0f, 100f, ListGlide.BAR_RATE, 1f / 60)
        assertTrue(v > 20f && v < 50f)
    }

    @Test
    fun approach_isAboutThereAfterThreeOverRate() {
        var v = 0f
        val frames = (3f / ListGlide.BAR_RATE * 60).toInt()
        repeat(frames) { v = ListGlide.approach(v, 100f, ListGlide.BAR_RATE, 1f / 60) }
        assertTrue(v > 93f)
    }

    @Test
    fun approach_endsAtTheTargetWhenNear() {
        assertEquals(100f, ListGlide.approach(99.7f, 100f, ListGlide.SCROLL_RATE, 1f / 60), 0f)
        assertEquals(0f, ListGlide.approach(0f, 0f, ListGlide.SCROLL_RATE, 1f / 60), 0f)
    }

    @Test
    fun approach_sameAtAnyFrameRate() {
        var at60 = 0f
        repeat(6) { at60 = ListGlide.approach(at60, 100f, ListGlide.SCROLL_RATE, 1f / 60) }
        var at30 = 0f
        repeat(3) { at30 = ListGlide.approach(at30, 100f, ListGlide.SCROLL_RATE, 1f / 30) }
        assertEquals(at60, at30, 0.01f)
    }

    @Test
    fun limit_keepsTheLagShort() {
        assertEquals(60f, ListGlide.limit(10f, 100f, 40f), 0f)
        assertEquals(140f, ListGlide.limit(200f, 100f, 40f), 0f)
        assertEquals(90f, ListGlide.limit(90f, 100f, 40f), 0f)
    }

    @Test
    fun shift_fromARowInBothLists() {
        // One step down: the list scrolled up by 20 px, row 3 left the top, row 9 came in
        val shift = ListGlide.shift(intArrayOf(3, 4, 5), intArrayOf(-10, 30, 70), intArrayOf(4, 5, 9), intArrayOf(10, 50, 330))
        assertEquals(-20, shift)
    }

    @Test
    fun shift_noRowInBoth_isNull() {
        assertNull(ListGlide.shift(intArrayOf(0, 1), intArrayOf(0, 40), intArrayOf(50, 51), intArrayOf(0, 40)))
    }

    @Test
    fun pickPictureTakesTheSameSizeFirst() {
        val w = intArrayOf(480, 480, 480)
        val h = intArrayOf(40, 64, 80)
        val bytes = intArrayOf(480 * 40 * 4, 480 * 64 * 4, 480 * 80 * 4)
        assertEquals(1, ListGlide.pickPicture(w, h, bytes, 480, 64, canResize = true))
    }

    @Test
    fun pickPictureResizesTheSmallestThatHasRoom() {
        val w = intArrayOf(480, 480, 480)
        val h = intArrayOf(40, 100, 80)
        val bytes = intArrayOf(480 * 40 * 4, 480 * 100 * 4, 480 * 80 * 4)
        assertEquals(2, ListGlide.pickPicture(w, h, bytes, 480, 64, canResize = true))
        assertEquals(-1, ListGlide.pickPicture(w, h, bytes, 480, 64, canResize = false))
        assertEquals(-1, ListGlide.pickPicture(w, h, bytes, 480, 120, canResize = true))
    }

    @Test
    fun arrowTop_onTheRow_isTheTopInTheRow() {
        assertEquals(12, ListGlide.arrowTop(12, 16, 40, 40))
        assertEquals(0, ListGlide.arrowTop(0, 40, 40, 40))
    }

    @Test
    fun arrowTop_keepsTheCentreInAnotherHeight() {
        // A centred arrow stays centred: (60 - 16) / 2
        assertEquals(22, ListGlide.arrowTop(12, 16, 40, 60))
        // An arrow as high as the row: its centre is the centre of the highlight
        assertEquals(10, ListGlide.arrowTop(0, 40, 40, 60))
    }

    @Test
    fun arrowTop_noRowHeight_isTheTopInTheRow() {
        assertEquals(12, ListGlide.arrowTop(12, 16, 0, 60))
    }

    @Test
    fun arrowAlpha_fullOnRowsWithAnArrow() {
        assertEquals(255, ListGlide.arrowAlpha(40, 40))
        // A highlight between two rows that both have an arrow
        assertEquals(255, ListGlide.arrowAlpha(15 + 25, 15 + 25))
    }

    @Test
    fun arrowAlpha_fadesBetweenARowWithAndARowWithout() {
        assertEquals(64, ListGlide.arrowAlpha(10, 40))
        assertEquals(128, ListGlide.arrowAlpha(20, 40))
        assertEquals(0, ListGlide.arrowAlpha(0, 40))
    }

    @Test
    fun arrowAlpha_noRowWithAPicture_isZero() {
        assertEquals(0, ListGlide.arrowAlpha(0, 0))
    }
}
