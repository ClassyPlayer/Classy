package com.themoon.y1.games

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameKeysTest {
    private val keys = GameKeys()

    private fun down(code: Int, time: Long = 0, repeat: Int = 0) = keys.onKey(code, true, repeat, time)
    private fun up(code: Int, time: Long = 0) = keys.onKey(code, false, 0, time)

    private val back = KeyEvent.KEYCODE_BACK
    private val centre = KeyEvent.KEYCODE_DPAD_CENTER

    @Test
    fun topIsUpWhilePressed() {
        assertEquals(listOf(GameInput.Key(back, true)), down(back, 1000))
        assertEquals(listOf(GameInput.Key(back, false)), up(back, 1100))
    }

    @Test
    fun twoTopPushesAreTheGameMenu() {
        down(back, 1000)
        up(back, 1080)
        assertEquals(listOf(GameInput.Key(back, false), GameInput.MenuTap), down(back, 1000 + GameKeys.DOUBLE_TAP_MS - 1))
        // A third push starts again
        assertEquals(listOf(GameInput.Key(back, true)), down(back, 1500))
    }

    @Test
    fun slowTopPushesAreTwoUps() {
        down(back, 1000)
        assertEquals(listOf(GameInput.Key(back, true)), down(back, 1000 + GameKeys.DOUBLE_TAP_MS))
    }

    @Test
    fun repeatsOfAHeldButtonAreDropped() {
        down(back, 1000)
        assertTrue(down(back, 1400, repeat = 1).isEmpty())
        assertTrue(down(centre, 1400, repeat = 3).isEmpty())
    }

    @Test
    fun centreIsAction() {
        assertTrue(keys.isCentrePress(centre, true, 0))
        assertFalse(keys.isCentrePress(centre, true, 1))
        assertEquals(listOf(GameInput.Key(centre, true)), down(KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf(GameInput.Key(centre, false)), up(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun centreHoldOpensTheMenuOnce() {
        down(centre)
        assertEquals(listOf(GameInput.Key(centre, false), GameInput.ExitMenu), keys.centreHeld())
        assertTrue(keys.centreHeld().isEmpty())
        // The release of the hold does not go to the game
        assertTrue(up(centre).isEmpty())
    }

    @Test
    fun theHoldTimerAfterTheReleaseDoesNothing() {
        down(centre)
        up(centre)
        assertTrue(keys.centreHeld().isEmpty())
    }

    @Test
    fun aCentreReleaseWithoutItsPressIsDropped() {
        assertTrue(up(centre).isEmpty())
        down(centre)
        keys.reset()
        assertTrue(up(centre).isEmpty())
    }

    @Test
    fun wheelSteps() {
        assertEquals(listOf(GameInput.Wheel(-GameKeys.WHEEL_STEP)), down(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(listOf(GameInput.Wheel(GameKeys.WHEEL_STEP)), down(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(up(KeyEvent.KEYCODE_DPAD_RIGHT).isEmpty())
    }

    @Test
    fun otherButtonsGoAsTheyAre() {
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            assertEquals(listOf(GameInput.Key(code, true)), down(code))
            assertEquals(listOf(GameInput.Key(code, false)), up(code))
        }
    }
}
