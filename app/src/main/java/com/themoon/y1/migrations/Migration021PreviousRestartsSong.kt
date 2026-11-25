package com.themoon.y1.migrations

import com.themoon.y1.Prefs
import com.themoon.y1.managers.PreviousButton

/**
 * Migration 021: Settings → Playback → Previous Restarts Song (issue #141) is on for a new install, but an update must
 * not change what the Previous button does. A player that ran migrations before, or played before, gets the setting
 * Off once, unless it already has a value. 020 is the Cover Flow release types branch.
 */
class Migration021PreviousRestartsSong : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Keep Previous = previous song on a player that was updated"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val prefs = context.settingsPrefs ?: return
        if (prefs.contains(Prefs.PREVIOUS_RESTARTS.key)) return
        val hasPlayed = prefs.contains(Prefs.HAS_SAVED_PLAYBACK_STATE.key)
        if (PreviousButton.keepsOldBehaviour(context.startingVersion, hasPlayed)) {
            Prefs.PREVIOUS_RESTARTS.set(prefs, false)
        }
    }

    companion object {
        const val VERSION: Int = 21
        const val ID: String = "021_previous_restarts_song"
    }
}
