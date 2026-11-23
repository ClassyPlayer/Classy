package com.themoon.y1.migrations

/**
 * Migration 018: the library cache keeps the MOOD tag of each track (cache version 2), but the scan does not read
 * the tags again of a file that did not change. The cache load marks the FLAC, Ogg and Opus tracks of an older cache
 * as changed (MediaLibraryManager.parseLibraryCache), and this migration starts the library rescan that reads them.
 */
class Migration018MoodTags : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Rescan the library to read the MOOD tags of FLAC, Ogg and Opus files"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        context.requestMediaRescan()
    }

    companion object {
        const val VERSION: Int = 18
        const val ID: String = "018_mood_tags"
    }
}
