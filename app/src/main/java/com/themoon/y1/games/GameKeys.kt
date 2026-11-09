package com.themoon.y1.games

import android.view.KeyEvent

/** What a Y2 button does in a game. */
sealed interface GameInput {
    /** A button of the game ([code]: the Android key code that the core maps, see fliwheel/android). */
    data class Key(val code: Int, val down: Boolean) : GameInput

    /** A wheel turn; negative = counter-clockwise. */
    data class Wheel(val delta: Float) : GameInput

    /** The game's own Menu button (pause, options): press it and let it go a moment later. */
    data object MenuTap : GameInput

    /** The centre hold: the Resume / Quit Game menu of the app. */
    data object ExitMenu : GameInput
}

/**
 * The Y2 buttons in a game, as pure state (the tests give it the key events and the time):
 * - Top (Back): Up of the game while it is pressed; two pushes within [DOUBLE_TAP_MS] = the game's Menu.
 * - Centre: Action of the game; a hold of [HOLD_MS] = the app's Resume / Quit Game menu ([centreHeld]).
 * - Wheel (DPAD_LEFT / DPAD_RIGHT): a scroll of [WHEEL_STEP] per step.
 * - The other buttons (Play/Pause = Down, Previous = Left, Next = Right) go to the core as they are.
 */
class GameKeys {
    private var lastTopPushMs = -DOUBLE_TAP_MS
    private var isCentreDown = false
    private var wasCentreHeld = false

    /** One key event of the screen. The controller starts the hold timer at a centre press ([isCentrePress]). */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, timeMs: Long): List<GameInput> {
        if (down && repeatCount > 0) return emptyList()
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> top(down, timeMs)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> centre(down)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) listOf(GameInput.Wheel(-WHEEL_STEP)) else emptyList()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) listOf(GameInput.Wheel(WHEEL_STEP)) else emptyList()
            else -> listOf(GameInput.Key(keyCode, down))
        }
    }

    /** True for the first press event of the centre button: the hold timer starts. */
    fun isCentrePress(keyCode: Int, down: Boolean, repeatCount: Int): Boolean =
        down && repeatCount == 0 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)

    /** The hold timer of the centre button ran out while it is still pressed. */
    fun centreHeld(): List<GameInput> {
        if (!isCentreDown || wasCentreHeld) return emptyList()
        wasCentreHeld = true
        return listOf(GameInput.Key(KeyEvent.KEYCODE_DPAD_CENTER, false), GameInput.ExitMenu)
    }

    /** The game lost the keys (the menu opened, the screen went off): forget the buttons that are down. */
    fun reset() {
        isCentreDown = false
        wasCentreHeld = false
        lastTopPushMs = -DOUBLE_TAP_MS
    }

    private fun top(down: Boolean, timeMs: Long): List<GameInput> {
        if (!down) return listOf(GameInput.Key(KeyEvent.KEYCODE_BACK, false))
        if (timeMs - lastTopPushMs < DOUBLE_TAP_MS) {
            lastTopPushMs = -DOUBLE_TAP_MS
            return listOf(GameInput.Key(KeyEvent.KEYCODE_BACK, false), GameInput.MenuTap)
        }
        lastTopPushMs = timeMs
        return listOf(GameInput.Key(KeyEvent.KEYCODE_BACK, true))
    }

    private fun centre(down: Boolean): List<GameInput> {
        if (down) {
            isCentreDown = true
            wasCentreHeld = false
            return listOf(GameInput.Key(KeyEvent.KEYCODE_DPAD_CENTER, true))
        }
        // No release after a hold (it already let the button go in the game), nor without a press in the game
        val release = isCentreDown && !wasCentreHeld
        isCentreDown = false
        wasCentreHeld = false
        return if (release) listOf(GameInput.Key(KeyEvent.KEYCODE_DPAD_CENTER, false)) else emptyList()
    }

    companion object {
        const val DOUBLE_TAP_MS = 350L
        const val HOLD_MS = 1500L
        const val WHEEL_STEP = 2f
    }
}
