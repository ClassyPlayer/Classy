package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order of the tags of the release workflow, as the installed app compares them (also the apps already out). */
class OtaVersionCompareTest {

    /** The tags of one day and the next, oldest first. One build counter per day, shared by stable and beta. */
    private val ordered = listOf(
        "20261113-beta",
        "20261113-v2",
        "20261114",
        "20261114-beta.2",
        "20261114-v3",
        "20261114-beta.4",
        "20261115-beta",
    )

    @Test
    fun releaseTags_compareInReleaseOrder() {
        for (i in ordered.indices) for (j in ordered.indices) {
            val expected = i.compareTo(j).coerceIn(-1, 1)
            assertEquals("${ordered[i]} vs ${ordered[j]}", expected, OtaUpdateManager.compareVersionStrings(ordered[i], ordered[j]))
        }
    }

    @Test
    fun dateVersions_beatOldNames() {
        assertEquals(1, OtaUpdateManager.compareVersionStrings("20261114-beta", "v1.5.0"))
        assertEquals(-1, OtaUpdateManager.compareVersionStrings("y1-exp.2", "20261112"))
        assertEquals(0, OtaUpdateManager.compareVersionStrings("v20261114", "20261114"))
    }

    @Test
    fun isServerVersionNewer_usesTheNameThenTheCode() {
        assertTrue(OtaUpdateManager.isServerVersionNewer(2026111402, "20261114-beta.2", 2026111400, "20261114"))
        assertFalse(OtaUpdateManager.isServerVersionNewer(2026111400, "20261114", 2026111402, "20261114-beta.2"))
        assertFalse(OtaUpdateManager.isServerVersionNewer(2026111400, "20261114", 2026111400, "20261114"))
        // An old 8-digit code against a 10-digit code of the same day: equal
        assertFalse(OtaUpdateManager.isServerVersionNewer(2026111400, "20261114", 20261114, "20261114"))
    }
}
