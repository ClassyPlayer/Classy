package com.themoon.y1.controllers.settings

import com.themoon.y1.controllers.settings.BrightnessController.Companion.fastStepped
import com.themoon.y1.controllers.settings.BrightnessController.Companion.level
import com.themoon.y1.controllers.settings.BrightnessController.Companion.percent
import com.themoon.y1.controllers.settings.BrightnessController.Companion.stepped
import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessStepTest {
    @Test fun theFullRangeIsZeroToOneHundredPercent() {
        assertEquals(0, percent(0))
        assertEquals(100, percent(255))
        assertEquals(0, level(0))
        assertEquals(255, level(100))
    }

    @Test fun eachPercentIsOneLevelAndBack() {
        for (p in 0..100) assertEquals(p, percent(level(p)))
    }

    @Test fun eachStepChangesTheShownPercentByOne() {
        for (l in 0..255) {
            if (percent(l) < 100) assertEquals(percent(l) + 1, percent(stepped(l, 1)))
            if (percent(l) > 0) assertEquals(percent(l) - 1, percent(stepped(l, -1)))
        }
    }

    @Test fun theLevelStaysFromTheLowestLevelTo255() {
        assertEquals(255, stepped(255, 1))
        assertEquals(0, stepped(0, -1))
        assertEquals(255, stepped(250, 5))
        assertEquals(255, fastStepped(255, 1))
        assertEquals(0, fastStepped(0, -1))
    }

    @Test fun aFastStepGoesToTheNextMultipleOfFive() {
        assertEquals(10, percent(fastStepped(level(7), 1)))
        assertEquals(15, percent(fastStepped(level(10), 1)))
        assertEquals(5, percent(fastStepped(level(7), -1)))
        assertEquals(5, percent(fastStepped(level(10), -1)))
        assertEquals(0, percent(fastStepped(level(3), -1)))
        assertEquals(100, percent(fastStepped(level(97), 1)))
    }
}
