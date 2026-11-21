package com.themoon.y1

import com.themoon.y1.ClassyApplication.RunningProcess
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleProcessTest {

    @Test
    fun onlyOtherProcessesWithOurNameAndUserAreStale() {
        val running = listOf(
            RunningProcess(100, 10012, "com.themoon.y1"),      // this process
            RunningProcess(90, 10012, "com.themoon.y1"),       // old copy
            RunningProcess(91, 10012, "com.themoon.y1:other"), // another process name
            RunningProcess(92, 1000, "com.themoon.y1"),        // another user
            RunningProcess(93, 10012, null)
        )
        assertEquals(listOf(90), ClassyApplication.staleProcessIds(100, 10012, "com.themoon.y1", running))
    }

    @Test
    fun noOtherProcessGivesAnEmptyList() {
        val running = listOf(RunningProcess(100, 10012, "com.themoon.y1"))
        assertEquals(emptyList<Int>(), ClassyApplication.staleProcessIds(100, 10012, "com.themoon.y1", running))
    }

    @Test
    fun aNewStartAfterAnOldProcessIsStoppedComesAtMostOnceIn30Seconds() {
        assertEquals(true, ClassyApplication.mayRestart(0L, 5_000L))          // never before
        assertEquals(false, ClassyApplication.mayRestart(100_000L, 110_000L)) // 10 s ago
        assertEquals(true, ClassyApplication.mayRestart(100_000L, 130_000L))  // 30 s ago
        assertEquals(true, ClassyApplication.mayRestart(900_000L, 20_000L))   // before a reboot
    }
}
