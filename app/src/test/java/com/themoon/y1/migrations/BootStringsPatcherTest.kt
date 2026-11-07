package com.themoon.y1.migrations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootStringsPatcherTest {

    @Test
    fun testIsPatchedOnAlreadyPatchedApk() {
        val apk = File("../classy-boot-screen/framework-res.apk")
        if (!apk.exists()) {
            val localApk = File("classy-boot-screen/framework-res.apk")
            if (!localApk.exists()) return
            assertTrue(BootStringsPatcher.isPatched(localApk))
            return
        }
        assertTrue(BootStringsPatcher.isPatched(apk))
    }

    @Test
    fun testNonExistentFileReturnsFalse() {
        assertFalse(BootStringsPatcher.isPatched(File("non_existent_file.apk")))
        assertFalse(BootStringsPatcher.patchInPlace(File("non_existent_file.apk")))
    }
}
