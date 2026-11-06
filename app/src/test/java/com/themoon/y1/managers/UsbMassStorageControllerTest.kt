package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMassStorageControllerTest {

    @Test
    fun testParseBool_trueParcel() {
        val output = "Result: Parcel(00000000 00000001   '........')"
        assertTrue(UsbMassStorageController.parseBool(output))
    }

    @Test
    fun testParseBool_falseParcel() {
        val output = "Result: Parcel(00000000 00000000   '........')"
        assertFalse(UsbMassStorageController.parseBool(output))
    }

    @Test
    fun testParseBool_nullOrMalformed() {
        assertFalse(UsbMassStorageController.parseBool(null))
        assertFalse(UsbMassStorageController.parseBool(""))
        assertFalse(UsbMassStorageController.parseBool("Error: something went wrong"))
    }

    @Test
    fun testFunctionsToRestore_mtpAdbDropsAdb() {
        assertEquals("mtp", UsbMassStorageController.functionsToRestore("mtp,adb"))
        assertEquals("mtp", UsbMassStorageController.functionsToRestore("mtp"))
        assertEquals("mtp,acm", UsbMassStorageController.functionsToRestore(" mtp , adb ,acm"))
    }

    @Test
    fun testFunctionsToRestore_massStorageNeedsNoSwitch() {
        assertNull(UsbMassStorageController.functionsToRestore("mass_storage,adb"))
        assertNull(UsbMassStorageController.functionsToRestore("mass_storage"))
    }

    private val internal = UsbMassStorageController.Volume("/storage/emulated/0", false, true, "mounted")
    private fun card(state: String?) = UsbMassStorageController.Volume("/storage/sdcard1", true, false, state)
    private val usbOtg = UsbMassStorageController.Volume("/storage/usbotg", false, false, "removed")

    @Test
    fun testCanShare_y2WithoutCardHasNothingToShare() {
        // The Y2 volume list (dumpsys mount) with no card: internal is emulated and does not allow mass storage.
        assertFalse(UsbMassStorageController.canShare(listOf(internal, card("removed"), usbOtg)))
        assertFalse(UsbMassStorageController.canShare(listOf(internal, card("unmounted"), usbOtg)))
        assertFalse(UsbMassStorageController.canShare(listOf(internal, card("checking"), usbOtg)))
        assertFalse(UsbMassStorageController.canShare(listOf(internal, card(null), usbOtg)))
        assertFalse(UsbMassStorageController.canShare(emptyList()))
    }

    @Test
    fun testCanShare_y2WithMountedOrSharedCard() {
        assertTrue(UsbMassStorageController.canShare(listOf(internal, card("mounted"), usbOtg)))
        assertTrue(UsbMassStorageController.canShare(listOf(internal, card("shared"), usbOtg)))
    }

    @Test
    fun testCanShare_physicalPrimaryThatAllowsMassStorage() {
        val primary = UsbMassStorageController.Volume("/storage/sdcard0", true, false, "mounted")
        assertTrue(UsbMassStorageController.canShare(listOf(primary)))
        assertFalse(UsbMassStorageController.canShare(listOf(primary.copy(emulated = true))))
    }

    @Test
    fun testFunctionsAfterEject_savedValueWins() {
        assertEquals("mtp", UsbMassStorageController.functionsAfterEject("mtp", "mass_storage,adb", "mtp,adb"))
        assertEquals("", UsbMassStorageController.functionsAfterEject("", "mass_storage,adb", "mtp,adb"))
    }

    @Test
    fun testFunctionsAfterEject_nothingSavedAfterRestartGoesBackToDefault() {
        // The app was killed during the share: the Y2 default is mtp,adb, but mass_storage is still on.
        assertEquals("", UsbMassStorageController.functionsAfterEject(null, "mass_storage,adb", "mtp,adb"))
        assertEquals("", UsbMassStorageController.functionsAfterEject(null, "mass_storage", "mtp"))
    }

    @Test
    fun testFunctionsAfterEject_nothingSavedAndNothingToChange() {
        // mass_storage is the default (for example the Solar ROM): keep it.
        assertNull(UsbMassStorageController.functionsAfterEject(null, "mass_storage,adb", "mass_storage,adb"))
        // mass_storage is not on: no switch.
        assertNull(UsbMassStorageController.functionsAfterEject(null, "mtp,adb", "mtp,adb"))
        assertNull(UsbMassStorageController.functionsAfterEject(null, null, null))
    }

    @Test
    fun testFunctionsToRestore_unknownUsesDefault() {
        assertEquals("", UsbMassStorageController.functionsToRestore(""))
        assertEquals("", UsbMassStorageController.functionsToRestore(null))
        assertEquals("", UsbMassStorageController.functionsToRestore("adb"))
        assertEquals("", UsbMassStorageController.functionsToRestore("none"))
    }
}
