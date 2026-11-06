package com.themoon.y1.migrations

import com.themoon.y1.managers.UsbDrive
import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration010UsbDriveTest {

    @Test
    fun mapsTheOldKeys() {
        assertEquals(UsbDrive.ASK, Migration010UsbDrive.newValue(true, null))
        assertEquals(UsbDrive.NEVER, Migration010UsbDrive.newValue(false, null))
        assertEquals(UsbDrive.ASK, Migration010UsbDrive.newValue(null, 0))
        assertEquals(UsbDrive.NEVER, Migration010UsbDrive.newValue(null, 1))
        assertEquals(UsbDrive.ALWAYS, Migration010UsbDrive.newValue(null, 2))
        // The newer key first; an unknown value of it uses the toggle
        assertEquals(UsbDrive.ALWAYS, Migration010UsbDrive.newValue(true, 2))
        assertEquals(UsbDrive.NEVER, Migration010UsbDrive.newValue(false, 9))
        assertNull(Migration010UsbDrive.newValue(null, 9))
        assertNull(Migration010UsbDrive.newValue(null, null))
    }

    @Test
    fun offBecomesNeverAndTheOldKeyGoes() {
        val prefs = FakePrefs(mutableMapOf("usb_storage_prompt" to false, "shuffle" to true))
        Migration010UsbDrive.move(prefs)
        assertEquals(UsbDrive.NEVER, prefs.getInt("usb_drive", -1))
        assertFalse(prefs.contains("usb_storage_prompt"))
        assertEquals(true, prefs.getBoolean("shuffle", false))
    }

    @Test
    fun onBecomesAsk() {
        val prefs = FakePrefs(mutableMapOf("usb_storage_prompt" to true))
        Migration010UsbDrive.move(prefs)
        assertEquals(UsbDrive.ASK, prefs.getInt("usb_drive", -1))
        assertFalse(prefs.contains("usb_storage_prompt"))
    }

    @Test
    fun theFirstVersionKeyMovesAndGoes() {
        for ((old, new) in listOf(0 to UsbDrive.ASK, 1 to UsbDrive.NEVER, 2 to UsbDrive.ALWAYS)) {
            val prefs = FakePrefs(mutableMapOf("usb_connection" to old))
            Migration010UsbDrive.move(prefs)
            assertEquals(new, prefs.getInt("usb_drive", -1))
            assertFalse(prefs.contains("usb_connection"))
        }
    }

    @Test
    fun noOldKeyWritesNothing() {
        val prefs = FakePrefs()
        Migration010UsbDrive.move(prefs)
        assertFalse(prefs.contains("usb_drive"))
    }

    @Test
    fun keepsAValueThatIsAlreadySet() {
        val prefs = FakePrefs(mutableMapOf("usb_storage_prompt" to false, "usb_connection" to 0, "usb_drive" to UsbDrive.ALWAYS))
        Migration010UsbDrive.move(prefs)
        assertEquals(UsbDrive.ALWAYS, prefs.getInt("usb_drive", -1))
        assertFalse(prefs.contains("usb_storage_prompt"))
        assertFalse(prefs.contains("usb_connection"))
    }

    @Test
    fun aPlugDoesWhatTheSettingSays() {
        assertEquals(UsbDrive.OnPlug.ASK, UsbDrive.onPlug(UsbDrive.ASK, true))
        assertEquals(UsbDrive.OnPlug.ASK, UsbDrive.onPlug(UsbDrive.ASK, null))
        // Ask without a card: nothing
        assertEquals(UsbDrive.OnPlug.NOTHING, UsbDrive.onPlug(UsbDrive.ASK, false))
        assertEquals(UsbDrive.OnPlug.NOTHING, UsbDrive.onPlug(UsbDrive.NEVER, true))
        assertEquals(UsbDrive.OnPlug.LEND, UsbDrive.onPlug(UsbDrive.ALWAYS, true))
        // Always without a card: the share gives the notice No microSD card.
        assertEquals(UsbDrive.OnPlug.LEND, UsbDrive.onPlug(UsbDrive.ALWAYS, false))
        assertEquals(UsbDrive.OnPlug.ASK, UsbDrive.onPlug(7, true))
    }

    @Test
    fun theQuestionSaysMusicWillStopOnlyForMusicFromTheCardThatPlays() {
        val card = "/storage/sdcard1"
        assertTrue(UsbDrive.musicStops(true, "/storage/sdcard1/Music/a.mp3", card))
        assertFalse(UsbDrive.musicStops(false, "/storage/sdcard1/Music/a.mp3", card))
        assertFalse(UsbDrive.musicStops(true, "/storage/sdcard0/Music/a.mp3", card))
        assertFalse(UsbDrive.musicStops(true, "/storage/sdcard10/a.mp3", card))
        assertFalse(UsbDrive.musicStops(true, null, card))
    }

    @Test
    fun musicPlaysAgainOnlyAfterEject() {
        assertTrue(UsbDrive.resumesMusic(UsbDrive.End.EJECT))
        assertFalse(UsbDrive.resumesMusic(UsbDrive.End.CABLE_OUT))
        assertFalse(UsbDrive.resumesMusic(UsbDrive.End.CARD_REMOVED))
    }
}
