package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackErrorsTest {

    @Test
    fun theQueueSkipsUntilEachTrackFailedOnce() {
        for (errors in 1..4) {
            assertFalse(AudioPlayerManager.stopsAfterErrors(errors, queueSize = 5))
        }
        assertTrue(AudioPlayerManager.stopsAfterErrors(5, queueSize = 5))
    }

    @Test
    fun aQueueOfOneTrackStopsAtItsFirstError() {
        assertTrue(AudioPlayerManager.stopsAfterErrors(1, queueSize = 1))
    }

    @Test
    fun noErrorDoesNotStop() {
        assertFalse(AudioPlayerManager.stopsAfterErrors(0, queueSize = 3))
    }
}
