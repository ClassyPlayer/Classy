package com.themoon.y1.managers

import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import com.themoon.y1.managers.RadioSwitch.BluetoothStep
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioSwitchTest {
    @Test fun wifiShowsWaitWhileItStartsOrStops() {
        assertEquals(true, RadioSwitch.wifiShown(WifiManager.WIFI_STATE_ENABLED))
        assertEquals(false, RadioSwitch.wifiShown(WifiManager.WIFI_STATE_DISABLED))
        assertEquals(false, RadioSwitch.wifiShown(WifiManager.WIFI_STATE_UNKNOWN))
        assertEquals(null, RadioSwitch.wifiShown(WifiManager.WIFI_STATE_ENABLING))
        assertEquals(null, RadioSwitch.wifiShown(WifiManager.WIFI_STATE_DISABLING))
    }

    @Test fun bluetoothShowsWaitWhileItStartsOrStops() {
        assertEquals(true, RadioSwitch.bluetoothShown(BluetoothAdapter.STATE_ON, starting = false))
        assertEquals(false, RadioSwitch.bluetoothShown(BluetoothAdapter.STATE_OFF, starting = false))
        assertEquals(null, RadioSwitch.bluetoothShown(BluetoothAdapter.STATE_TURNING_ON, starting = false))
        assertEquals(null, RadioSwitch.bluetoothShown(BluetoothAdapter.STATE_TURNING_OFF, starting = false))
        // The Y2 Bluetooth service starts before enable(): the adapter is still off
        assertEquals(null, RadioSwitch.bluetoothShown(BluetoothAdapter.STATE_OFF, starting = true))
    }

    @Test fun bluetoothStartsOrStopsOnlyFromOnOrOff() {
        assertEquals(BluetoothStep.START, RadioSwitch.bluetoothStep(true, BluetoothAdapter.STATE_OFF, starting = false))
        assertEquals(BluetoothStep.STOP, RadioSwitch.bluetoothStep(false, BluetoothAdapter.STATE_ON, starting = false))
        assertEquals(BluetoothStep.NOTHING, RadioSwitch.bluetoothStep(true, BluetoothAdapter.STATE_ON, starting = false))
        assertEquals(BluetoothStep.NOTHING, RadioSwitch.bluetoothStep(false, BluetoothAdapter.STATE_OFF, starting = false))
    }

    @Test fun aPressWhileBluetoothStartsOrStopsWaits() {
        assertEquals(BluetoothStep.BUSY, RadioSwitch.bluetoothStep(true, BluetoothAdapter.STATE_OFF, starting = true))
        assertEquals(BluetoothStep.BUSY, RadioSwitch.bluetoothStep(false, BluetoothAdapter.STATE_OFF, starting = true))
        assertEquals(BluetoothStep.BUSY, RadioSwitch.bluetoothStep(false, BluetoothAdapter.STATE_TURNING_ON, starting = false))
        assertEquals(BluetoothStep.BUSY, RadioSwitch.bluetoothStep(true, BluetoothAdapter.STATE_TURNING_OFF, starting = false))
    }
}
