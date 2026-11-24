package com.themoon.y1

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CardRootTest {

    private val sdcard0 = File("/storage/sdcard0")
    private val sdcard1 = File("/storage/sdcard1")

    @Test
    fun y1_cardIsThePrimaryStorage_evenWithInternalMemoryAtSdcard1() {
        assertEquals(sdcard0, StoragePaths.chooseCardRoot(sdcard0, sdcard1, sdcard0))
    }

    @Test
    fun y2_emulatedPrimary_usesTheCardAtSdcard1() {
        assertEquals(sdcard1, StoragePaths.chooseCardRoot(null, sdcard1, File("/storage/emulated/legacy")))
    }

    @Test
    fun noCard_usesThePrimaryStorage() {
        assertEquals(sdcard0, StoragePaths.chooseCardRoot(null, null, sdcard0))
    }

    @Test
    fun y1WithoutCard_usesInternalMemoryAtSdcard1() {
        // The card volume is not mounted, so it is not offered as the removable primary.
        assertEquals(sdcard1, StoragePaths.chooseCardRoot(null, sdcard1, sdcard0))
    }
}
