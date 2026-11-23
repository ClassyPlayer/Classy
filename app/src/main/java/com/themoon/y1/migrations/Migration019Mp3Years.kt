package com.themoon.y1.migrations

/**
 * Migration 019: the scan now reads the year of an MP3 from its ID3v2.3/2.4 tag when Android gives none (Id3Year),
 * but it does not read the tags again of a file that did not change. The cache load marks the MP3 tracks with no year
 * of an older cache (version < 3) as changed (MediaLibraryManager.parseLibraryCache), and this migration starts the
 * library rescan that reads them.
 */
class Migration019Mp3Years : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Rescan the library to read the year of MP3 files with no year"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        context.requestMediaRescan()
    }

    companion object {
        const val VERSION: Int = 19
        const val ID: String = "019_mp3_years"
    }
}
