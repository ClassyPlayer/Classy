package com.themoon.y1.migrations

/**
 * Migration 020: the scan now reads the release type tag (single, EP, album, ...) that Cover Flow can hide singles and
 * EPs by, but it does not read the tags again of a file that did not change. The cache load marks the MP3, FLAC, Ogg,
 * Opus, M4A and M4B music tracks of an older cache (version < 4) as changed (MediaLibraryManager.parseLibraryCache), and this
 * migration starts the library rescan that reads them.
 */
class Migration020ReleaseTypes : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Rescan the library to read the release type of the albums"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        context.requestMediaRescan()
    }

    companion object {
        const val VERSION: Int = 20
        const val ID: String = "020_release_types"
    }
}
