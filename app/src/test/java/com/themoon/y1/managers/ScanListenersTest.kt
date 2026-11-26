package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ScanListenersTest {
    private class Rec : MediaLibraryManager.ScanProgressListener {
        val events = ArrayList<String>()
        override fun onProgress(scanned: Int, total: Int) { events.add("progress $scanned/$total") }
        override fun onComplete(musicCount: Int, booksCount: Int) { events.add("complete $musicCount") }
    }

    @Test
    fun aRequestDuringAScanJoinsIt() {
        val scans = ScanListeners()
        val old = Rec()
        val new = Rec()
        val id = scans.join(old)!!
        // MainActivity made again during the scan: no second scan, but it gets the running scan's events
        assertNull(scans.join(new))
        assertTrue(scans.isRunning)
        for (l in scans.current()) l.onProgress(1, 2)
        for (l in scans.finish(id)) l.onComplete(5, 0)
        assertEquals(listOf("progress 1/2", "complete 5"), new.events)
        assertEquals(listOf("progress 1/2", "complete 5"), old.events)
        assertFalse(scans.isRunning)
    }

    @Test
    fun theEndOfAnOldScanDoesNotEndANewerScan() {
        val scans = ScanListeners()
        val first = scans.join(Rec())!!
        scans.finish(first) // onComplete of the first scan
        val next = Rec()
        val second = scans.join(next)
        assertNotNull(second)
        // The first scan's finally runs after the second scan started
        assertTrue(scans.finish(first).isEmpty())
        assertTrue(scans.isRunning)
        assertEquals(listOf(next), scans.finish(second!!))
        assertFalse(scans.isRunning)
        // The second scan's finally: nothing left to tell
        assertTrue(scans.finish(second).isEmpty())
    }
}
