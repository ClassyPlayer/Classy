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
    fun testIsPatchedPositiveAndNegativeFixtures() {
        val patchedZip = File.createTempFile("test_patched", ".apk")
        val stockZip = File.createTempFile("test_stock", ".apk")
        try {
            writeMockApk(patchedZip, "dummy_prefix Classy is starting… dummy_suffix".toByteArray(Charsets.UTF_8))
            assertTrue(BootStringsPatcher.isPatched(patchedZip))

            writeMockApk(stockZip, "dummy_prefix Android is upgrading… dummy_suffix".toByteArray(Charsets.UTF_8))
            assertFalse(BootStringsPatcher.isPatched(stockZip))
        } finally {
            patchedZip.delete()
            stockZip.delete()
        }
    }

    private fun writeMockApk(file: File, arscContent: ByteArray) {
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("resources.arsc"))
            zos.write(arscContent)
            zos.closeEntry()
        }
    }

    @Test
    fun testNonExistentFileReturnsFalse() {
        assertFalse(BootStringsPatcher.isPatched(File("non_existent_file.apk")))
        assertFalse(BootStringsPatcher.patchInPlace(File("non_existent_file.apk")))
    }
}
