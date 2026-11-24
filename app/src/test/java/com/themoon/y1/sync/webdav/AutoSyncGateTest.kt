package com.themoon.y1.sync.webdav

import org.junit.Assert.*
import org.junit.Test

class AutoSyncGateTest {

    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour

    private fun run(
        enabled: Boolean = true, configured: Boolean = true, wifi: Boolean = true,
        charging: Boolean = true, running: Boolean = false, last: Long = 0
    ) = AutoSyncGate.shouldRun(enabled, configured, wifi, charging, running, now, last)

    @Test
    fun disabledNeverRuns() {
        assertFalse(run(enabled = false))
        assertFalse(run(configured = false))
    }

    @Test
    fun needsWifiAndCharging() {
        assertTrue(run())
        assertFalse(run(wifi = false))
        assertFalse(run(charging = false))
    }

    @Test
    fun notWhileRunning() {
        assertFalse(run(running = true))
    }

    @Test
    fun waitsSixtyMinutes() {
        assertFalse(run(last = now - 59 * 60 * 1000L))
        assertTrue(run(last = now - hour))
    }

    @Test
    fun aClockSetBackDoesNotBlockItForever() {
        assertTrue(run(last = now + 5 * hour))
    }

    @Test
    fun aTimeInTheFutureDoesNotCount() {
        // The clock was set back: the stored time is ahead. The first try is allowed, then its own time throttles
        assertEquals(0L, AutoSyncGate.lastCounted(now, now + hour, now + 2 * hour))
        assertEquals(now - hour / 2, AutoSyncGate.lastCounted(now, now + hour, now - hour / 2))
        assertEquals(now - hour, AutoSyncGate.lastCounted(now, now - hour, now - 2 * hour))
    }
}
