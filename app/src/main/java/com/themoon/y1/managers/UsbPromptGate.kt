package com.themoon.y1.managers

/**
 * One USB question for each cable plug. The question shows at a USB connect; after an answer it does not show
 * again until the cable comes out. A USB function change (USB Drive, Eject) sends USB connect broadcasts
 * again, but it does not cut the power: only a power loss ends the plug.
 */
class UsbPromptGate {
    private var answered = false

    /** A USB connect broadcast: true when the question must show. */
    fun shouldAsk(): Boolean = !answered

    /** The user answered, or the share is on: no question until the cable comes out. */
    fun onAnswered() {
        answered = true
    }

    /** A power broadcast. [cableOut] = ACTION_POWER_DISCONNECTED; [plugged] = EXTRA_PLUGGED (0 = no power, -1 = unknown). */
    fun onPower(cableOut: Boolean, plugged: Int) {
        if (cableOut || plugged == 0) answered = false
    }
}
