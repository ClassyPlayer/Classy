package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class RootFileInstallTest {

    @Test
    fun lines_copyToAStagedFileThenRename() {
        // The same lines as the boot screen migration wrote before (BootScreenMigrator)
        assertEquals(
            listOf(
                "cp /cache/a.jar /system/framework/android.policy.jar.classy_tmp",
                "chmod 644 /system/framework/android.policy.jar.classy_tmp",
                "chown root:root /system/framework/android.policy.jar.classy_tmp",
                "mv /system/framework/android.policy.jar.classy_tmp /system/framework/android.policy.jar",
            ),
            RootFileInstall.lines("/cache/a.jar", "/system/framework/android.policy.jar")
        )
    }
}
