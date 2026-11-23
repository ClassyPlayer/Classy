package com.themoon.y1.navigation

import com.themoon.y1.games.WheelPads
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pure parts of [PadWheel] (no Android code), for unit tests.
 * A **position** is half a pad: 30 positions a turn (12°), clockwise from 12 o'clock.
 */
object PadDecoder {
    const val POSITIONS = 2 * WheelPads.PADS
    const val NONE = -1
    private const val MULTI = -2

    /** Position of each mask with one group of pads; [NONE] for 0, [MULTI] for two or more groups. Made one time. */
    private val table: IntArray by lazy {
        val t = IntArray(1 shl WheelPads.PADS)
        val scratch = IntArray(WheelPads.PADS)
        t[0] = NONE
        for (mask in 1 until t.size) t[mask] = if (groups(mask, scratch) == 1) centre(mask) else MULTI
        t
    }

    /** Makes the table now (one time, ~32 K masks), so that the first read of a touch does not wait for it. */
    fun prepare() {
        table.size
    }

    /** The position of a group of pads (its circular centre, to the nearest half pad). */
    fun centre(group: Int): Int {
        val turns = WheelPads.turns(group) ?: return NONE
        return (turns * POSITIONS).roundToInt() % POSITIONS
    }

    /**
     * Splits [mask] into groups of neighbouring pads (a gap of one pad joins, as in a press) in [out]; returns the
     * count. No allocation.
     */
    fun groups(mask: Int, out: IntArray): Int {
        val n = WheelPads.PADS
        if (mask == 0) return 0
        fun bit(i: Int) = mask shr ((i % n + n) % n) and 1
        // A group ends at two pads with no finger; with no such place, all pads are one group
        var start = -1
        for (i in 0 until n) if (bit(i) == 0 && bit(i + 1) == 0) {
            start = (i + 2) % n
            break
        }
        if (start < 0) {
            out[0] = mask
            return 1
        }
        var count = 0
        var cur = 0
        var zeros = 0
        for (k in 0 until n) {
            val i = (start + k) % n
            if (bit(i) == 1) {
                cur = cur or (1 shl i)
                zeros = 0
            } else if (++zeros >= 2 && cur != 0) {
                out[count++] = cur
                cur = 0
            }
        }
        if (cur != 0) out[count++] = cur
        return count
    }

    /** The shortest way from position [from] to [to]: -14..15. */
    fun shortest(from: Int, to: Int): Int {
        val d = ((to - from) % POSITIONS + POSITIONS) % POSITIONS
        return if (d > POSITIONS / 2) d - POSITIONS else d
    }

    /** Decodes masks on one thread (it keeps a scratch array and the group count of the last mask). */
    class Reader {
        private val scratch = IntArray(WheelPads.PADS)

        /** The pad groups of the last mask (1 with one finger, 2+ with two fingers or a resting part of the thumb). */
        var lastGroups = 0
            private set

        /**
         * The position of the finger in [mask], or [NONE]. With two or more groups: the group nearest to [near]
         * (the followed position, or [NONE] for the largest group).
         */
        fun read(mask: Int, near: Int): Int {
            val m = mask and WheelPads.MASK
            val v = table[m]
            if (v != MULTI) {
                lastGroups = if (v == NONE) 0 else 1
                return v
            }
            val count = groups(m, scratch)
            lastGroups = count
            var best = NONE
            var bestScore = Int.MAX_VALUE
            for (g in 0 until count) {
                val c = table[scratch[g]].takeIf { it >= 0 } ?: centre(scratch[g])
                if (c < 0) continue
                val score = if (near >= 0) abs(shortest(near, c)) else -Integer.bitCount(scratch[g])
                if (score < bestScore) {
                    bestScore = score
                    best = c
                }
            }
            return best
        }
    }
}

/**
 * Whole positions to rows: each row needs exactly [k] positions in one direction. The count starts again at each
 * touch ([reset]) and at each reversal, so the first row of a touch or of a reversal always needs the same travel.
 */
class RowCounter {
    private var ticks = 0
    private var dir = 0

    fun reset() {
        ticks = 0
        dir = 0
    }

    /** Adds [positions] (signed); returns the whole rows (signed) for [k] positions a row. */
    fun add(positions: Int, k: Int): Int {
        if (positions == 0 || k <= 0) return 0
        val s = Integer.signum(positions)
        if (s != dir) {
            ticks = 0
            dir = s
        }
        ticks += positions
        val rows = ticks / k
        ticks -= rows * k
        return rows
    }
}

/**
 * Wheel speed from the times of the position changes (the M/T method of encoders): positions between the first and
 * the last change in a window, over the time between them. The error is one chip frame (~10 ms), not one position as
 * with a fixed window. When no change comes for longer than the last interval, the speed is at most one position over
 * that time (the thumb slows down). No allocation.
 */
class PadSpeed {
    private val times = LongArray(64)
    private val places = IntArray(64)
    private var count = 0
    private var head = 0

    fun reset() {
        count = 0
    }

    /** The followed position (unwrapped) changed to [place] at [timeMs]. */
    fun change(timeMs: Long, place: Int) {
        times[head] = timeMs
        places[head] = place
        head = (head + 1) % times.size
        if (count < times.size) count++
    }

    /**
     * Turns a second (clockwise positive) over the changes in the last [windowMs] before [nowMs]; 0 when those
     * changes span less than [minSpanMs].
     */
    fun turnsPerSecond(nowMs: Long, windowMs: Long, minSpanMs: Long): Double {
        if (count < 2) return 0.0
        val last = (head - 1 + times.size) % times.size
        var first = last
        var steps = 0
        while (steps < count - 1) {
            val prev = (first - 1 + times.size) % times.size
            if (times[prev] < nowMs - windowMs) break
            first = prev
            steps++
        }
        val span = times[last] - times[first]
        if (steps == 0 || span < minSpanMs || span <= 0) return 0.0
        var v = (places[last] - places[first]).toDouble() / PadDecoder.POSITIONS * 1000.0 / span
        val since = nowMs - times[last]
        if (since > span / steps) {
            val cap = 1000.0 / PadDecoder.POSITIONS / since
            if (abs(v) > cap) v = Math.signum(v) * cap
        }
        return v
    }
}
