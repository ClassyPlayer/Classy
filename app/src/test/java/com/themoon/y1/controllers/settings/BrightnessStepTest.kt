package com.themoon.y1.controllers.settings

import com.themoon.y1.controllers.settings.BrightnessController.Companion.MIN_LEVEL
import com.themoon.y1.controllers.settings.BrightnessController.Companion.level
import com.themoon.y1.controllers.settings.BrightnessController.Companion.percent
import com.themoon.y1.controllers.settings.BrightnessController.Companion.stepped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessStepTest {
    @Test fun eachPercentIsOneLevelAndBack() {
        for (p in 0..100) assertEquals(p, percent(level(p)))
        assertEquals(100, percent(255))
        assertEquals(4, percent(MIN_LEVEL))
    }

    @Test fun eachStepChangesTheShownPercentByOne() {
        for (l in MIN_LEVEL..255) {
            if (percent(l) < 100) assertEquals(percent(l) + 1, percent(stepped(l, 1)))
            if (percent(l) > percent(MIN_LEVEL)) assertEquals(percent(l) - 1, percent(stepped(l, -1)))
        }
    }

    @Test fun theLevelStaysFromTheLowestLevelTo255() {
        assertEquals(255, stepped(255, 1))
        assertEquals(MIN_LEVEL, stepped(MIN_LEVEL, -1))
        assertEquals(MIN_LEVEL, stepped(11, -1))
        assertTrue(stepped(254, 5) == 255)
    }
}
