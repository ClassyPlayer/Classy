package com.themoon.y1.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBounceTest {
    private companion object {
        const val ENTER = 66
        const val DPAD_CENTER = 23
    }

    @Test
    fun firstPressIsNotABounce() {
        val b = KeyBounce(bounceMs = 60)
        assertFalse(b.onPress(ENTER, 1_000))
        assertFalse(b.onRelease(ENTER, 1_100))
    }

    @Test
    fun pressSoonAfterReleaseIsDroppedWithItsRelease() {
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_020)
        assertTrue(b.onPress(ENTER, 1_040))
        assertTrue(b.onRelease(ENTER, 1_060))
    }

    @Test
    fun fastDoubleClickIsKept() {
        // Double-Click to Favorite: about 100 ms between the release and the second press
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_100)
        assertFalse(b.onPress(ENTER, 1_200))
        assertFalse(b.onRelease(ENTER, 1_300))
    }

    @Test
    fun pressAtTheLimitIsKept() {
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_100)
        assertFalse(b.onPress(ENTER, 1_160))
    }

    @Test
    fun heldBounceDropsItsRepeatsAndLongPress() {
        // One push gave a short contact, then the contact closed again and stayed closed
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_020)
        assertTrue(b.onPress(ENTER, 1_050))
        assertTrue(b.onRepeat(ENTER))
        assertTrue(b.onRelease(ENTER, 3_500))
    }

    @Test
    fun repeatsOfARealPressAreKept() {
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        assertFalse(b.onRepeat(ENTER))
        assertFalse(b.onRelease(ENTER, 2_000))
    }

    @Test
    fun chainOfBouncesIsDropped() {
        // Each dropped release starts a new bounce time: 20 ms contacts with short gaps are all one push
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_020)
        assertTrue(b.onPress(ENTER, 1_060))
        assertTrue(b.onRelease(ENTER, 1_080))
        assertTrue(b.onPress(ENTER, 1_120))
        assertTrue(b.onRelease(ENTER, 1_140))
        assertFalse(b.onPress(ENTER, 1_300))
    }

    @Test
    fun isBounceDoesNotChangeTheState() {
        // MainActivity asks before InputRouter gives the press: the press must still count as a bounce
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_020)
        assertTrue(b.isBounce(ENTER, 1_040))
        assertFalse(b.onRepeat(ENTER))
        assertTrue(b.onPress(ENTER, 1_040))
    }

    @Test
    fun pressBeforeTheLastReleaseIsKept() {
        // Times from another clock or device: no bounce without a positive gap
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_100)
        assertFalse(b.onPress(ENTER, 1_050))
    }

    @Test
    fun pressOfAnotherKeyIsKept() {
        // Enter of a keyboard, then the centre button: two keys, not a bounce
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_100)
        assertFalse(b.onPress(DPAD_CENTER, 1_120))
    }

    @Test
    fun eachKeyKeepsItsOwnReleaseTime() {
        // Enter released at 1020, the D-pad centre at 1030: an Enter press at 1040 is still a bounce of Enter
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_020)
        b.onPress(DPAD_CENTER, 1_005)
        b.onRelease(DPAD_CENTER, 1_030)
        assertTrue(b.onPress(ENTER, 1_040))
    }

    @Test
    fun pressOfAnotherKeyKeepsADroppedPressDropped() {
        val b = KeyBounce(bounceMs = 60)
        b.onPress(ENTER, 1_000)
        b.onRelease(ENTER, 1_020)
        assertTrue(b.onPress(ENTER, 1_040))
        assertFalse(b.onPress(DPAD_CENTER, 1_050))
        assertTrue(b.onRepeat(ENTER))
        assertTrue(b.onRelease(ENTER, 1_600))
    }
}
