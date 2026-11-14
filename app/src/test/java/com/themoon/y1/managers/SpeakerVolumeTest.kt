package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerVolumeTest {

    @Test
    fun bluetoothAndFmUseTheIndexOtherOutputsSoftware() {
        assertEquals(VolumeSplit.INDEX, SpeakerVolume.split(false, AudioOutput.A2DP))
        assertEquals(VolumeSplit.INDEX, SpeakerVolume.split(true, AudioOutput.SPEAKER))
        assertEquals(VolumeSplit.INDEX, SpeakerVolume.split(true, AudioOutput.WIRED))
        assertEquals(VolumeSplit.SOFTWARE, SpeakerVolume.split(false, AudioOutput.SPEAKER))
        assertEquals(VolumeSplit.SOFTWARE, SpeakerVolume.split(false, AudioOutput.WIRED))
        assertEquals(VolumeSplit.SOFTWARE, SpeakerVolume.split(false, null))
    }

    @Test
    fun safeStepIsBluetoothIndexTwo() {
        val s = VolumeSteps.settingFor(SpeakerVolume.SAFE_STEP, VolumeCurves.A2DP, split = VolumeSplit.INDEX)
        assertEquals(2, s.index)
        // The Y2's AVRCP patch sends 8 per index to the speaker: 16 of 127.
        assertEquals(16, 8 * s.index)
        assertEquals(-40.0, VolumeSteps.targetDb(SpeakerVolume.SAFE_STEP), 1e-9)
    }

    @Test
    fun safeStepCapsHeadphonesAndBluetoothButNotTheSpeaker() {
        assertEquals(10, SpeakerVolume.safeStep(25, AudioOutput.A2DP))
        assertEquals(10, SpeakerVolume.safeStep(25, AudioOutput.WIRED))
        assertEquals(25, SpeakerVolume.safeStep(25, AudioOutput.SPEAKER))
        assertEquals(25, SpeakerVolume.safeStep(25, null))
        // It never raises the volume.
        assertEquals(4, SpeakerVolume.safeStep(4, AudioOutput.A2DP))
        assertEquals(0, SpeakerVolume.safeStep(0, AudioOutput.WIRED))
    }

    @Test
    fun aLoudSpeakerReportJustAfterConnectingIsCapped() {
        assertTrue(SpeakerVolume.capsSpeakerReport(12, 2_500L))
        assertTrue(SpeakerVolume.capsSpeakerReport(30, 0L))
        assertFalse(SpeakerVolume.capsSpeakerReport(10, 2_500L))
        assertFalse(SpeakerVolume.capsSpeakerReport(4, 2_500L))
        // Later, the speaker's buttons set the volume.
        assertFalse(SpeakerVolume.capsSpeakerReport(30, SpeakerVolume.CONNECT_WINDOW_MS))
        assertFalse(SpeakerVolume.capsSpeakerReport(30, 60_000L))
    }

    @Test
    fun theConnectCapKeepsAVolumeLimitBelowTheSafeStep() {
        assertEquals(5, SpeakerVolume.connectCapStep(5))
        assertEquals(1, SpeakerVolume.connectCapStep(1))
        assertEquals(SpeakerVolume.SAFE_STEP, SpeakerVolume.connectCapStep(20))
        // Volume Limit off (-1): the safe step.
        assertEquals(SpeakerVolume.SAFE_STEP, SpeakerVolume.connectCapStep(-1))
    }

    @Test
    fun ourOwnIndexAndTheSpeakersConfirmationAreNotFollowed() {
        val now = 100_000L
        // Our target, at any time.
        assertTrue(SpeakerVolume.isOwnChange(5, 5, emptyList(), now))
        // The confirmation of an index we just wrote.
        val writes = listOf(SpeakerVolume.IndexWrite(6, now - 300L))
        assertTrue(SpeakerVolume.isOwnChange(6, 5, writes, now))
        // ... also after our next write (a lowering writes one index every 220 ms).
        val lowering = listOf(SpeakerVolume.IndexWrite(4, now - 240L), SpeakerVolume.IndexWrite(3, now - 20L))
        assertTrue(SpeakerVolume.isOwnChange(4, 2, lowering, now))
        // Too late for a confirmation: the speaker set it.
        val old = listOf(SpeakerVolume.IndexWrite(6, now - SpeakerVolume.ECHO_WINDOW_MS))
        assertFalse(SpeakerVolume.isOwnChange(6, 5, old, now))
    }

    @Test
    fun aSpeakerButtonJustAfterOurWriteIsFollowed() {
        val now = 100_000L
        // We wrote 5 (our target) 200 ms ago; the speaker's button then gives 6: that is the speaker's volume.
        val writes = listOf(SpeakerVolume.IndexWrite(5, now - 200L))
        assertFalse(SpeakerVolume.isOwnChange(6, 5, writes, now))
        assertFalse(SpeakerVolume.isOwnChange(3, 5, writes, now))
    }

    @Test
    fun theSpeakersConfirmationIsTheIndexWeWrote() {
        // The Y2's AVRCP patch sends 8 * index; Android maps the speaker's answer back with ceil(v * 15 / 127).
        for (index in 0..15) {
            val sent = 8 * index
            assertEquals(index, Math.ceil(sent * 15.0 / 127.0).toInt())
        }
    }

    @Test
    fun musicAfterALongPauseStartsSafe() {
        val pause = SpeakerVolume.LONG_PAUSE_MS
        assertTrue(SpeakerVolume.capsOnPlay(1_000L + pause, 1_000L, 0L))
        assertFalse(SpeakerVolume.capsOnPlay(1_000L + pause - 1, 1_000L, 0L))
        // A volume change during the pause is a fresh choice: it counts from then.
        assertFalse(SpeakerVolume.capsOnPlay(1_000L + pause, 1_000L, 1_000L + pause - 60_000L))
        assertTrue(SpeakerVolume.capsOnPlay(1_000L + 2 * pause, 1_000L, 1_000L + pause))
        // Nothing known (never paused, volume never changed): no cap here; start and connect cap already.
        assertFalse(SpeakerVolume.capsOnPlay(10L * pause, 0L, 0L))
    }
}
