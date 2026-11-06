package com.themoon.y1.migrations

import com.themoon.y1.managers.UsbConnection
import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class Migration010ComputerConnectionTest {

    @Test
    fun mapsTheOldToggle() {
        assertEquals(UsbConnection.ASK, Migration010ComputerConnection.newValue(true))
        assertEquals(UsbConnection.MEDIA_DEVICE, Migration010ComputerConnection.newValue(false))
        assertNull(Migration010ComputerConnection.newValue(null))
    }

    @Test
    fun offBecomesMediaDeviceAndTheOldKeyGoes() {
        val prefs = FakePrefs(mutableMapOf("usb_storage_prompt" to false, "shuffle" to true))
        Migration010ComputerConnection.move(prefs)
        assertEquals(UsbConnection.MEDIA_DEVICE, prefs.getInt("usb_connection", -1))
        assertFalse(prefs.contains("usb_storage_prompt"))
        assertEquals(true, prefs.getBoolean("shuffle", false))
    }

    @Test
    fun onBecomesAsk() {
        val prefs = FakePrefs(mutableMapOf("usb_storage_prompt" to true))
        Migration010ComputerConnection.move(prefs)
        assertEquals(UsbConnection.ASK, prefs.getInt("usb_connection", -1))
        assertFalse(prefs.contains("usb_storage_prompt"))
    }

    @Test
    fun noOldKeyWritesNothing() {
        val prefs = FakePrefs()
        Migration010ComputerConnection.move(prefs)
        assertFalse(prefs.contains("usb_connection"))
    }

    @Test
    fun keepsAValueThatIsAlreadySet() {
        val prefs = FakePrefs(mutableMapOf("usb_storage_prompt" to false, "usb_connection" to UsbConnection.DISK_MODE))
        Migration010ComputerConnection.move(prefs)
        assertEquals(UsbConnection.DISK_MODE, prefs.getInt("usb_connection", -1))
        assertFalse(prefs.contains("usb_storage_prompt"))
    }

    @Test
    fun aPlugDoesWhatTheSettingSays() {
        assertEquals(UsbConnection.OnPlug.ASK, UsbConnection.onPlug(UsbConnection.ASK, true))
        assertEquals(UsbConnection.OnPlug.ASK, UsbConnection.onPlug(UsbConnection.ASK, null))
        assertEquals(UsbConnection.OnPlug.NOTHING, UsbConnection.onPlug(UsbConnection.ASK, false))
        assertEquals(UsbConnection.OnPlug.NOTHING, UsbConnection.onPlug(UsbConnection.MEDIA_DEVICE, true))
        assertEquals(UsbConnection.OnPlug.DISK_MODE, UsbConnection.onPlug(UsbConnection.DISK_MODE, true))
        assertEquals(UsbConnection.OnPlug.DISK_MODE, UsbConnection.onPlug(UsbConnection.DISK_MODE, false))
        assertEquals(UsbConnection.OnPlug.ASK, UsbConnection.onPlug(7, true))
    }
}
