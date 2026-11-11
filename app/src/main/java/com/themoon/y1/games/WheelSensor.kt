package com.themoon.y1.games

import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The Y2 wheel as a position sensor, for the games ([WheelTracker] turns its pad masks into [GameInput]).
 *
 * The wheel controller has a test mode, switched by the kernel's /proc/tpd_testAdcCh (writable by every app): it
 * then reports which of its 15 touch pads a finger covers, instead of scroll steps. While it is on:
 * - the wheel sends no steps (the app's lists do not scroll): [stop] it before a menu of the app;
 * - every change of the mask repeats the last step key (DPAD_LEFT / DPAD_RIGHT): the game drops those keys;
 * - a screen-on puts the controller back to steps (the kernel's resume): [start] it again after the screen was off.
 *
 * [start] is false without that file (another device or kernel): the game keeps the scroll steps.
 */
class WheelSensor(private val onInputs: (List<GameInput>) -> Unit) {
    @Volatile
    private var thread: Thread? = null
    private val tracker = WheelTracker()
    /** One buffer for all reads (only the poll thread reads): readText() made a stream, a decoder and a String each time. */
    private val buffer = ByteArray(32)

    val isActive: Boolean get() = thread != null

    fun start(): Boolean {
        if (thread != null) return true
        if (!setTestMode(true)) return false
        thread = Thread({ poll() }, "WheelSensor").also { it.start() }
        return true
    }

    /** Back to scroll steps; a finger on the wheel lifts for the game. */
    fun stop() {
        val t = thread ?: return
        thread = null
        try {
            t.join(200)
        } catch (_: InterruptedException) {}
        setTestMode(false)
        val lift = tracker.lift()
        if (lift.isNotEmpty()) onInputs(lift)
    }

    /** The pads a finger covers; 0 with no finger, or when the file says "Not in test mode". */
    private fun readMask(): Int = try {
        // A new open for each read: the kernel makes the text at a read from the start of the file
        val n = FileInputStream(TEST_MODE).use { it.read(buffer) }
        if (n > 0) parseMask(buffer, n) else 0
    } catch (_: Exception) {
        0
    }

    private fun poll() {
        while (thread === Thread.currentThread()) {
            val inputs = tracker.onMask(readMask(), SystemClock.uptimeMillis())
            if (inputs.isNotEmpty()) onInputs(inputs)
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    companion object {
        private const val POLL_MS = 4L
        private val TEST_MODE = File("/proc/tpd_testAdcCh")

        /**
         * The pad mask in the first [length] bytes of [bytes]: a decimal number, with white space around it; 0 for
         * anything else (no finger, or "Not in test mode").
         */
        internal fun parseMask(bytes: ByteArray, length: Int): Int {
            var i = 0
            while (i < length && bytes[i].toInt().toChar().isWhitespace()) i++
            val start = i
            var value = 0
            while (i < length && bytes[i] in '0'.code.toByte()..'9'.code.toByte()) {
                if (value > (Int.MAX_VALUE - 9) / 10) return 0
                value = value * 10 + (bytes[i] - '0'.code.toByte())
                i++
            }
            if (i == start) return 0
            while (i < length) if (!bytes[i++].toInt().toChar().isWhitespace()) return 0
            return value
        }

        /** Back to scroll steps, at the app start: after the app died during a game, the wheel is still in test mode. */
        fun resetTestMode() {
            if (TEST_MODE.exists()) setTestMode(false)
        }

        private fun setTestMode(on: Boolean): Boolean {
            if (!TEST_MODE.exists()) return false
            return try {
                FileOutputStream(TEST_MODE).use { it.write(if (on) ONE else ZERO) }
                true
            } catch (e: Exception) {
                FliwheelBridge.log("wheel test mode ${if (on) "on" else "off"} failed: ${e.message}")
                false
            }
        }

        private val ONE = "1".toByteArray()
        private val ZERO = "0".toByteArray()
    }
}
