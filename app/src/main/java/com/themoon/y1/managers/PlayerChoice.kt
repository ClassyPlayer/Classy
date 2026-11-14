package com.themoon.y1.managers

import java.util.EnumSet

/**
 * The player that plays a Music Quiz clip, and the next player when it fails, so that a file that one player cannot
 * play goes to the other player, and later clips of the file go directly to the player that works.
 * [FOR_CLIP]: MediaPlayer first for each file (as before), ExoPlayer when MediaPlayer fails.
 *
 * Main thread only.
 */
class PlayerChoice {

    enum class Engine { MEDIA_PLAYER, EXO_PLAYER }

    /** The result of a check of a player that must play: see [checkStart]. */
    enum class StartCheck { PLAYS, STALLED, CHECK_AGAIN }

    private val failures = HashMap<String, EnumSet<Engine>>()

    /** The first player of [order] that has not failed for [path]. When all failed: the last player of [order]. */
    fun choose(path: String, order: List<Engine>): Engine =
        order.firstOrNull { !hasFailed(path, it) } ?: order.last()

    /**
     * Records that [engine] cannot play [path]. Returns the next player of [order] to try, or null when all players
     * of [order] failed for [path].
     */
    fun fail(path: String, engine: Engine, order: List<Engine>): Engine? {
        failures.getOrPut(path) { EnumSet.noneOf(Engine::class.java) }.add(engine)
        return order.firstOrNull { !hasFailed(path, it) }
    }

    fun hasFailed(path: String, engine: Engine): Boolean = failures[path]?.contains(engine) == true

    /** True when [order] has a player other than [engine] that has not failed for [path]. */
    fun hasNext(path: String, engine: Engine, order: List<Engine>): Boolean =
        order.any { it != engine && !hasFailed(path, it) }

    companion object {
        val MEDIA_PLAYER_FIRST: List<Engine> = listOf(Engine.MEDIA_PLAYER, Engine.EXO_PLAYER)

        /** The time after which a player that must play has to be past its start position. */
        const val START_CHECK_MS = 5000L

        /** A smaller step of the position in [START_CHECK_MS] is no progress (the player hangs). */
        const val MIN_PROGRESS_MS = 500L

        /** The players of a Music Quiz clip, for each file: MediaPlayer (it starts fast), then ExoPlayer. */
        val FOR_CLIP: List<Engine> = MEDIA_PLAYER_FIRST

        /**
         * A player that must play was at [fromMs], and [START_CHECK_MS] later it is at [positionMs].
         * [StartCheck.PLAYS]: it moved forward. [StartCheck.STALLED]: it did not move.
         * [StartCheck.CHECK_AGAIN]: it moved back (a seek), so the check must start again from [positionMs].
         */
        fun checkStart(fromMs: Long, positionMs: Long): StartCheck {
            val step = positionMs - fromMs
            return when {
                step >= MIN_PROGRESS_MS -> StartCheck.PLAYS
                step <= -MIN_PROGRESS_MS -> StartCheck.CHECK_AGAIN
                else -> StartCheck.STALLED
            }
        }
    }
}
