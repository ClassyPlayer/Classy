package com.themoon.y1.navigation

/**
 * What a wheel turn moves on a screen now ([com.themoon.y1.controllers.ScreenController.wheelKind]). The Y2 pad wheel
 * ([PadWheel]) gives the rows a turn of Settings → Controls → Wheel Mode to [LIST], and 10 steps a turn to [VALUE] and
 * [LETTERS]. The key steps of the wheel (9 a turn) are the same for all kinds.
 */
enum class WheelKind {
    /** The rows of a menu or a list. */
    LIST,
    /** A value or an item that shows alone: volume, a slider, a page, a photo, an album in Cover Flow. */
    VALUE,
    /** The letters of the letter jump ([LetterJump.Mode]). */
    LETTERS,
}
