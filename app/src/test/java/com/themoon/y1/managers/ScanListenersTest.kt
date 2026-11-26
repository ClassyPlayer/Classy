package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertTrue(scans.join(old))
        // MainActivity made again during the scan: no second scan, but it gets the running scan's events
        assertFalse(scans.join(new))
        assertTrue(scans.isRunning)
        for (l in scans.current()) l.onProgress(1, 2)
        for (l in scans.finish()) l.onComplete(5, 0)
        assertEquals(listOf("progress 1/2", "complete 5"), new.events)
        assertEquals(listOf("progress 1/2", "complete 5"), old.events)
        assertFalse(scans.isRunning)
    }

    @Test
    fun aRequestAfterTheEndStartsANewScanWithOnlyItsListener() {
        val scans = ScanListeners()
        val first = Rec()
        scans.join(first)
        scans.finish()
        val second = Rec()
        assertTrue(scans.join(second))
        assertEquals(listOf(second), scans.current())
        // A second finish (the scan thread's finally) has no listeners left to tell
        scans.finish()
        assertTrue(scans.finish().isEmpty())
    }
}
