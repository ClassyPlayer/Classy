package com.themoon.y1.games

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelSensorTest {
    private fun mask(text: String): Int {
        val bytes = text.toByteArray()
        return WheelSensor.parseMask(bytes, bytes.size)
    }

    @Test
    fun readsTheMaskWithWhiteSpace() {
        assertEquals(0, mask("0\n"))
        assertEquals(12345, mask("12345\n"))
        assertEquals(7, mask("  7 \r\n"))
    }

    @Test
    fun anythingElseIsNoFinger() {
        assertEquals(0, mask(""))
        assertEquals(0, mask("Not in test mode\n"))
        assertEquals(0, mask("12 34"))
        assertEquals(0, mask("-5"))
        assertEquals(0, mask("99999999999"))
    }

    @Test
    fun readsOnlyTheGivenLength() {
        val bytes = "42\nXYZ".toByteArray()
        assertEquals(42, WheelSensor.parseMask(bytes, 3))
    }
}
