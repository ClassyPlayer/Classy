package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerMathTest {
    @Test fun slowTurnsMoveSingleMinutesFastTurnsBiggerSteps() {
        assertEquals(1, SleepTimerMath.wheelStep(-1))
        assertEquals(1, SleepTimerMath.wheelStep(400))
        assertEquals(2, SleepTimerMath.wheelStep(150))
        assertEquals(5, SleepTimerMath.wheelStep(100))
        assertEquals(10, SleepTimerMath.wheelStep(60))
        assertEquals(15, SleepTimerMath.wheelStep(20))
    }

    @Test fun aBigStepLandsOnItsNextMultipleInEitherDirection() {
        assertEquals(31, SleepTimerMath.stepped(30, 1, 1))
        assertEquals(29, SleepTimerMath.stepped(30, -1, 1))
        assertEquals(40, SleepTimerMath.stepped(37, 1, 5))
        assertEquals(35, SleepTimerMath.stepped(37, -1, 5))
        assertEquals(45, SleepTimerMath.stepped(40, 1, 5))
        assertEquals(30, SleepTimerMath.stepped(45, -1, 15))
        assertEquals(15, SleepTimerMath.stepped(29, -1, 15))
    }

    @Test fun theTimeStaysBetweenOneMinuteAndADay() {
        assertEquals(1, SleepTimerMath.stepped(1, -1, 1))
        assertEquals(1, SleepTimerMath.stepped(10, -1, 15))
        assertEquals(1440, SleepTimerMath.stepped(1440, 1, 1))
        assertEquals(1440, SleepTimerMath.stepped(1435, 1, 15))
        assertEquals(1, SleepTimerMath.clamp(0))
        assertEquals(1440, SleepTimerMath.clamp(5000))
    }

    @Test fun durationTextHasHoursFromAnHourOnAndNoZeroMinutes() {
        val min = "%d min"
        val h = "%d h"
        val hm = "%d h %02d min"
        assertEquals("45 min", SleepTimerMath.durationText(45, min, h, hm))
        assertEquals("59 min", SleepTimerMath.durationText(59, min, h, hm))
        assertEquals("1 h", SleepTimerMath.durationText(60, min, h, hm))
        assertEquals("1 h 05 min", SleepTimerMath.durationText(65, min, h, hm))
        assertEquals("24 h", SleepTimerMath.durationText(1440, min, h, hm))
    }

    @Test fun theLastCustomTimeIsOnTopUnlessItIsAPreset() {
        val presets = SleepTimerMath.PRESETS.toList()
        assertEquals(presets, SleepTimerMath.listMinutes(0))
        assertEquals(listOf(37) + presets, SleepTimerMath.listMinutes(37))
        assertEquals(presets, SleepTimerMath.listMinutes(30))
        assertEquals(presets, SleepTimerMath.listMinutes(5000))
    }

    @Test fun theCustomScreenStartsAtTheTimeLeftThenTheLastCustomTime() {
        assertEquals(42, SleepTimerMath.customStart(42, 90))
        assertEquals(90, SleepTimerMath.customStart(0, 90))
        assertEquals(SleepTimerMath.DEFAULT_CUSTOM_MINUTES, SleepTimerMath.customStart(0, 0))
    }

    @Test fun minutesLeftAreRoundedUp() {
        assertEquals(0, SleepTimerMath.minutesLeft(1_000, 1_000))
        assertEquals(1, SleepTimerMath.minutesLeft(1_000, 999))
        assertEquals(1, SleepTimerMath.minutesLeft(60_000, 0))
        assertEquals(2, SleepTimerMath.minutesLeft(60_001, 0))
        assertEquals(1L, SleepTimerMath.msToNextMinute(60_001, 0))
        assertEquals(60_000L, SleepTimerMath.msToNextMinute(120_000, 0))
        assertEquals(30_000L, SleepTimerMath.msToNextMinute(90_000, 0))
    }

    @Test fun theFadeGoesFromFullToSilent() {
        assertEquals(1f, SleepTimerMath.fadeFactor(SleepTimerMath.FADE_MS), 0f)
        assertEquals(1f, SleepTimerMath.fadeFactor(60_000), 0f)
        assertEquals(0.25f, SleepTimerMath.fadeFactor(SleepTimerMath.FADE_MS / 2), 0.001f)
        assertEquals(0f, SleepTimerMath.fadeFactor(0), 0f)
        assertEquals(0f, SleepTimerMath.fadeFactor(-5), 0f)
    }
}
