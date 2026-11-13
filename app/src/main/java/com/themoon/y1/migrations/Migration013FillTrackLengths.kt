package com.themoon.y1.migrations

/**
 * Migration 013: the library cache keeps the length of each track (`durationMs`), but the scan does not read
 * the tags again of a file that did not change. After the next load of the library cache, a low-priority
 * worker reads the length of each cached track that has none and saves the cache (MediaLibraryManager.fillMissingLengths).
 */
class Migration013FillTrackLengths : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Read the length of each track in the library cache that has none"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val prefs = context.migrationPrefs ?: return
        // Not saved: the migration fails, and runs again at the next start
        check(prefs.edit().putBoolean(MigrationManager.KEY_PENDING_LENGTH_FILL, true).commit()) { "Pending length fill not saved" }
    }

    companion object {
        const val VERSION: Int = 13
        const val ID: String = "013_fill_track_lengths"
    }
}
