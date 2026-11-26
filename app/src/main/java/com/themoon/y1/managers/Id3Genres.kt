package com.themoon.y1.managers

/**
 * The genres of ID3 by number: ID3v1 (0 to 79) and the Winamp additions (80 to 191). ID3v2 TCON frames can give a
 * genre as "(17)", "17", "(17)Rock" (number and refinement) or "(51)(39)" (several numbers), and "(RX)" / "(CR)" for
 * Remix and Cover.
 */
object Id3Genres {
    val NAMES: List<String> = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        // 80: Winamp
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic", "Bluegrass",
        "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic",
        "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove",
        "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House", "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore Techno",
        "Terror", "Indie", "BritPop", "Afro-Punk", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
        "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "J-Pop", "Synthpop", "Abstract", "Art Rock",
        "Baroque", "Bhangra", "Big Beat", "Breakbeat", "Chillout", "Downtempo", "Dub", "EBM", "Eclectic", "Electro",
        "Electroclash", "Emo", "Experimental", "Garage", "Global", "IDM", "Illbient", "Industro-Goth", "Jam Band", "Krautrock",
        "Leftfield", "Lounge", "Math Rock", "New Romantic", "Nu-Breakz", "Post-Punk", "Post-Rock", "Psytrance", "Shoegaze", "Space Rock",
        "Trop Rock", "World Music", "Neoclassical", "Audiobook", "Audio Theatre", "Neue Deutsche Welle", "Podcast", "Indie Rock", "G-Funk", "Dubstep",
        "Garage Rock", "Psybient"
    )

    /** One reference at the start of a TCON value: "(17)", "(RX)" or "(CR)". */
    private val REFERENCE = Regex("^\\((\\d{1,3}|RX|CR)\\)", RegexOption.IGNORE_CASE)

    /**
     * The genres of a TCON value [raw]. ID3v2.3 gives references and then a refinement: "(51)(39)" is two genres,
     * "(4)Eurodisco" one, named by its refinement (it says more than the number), and "((text)" a refinement that
     * starts with "(". ID3v2.4 gives a plain number, "RX" or "CR", or a name. Numbers that are not genres stay as
     * they are written.
     */
    fun names(raw: String): List<String> {
        var rest = raw.trim()
        val references = ArrayList<String>()
        while (!rest.startsWith("((")) {
            val m = REFERENCE.find(rest) ?: break
            references.add(token(m.groupValues[1]) ?: m.value)
            rest = rest.substring(m.value.length)
        }
        val refinement = (if (rest.startsWith("((")) rest.substring(1) else rest).trim()
        // Text after a reference is free text ("(17)13" is "13"); a value with no reference can be a v2.4 number
        if (refinement.isNotEmpty()) {
            return listOf(if (references.isEmpty()) token(refinement) ?: refinement else refinement)
        }
        return references
    }

    /** The genres of a TCON value [raw] ([names]) joined with "; ", as the other tag readers join genres. */
    fun name(raw: String): String = names(raw).joinToString("; ")

    /** The name of a genre number, "RX" or "CR"; null for anything else. */
    private fun token(value: String): String? = when {
        value.equals("RX", ignoreCase = true) -> "Remix"
        value.equals("CR", ignoreCase = true) -> "Cover"
        value.length in 1..3 && value.all { it in '0'..'9' } -> NAMES.getOrNull(value.toInt())
        else -> null
    }
}
