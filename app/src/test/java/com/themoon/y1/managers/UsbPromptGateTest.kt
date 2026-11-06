package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPromptGateTest {

    private val usbPlugged = 2 // BatteryManager.BATTERY_PLUGGED_USB

    @Test
    fun plugAsksOnce_functionSwitchesDoNotAskAgain() {
        val gate = UsbPromptGate()
        gate.onPower(cableOut = false, plugged = usbPlugged)
        assertTrue(gate.shouldAsk())
        gate.onAnswered() // Disk Mode
        assertFalse(gate.shouldAsk()) // the switch to mass storage
        gate.onPower(cableOut = false, plugged = usbPlugged)
        assertFalse(gate.shouldAsk()) // Eject: the switch back to MTP
    }

    @Test
    fun notNowOrBack_noQuestionOnTheNextConnect() {
        val gate = UsbPromptGate()
        assertTrue(gate.shouldAsk())
        gate.onAnswered()
        assertFalse(gate.shouldAsk())
        assertFalse(gate.shouldAsk())
    }

    @Test
    fun unplugThenReplug_asksAgain() {
        val gate = UsbPromptGate()
        gate.onAnswered()
        gate.onPower(cableOut = true, plugged = usbPlugged) // ACTION_POWER_DISCONNECTED before the battery update
        gate.onPower(cableOut = false, plugged = usbPlugged) // POWER_CONNECTED at the replug
        assertTrue(gate.shouldAsk())
    }

    @Test
    fun batteryWithoutPower_endsThePlug() {
        val gate = UsbPromptGate()
        gate.onAnswered()
        gate.onPower(cableOut = false, plugged = -1) // no plug data: no change
        assertFalse(gate.shouldAsk())
        gate.onPower(cableOut = false, plugged = 0) // a missed POWER_DISCONNECTED
        assertTrue(gate.shouldAsk())
    }

    @Test
    fun questionClosedWithoutAnswer_asksAgain() {
        val gate = UsbPromptGate()
        assertTrue(gate.shouldAsk())
        assertTrue(gate.shouldAsk())
    }
}
