package com.themoon.y1.managers

import com.themoon.y1.managers.AudioPlayerManager.Companion.TrackErrorAction
import org.junit.Assert.assertEquals
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
    fun anErrorOfTheLastTrackStopsAtTheQueueEnd_exceptWithRepeatAll() {
        // A 10-track album, the last file does not play
        assertEquals(TrackErrorAction.STOP_AT_END, AudioPlayerManager.afterTrackError(1, 10, index = 9, repeatMode = 0))
        assertEquals(TrackErrorAction.STOP_AT_END, AudioPlayerManager.afterTrackError(1, 10, index = 9, repeatMode = 1))
        assertEquals(TrackErrorAction.NEXT, AudioPlayerManager.afterTrackError(1, 10, index = 9, repeatMode = 2))
        // Another track: the next track plays
        assertEquals(TrackErrorAction.NEXT, AudioPlayerManager.afterTrackError(1, 10, index = 4, repeatMode = 0))
    }

    @Test
    fun aQueueOfOnlyBadFilesStillStopsAfterOnePass() {
        assertEquals(TrackErrorAction.NEXT, AudioPlayerManager.afterTrackError(1, 3, index = 0, repeatMode = 2))
        assertEquals(TrackErrorAction.NEXT, AudioPlayerManager.afterTrackError(2, 3, index = 1, repeatMode = 2))
        assertEquals(TrackErrorAction.STOP_ALL_FAILED, AudioPlayerManager.afterTrackError(3, 3, index = 2, repeatMode = 2))
        assertEquals(TrackErrorAction.STOP_ALL_FAILED, AudioPlayerManager.afterTrackError(3, 3, index = 2, repeatMode = 0))
    }

    @Test
    fun noErrorDoesNotStop() {
        assertFalse(AudioPlayerManager.stopsAfterErrors(0, queueSize = 3))
    }
}
