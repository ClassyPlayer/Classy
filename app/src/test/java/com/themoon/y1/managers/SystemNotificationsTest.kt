package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemNotificationsTest {

    @Test
    fun hidesOnlyTheNotificationsAndThePanel() {
        assertEquals(0x000B0000, SystemNotifications.HIDE_FLAGS)
        // StatusBarManager.DISABLE_HOME, DISABLE_BACK, DISABLE_RECENT and DISABLE_NOTIFICATION_ALERTS stay off
        for (flag in listOf(0x00200000, 0x00400000, 0x01000000, 0x00040000)) {
            assertEquals(0, SystemNotifications.HIDE_FLAGS and flag)
        }
    }

    @Test
    fun hideAndShowSetTheFlags() {
        val calls = mutableListOf<Int>()
        val bar = SystemNotifications { calls += it }
        bar.hide()
        bar.show()
        assertEquals(listOf(SystemNotifications.HIDE_FLAGS, 0), calls)
    }

    @Test
    fun withoutThePermissionItStopsAfterTheFirstTry() {
        var calls = 0
        val bar = SystemNotifications {
            calls++
            throw SecurityException("needs android.permission.STATUS_BAR")
        }
        bar.hide()
        bar.show()
        bar.hide()
        assertEquals(1, calls)
    }

    @Test
    fun withoutAStatusBarManagerItDoesNothing() {
        val bar = SystemNotifications(null)
        bar.hide()
        bar.show()
    }
}
