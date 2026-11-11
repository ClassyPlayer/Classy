package com.themoon.y1.games

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Where a finger rests on the Y2 wheel, from the mask of its 15 touch pads ([WheelSensor] reads it).
 *
 * Bit i of the mask is pad i; the pads run clockwise, [PAD_AT_TOP] at 12 o'clock. A finger covers 1 to 5 neighbouring
 * pads: their circular centre is where it rests, in steps of half a pad (12°).
 */
object WheelPads {
    const val PADS = 15
    const val MASK = (1 shl PADS) - 1

    /** Measured on a Y2: taps at 12, 3, 6 and 9 o'clock centre on pads 4, 8.5, 12 and 0. */
    const val PAD_AT_TOP = 4.0

    /** Clockwise turns from 12 o'clock, in [0, 1); null with no finger. */
    fun turns(mask: Int): Double? {
        var x = 0.0
        var y = 0.0
        for (pad in 0 until PADS) {
            if (mask shr pad and 1 == 0) continue
            val a = 2 * PI * pad / PADS
            x += cos(a)
            y += sin(a)
        }
        if (hypot(x, y) < 1e-6) return null
        val pad = atan2(y, x) / (2 * PI) * PADS
        return wrap((pad - PAD_AT_TOP) / PADS)
    }

    /** [turns] as the core's touch angle: the counter-clockwise byte ring of the iPod wheel (Right = 0, Top = 64). */
    fun guestAngle(turns: Double): Int = ((0.25 - turns) * 256).roundToInt() and 0xff

    /** [turns] into [0, 1). */
    fun wrap(turns: Double): Double = turns - floor(turns)

    /** The shortest way from [from] to [to], in turns: positive clockwise, within ±0.5. */
    fun shortest(from: Double, to: Double): Double {
        val d = wrap(to - from)
        return if (d > 0.5) d - 1 else d
    }
}

/**
 * One finger on the wheel, from the pad masks of [WheelSensor] (one per poll), as game input:
 * - the turn as [GameInput.Wheel] at [DETENTS_PER_TURN] detents a turn, like the iPod wheel;
 * - where the finger rests as [GameInput.WheelTouch] (null when it lifts).
 *
 * A finger between two pads makes the mask flicker between them (half a pad): the position follows the finger only
 * past [DEAD_BAND] turns, so a resting finger does not scroll. The mask can drop to 0 for a moment while the finger
 * slides fast; it counts as a lift only after [LIFT_MS].
 */
class WheelTracker {
    /** The followed position (turns), null with no finger. */
    private var position: Double? = null
    private var lastSeenMs = 0L
    private var detents = 0.0
    private var sentAngle: Int? = null

    fun onMask(mask: Int, timeMs: Long): List<GameInput> {
        val turns = WheelPads.turns(mask and WheelPads.MASK)
        if (turns == null) {
            return if (position != null && timeMs - lastSeenMs >= LIFT_MS) lift() else emptyList()
        }
        lastSeenMs = timeMs
        val from = position
        val out = ArrayList<GameInput>(2)
        if (from == null) {
            position = turns
        } else {
            val d = WheelPads.shortest(from, turns)
            if (abs(d) > DEAD_BAND) {
                val moved = d - DEAD_BAND * d.sign
                position = WheelPads.wrap(from + moved)
                detents += moved * DETENTS_PER_TURN
                // The core moves whole detents (2 per unit of delta): keep the rest for the next move
                val whole = detents.toInt()
                if (whole != 0) {
                    detents -= whole
                    out += GameInput.Wheel(whole / 2f)
                }
            }
        }
        val angle = WheelPads.guestAngle(position!!)
        if (angle != sentAngle) {
            sentAngle = angle
            out += GameInput.WheelTouch(angle)
        }
        return out
    }

    /** The finger left (or the wheel stops sensing): forget it. */
    fun lift(): List<GameInput> {
        val wasTouching = sentAngle != null
        position = null
        detents = 0.0
        sentAngle = null
        return if (wasTouching) listOf(GameInput.WheelTouch(null)) else emptyList()
    }

    companion object {
        const val DETENTS_PER_TURN = 96
        const val DEAD_BAND = 0.55 / WheelPads.PADS
        const val LIFT_MS = 60L
    }
}
