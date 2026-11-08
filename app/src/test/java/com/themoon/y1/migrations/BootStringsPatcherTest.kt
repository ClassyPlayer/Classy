package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootStringsPatcherTest {

    @Test
    fun testWantedReplacements() {
        val pairs = BootStringsPatcher.WANTED_REPLACEMENTS
        assertEquals(4, pairs.size)
        assertEquals("Android is upgrading\u2026" to "Classy is starting\u2026", pairs[0])
        assertEquals("Optimizing app %1\$d of %2\$d." to "Updating %1\$d of %2\$d", pairs[1])
        assertEquals("Starting apps." to "Starting up\u2026", pairs[2])
        assertEquals("Finishing boot." to "Finishing up\u2026", pairs[3])
    }

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
