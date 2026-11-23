package com.themoon.y1.photos

import java.util.Random

/**
 * The order of a slideshow over [count] photos that starts at photo [start]: in album order, or shuffled
 * (the start photo first, then the others in a random order). With [repeat] the show starts again after the last
 * photo (a shuffled show gets a new order); without it, [next] gives -1 at the end.
 *
 * No Android types: unit-tested. [step] moves back and forth (the wheel during a slideshow).
 */
class SlideshowOrder(
    private val count: Int,
    start: Int,
    private val shuffle: Boolean,
    private val repeat: Boolean,
    private val random: Random = Random()
) {
    // Before [order]: makeOrder sets it
    private var place = 0
    private var order: IntArray = makeOrder(start.coerceIn(0, (count - 1).coerceAtLeast(0)))

    /** The photo that shows now. */
    val current: Int get() = if (count == 0) -1 else order[place]

    private fun makeOrder(first: Int): IntArray {
        if (count == 0) return IntArray(0)
        if (!shuffle) return IntArray(count) { it }.also { place = first }
        val rest = (0 until count).filter { it != first }.toMutableList()
        java.util.Collections.shuffle(rest, random)
        place = 0
        return (listOf(first) + rest).toIntArray()
    }

    /** The photo that [next] will give (to decode it ahead), or -1 when that is not known yet (a new shuffled round). */
    fun peek(): Int = when {
        count == 0 -> -1
        place + 1 < count -> order[place + 1]
        repeat && !shuffle -> order[0]
        else -> -1
    }

    /** The next photo (and it becomes [current]), or -1 at the end without repeat. */
    fun next(): Int = step(1)

    /** One photo forward ([delta] > 0) or back; -1 when the show ends (past the last photo, no repeat). */
    fun step(delta: Int): Int {
        if (count == 0) return -1
        val to = place + delta
        when {
            to in 0 until count -> place = to
            to < 0 -> place = if (repeat) count - 1 else 0
            !repeat -> return -1
            shuffle -> {
                // A new round: a new order that does not start with the photo that shows now
                val last = order[place]
                var o: IntArray
                do {
                    o = makeOrder(random.nextInt(count))
                } while (count > 1 && o[0] == last)
                order = o
                place = 0
            }
            else -> place = 0
        }
        return order[place]
    }
}
