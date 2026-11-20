package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRadioTest {
    @Test fun showsTheRealStateWhenNothingIsPending() {
        assertTrue(PendingRadio().shown(real = true, nowMs = 0))
        assertFalse(PendingRadio().shown(real = false, nowMs = 0))
    }

    @Test fun showsTheTargetWhileThePendingRequestIsUnderWay() {
        val p = PendingRadio().apply { set(true, 1_000) }
        assertTrue(p.shown(real = false, nowMs = 2_000))
    }

    @Test fun anotherStateOfTheRadioDoesNotEndTheRequest() {
        // Wi-Fi: off, then on again before it was off: the "off" broadcast is not the target
        val p = PendingRadio().apply { set(true, 0) }
        p.reached(false)
        assertTrue(p.shown(real = false, nowMs = 1_000))
    }

    @Test fun aReachedTargetIsDone() {
        val p = PendingRadio().apply { set(true, 0) }
        p.reached(true)
        assertFalse(p.shown(real = false, nowMs = 1_000)) // the real state shows again
    }

    @Test fun aRequestThatNeverArrivesExpires() {
        val p = PendingRadio().apply { set(true, 0) }
        assertFalse(p.shown(real = false, nowMs = PendingRadio.LIFE_MS + 1))
    }

    @Test fun clearGoesBackToTheRealState() {
        val p = PendingRadio().apply { set(true, 0) }
        p.clear()
        assertFalse(p.shown(real = false, nowMs = 1))
    }
}
