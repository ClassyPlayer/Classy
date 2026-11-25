package com.themoon.y1.models

import java.util.Locale

/**
 * The release type of an album (MusicBrainz release group type), as Picard, beets and Navidrome tag it:
 * ID3 TXXX "MusicBrainz Album Type", Vorbis RELEASETYPE (or MUSICBRAINZ_ALBUMTYPE) and the MP4 freeform atom
 * "com.apple.iTunes:MusicBrainz Album Type". Some taggers (Mp3tag) write a TXXX "RELEASETYPE" frame instead.
 *
 * The value has one primary type (album, single, ep, broadcast, other) and any number of secondary types
 * (compilation, soundtrack, live, remix, ...). Picard writes ID3v2.4 values NUL-separated, ID3v2.3 values joined
 * with "/", and Vorbis values as repeated fields; Navidrome splits on ",". [normalize] reads all of them.
 */
object ReleaseTypes {

    enum class Primary { ALBUM, SINGLE, EP, BROADCAST, OTHER }

    private val PRIMARY = mapOf(
        "album" to Primary.ALBUM,
        "single" to Primary.SINGLE,
        "ep" to Primary.EP,
        "broadcast" to Primary.BROADCAST,
        "other" to Primary.OTHER
    )

    /** The one MusicBrainz type with a "/" in its name; it must not split. */
    private const val MIXTAPE = "mixtape/street"
    private const val MIXTAPE_HOLD = "mixtape\u0001street"

    private val SEPARATORS = Regex("[,;/\u0000]")

    /** iTunes and Apple Music put the type in the album title: "Name - Single", "Name - EP". */
    private val TITLE_SUFFIX = Regex("\\s[-\u2013\u2014]\\s*(single|ep)\\s*$", RegexOption.IGNORE_CASE)

    /**
     * The release type tag value in the form the library cache keeps: lower case, one token per type, joined with ";"
     * ("Album/Live" → "album;live"). Empty when [raw] has no type.
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val tokens = LinkedHashSet<String>()
        val held = raw.lowercase(Locale.US).replace(MIXTAPE, MIXTAPE_HOLD)
        for (part in held.split(SEPARATORS)) {
            val token = part.replace(MIXTAPE_HOLD, MIXTAPE).trim { it <= ' ' || it == '\uFEFF' }
            if (token.isNotEmpty()) tokens.add(token)
        }
        return tokens.joinToString(";")
    }

    /** The primary type of one track's normalized [releaseType], or null when it has none. */
    fun primaryOf(releaseType: String): Primary? {
        if (releaseType.isEmpty()) return null
        for (token in releaseType.split(';')) {
            PRIMARY[token]?.let { return it }
        }
        return null
    }

    /** The type that the album title shows ("Name - Single", "Name - EP"), or null. */
    fun primaryOfTitle(album: String): Primary? {
        val match = TITLE_SUFFIX.find(album) ?: return null
        return if (match.groupValues[1].equals("ep", ignoreCase = true)) Primary.EP else Primary.SINGLE
    }

    /**
     * The primary type of an album with [tracks]:
     * 1. the type that most of its tagged tracks have (on a tie, the first one found);
     * 2. null when a track has a release type tag with no primary type ("live"): the album is tagged, not a single;
     * 3. else the type in its title ([primaryOfTitle]);
     * 4. else SINGLE when [untaggedSingleMaxTracks] > 0 and it has that many tracks or fewer;
     * 5. else null (no type: an album).
     */
    fun primaryOfAlbum(tracks: List<SongItem>, untaggedSingleMaxTracks: Int): Primary? {
        if (tracks.isEmpty()) return null
        val counts = LinkedHashMap<Primary, Int>()
        for (track in tracks) {
            val primary = primaryOf(track.releaseType) ?: continue
            counts[primary] = (counts[primary] ?: 0) + 1
        }
        var best: Primary? = null
        var bestCount = 0
        for ((primary, count) in counts) {
            if (count > bestCount) {
                best = primary
                bestCount = count
            }
        }
        if (best != null) return best
        // Only an untagged album is guessed from its title or its length
        if (tracks.any { it.releaseType.isNotBlank() }) return null
        primaryOfTitle(tracks[0].album)?.let { return it }
        return if (untaggedSingleMaxTracks > 0 && tracks.size <= untaggedSingleMaxTracks) Primary.SINGLE else null
    }
}
