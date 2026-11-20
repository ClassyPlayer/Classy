package com.themoon.y1.models

import java.io.File

class SongItem {
    var file: File
    var title: String
    var artist: String
    var album: String
    var year: String
    var genre: String = "Unknown Genre"
        set(value) {
            field = safe(value, "Unknown Genre")
            cachedGenres = null
        }

    private var cachedGenres: List<String>? = null

    fun getGenres(): List<String> {
        var g = cachedGenres
        if (g == null) {
            g = GenreHelper.parseGenres(genre)
            cachedGenres = g
        }
        return g
    }

    fun matchesGenre(targetGenre: String?): Boolean {
        if (targetGenre.isNullOrBlank()) return false
        val target = targetGenre.trim()
        return getGenres().any { it.equals(target, ignoreCase = true) }
    }

    var albumArtist: String
    var composer: String

    /** Length in milliseconds from the library scan; 0 when not known (a cache from before it was kept). */
    var durationMs: Long = 0L

    /** Each artist of the [artist] tag ("A;B" is A and B), for the Artists list. */
    fun getArtists(): List<String> = ArtistNames.split(artist).ifEmpty { listOf(artist) }

    /** Each artist of the [albumArtist] tag, for the Album Artists list. */
    fun getAlbumArtists(): List<String> = ArtistNames.split(albumArtist).ifEmpty { listOf(albumArtist) }

    /** True if [target] is one of the track artists ([trackArtist]) or of the album artists. */
    fun matchesArtist(target: String?, trackArtist: Boolean): Boolean =
        ArtistNames.contains(if (trackArtist) artist else albumArtist, target)

    /** The MOOD tag (FLAC, Ogg, Opus), several moods split like the genres (see [GenreHelper.parseGenres]). Empty when the file has none. */
    var mood: String = ""
        set(value) = synchronized(this) {
            field = value
            cachedMoods = null
        }

    private var cachedMoods: List<String>? = null

    /** The moods of the song; "Unknown Mood" when it has none, so the Mood list is never empty and shows the untagged songs. */
    fun getMoods(): List<String> = synchronized(this) {
        cachedMoods ?: (if (mood.isBlank()) listOf(UNKNOWN_MOOD) else GenreHelper.parseGenres(mood)).also { cachedMoods = it }
    }

    fun matchesMood(target: String?): Boolean = !target.isNullOrBlank() && getMoods().any { it.equals(target.trim(), ignoreCase = true) }

    /** The compilation flag of the tags (ID3 TCMP, MP4 cpil, Vorbis COMPILATION). The library cache keeps it. */
    var compilationTag: Boolean = false

    /** True if the song is on a compilation album. [Compilations.mark] sets it for all tracks of the album. */
    var isCompilation: Boolean = false

    /** Last modification time of the file in milliseconds for incremental change detection. */
    var dateModified: Long = 0L

    /** Size of the file in bytes for change detection. */
    var fileSize: Long = 0L

    constructor(file: File, title: String, artist: String?, album: String?) {
        this.file = file
        this.title = title
        this.artist = safe(artist, "Unknown Artist")
        this.album = safe(album, "Unknown Album")
        this.year = "Unknown Year"
        this.genre = "Unknown Genre"
        this.albumArtist = this.artist
        this.composer = "Unknown Composer"
    }

    constructor(file: File, title: String, artist: String?, album: String?, year: String?, genre: String?) {
        this.file = file
        this.title = title
        this.artist = safe(artist, "Unknown Artist")
        this.album = safe(album, "Unknown Album")
        this.year = safe(year, "Unknown Year")
        this.genre = safe(genre, "Unknown Genre")
        this.albumArtist = this.artist
        this.composer = "Unknown Composer"
    }

    constructor(
        file: File,
        title: String,
        artist: String?,
        album: String?,
        year: String?,
        genre: String?,
        albumArtist: String?
    ) {
        this.file = file
        this.title = title
        this.artist = safe(artist, "Unknown Artist")
        this.album = safe(album, "Unknown Album")
        this.year = safe(year, "Unknown Year")
        this.genre = safe(genre, "Unknown Genre")
        this.albumArtist = safe(albumArtist, this.artist)
        this.composer = "Unknown Composer"
    }

    constructor(
        file: File,
        title: String,
        artist: String?,
        album: String?,
        year: String?,
        genre: String?,
        albumArtist: String?,
        composer: String?
    ) {
        this.file = file
        this.title = title
        this.artist = safe(artist, "Unknown Artist")
        this.album = safe(album, "Unknown Album")
        this.year = safe(year, "Unknown Year")
        this.genre = safe(genre, "Unknown Genre")
        this.albumArtist = safe(albumArtist, this.artist)
        this.composer = safe(composer, "Unknown Composer")
    }

    companion object {
        const val UNKNOWN_MOOD = "Unknown Mood"

        internal fun safe(value: String?, fallback: String): String {
            return if (!value.isNullOrBlank()) value else fallback
        }
    }
}
