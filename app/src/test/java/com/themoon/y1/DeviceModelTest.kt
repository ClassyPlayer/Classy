package com.themoon.y1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModelTest {

    @Test
    fun models_matchOnlyTheirOwnName() {
        assertTrue(DeviceModel.isY1("Y1"))
        assertTrue(DeviceModel.isY1("Y1 "))
        assertFalse(DeviceModel.isY1("Y2"))
        assertFalse(DeviceModel.isY1(null))
        assertTrue(DeviceModel.isY2("Y2"))
        assertFalse(DeviceModel.isY2("Y1"))
    }
}
