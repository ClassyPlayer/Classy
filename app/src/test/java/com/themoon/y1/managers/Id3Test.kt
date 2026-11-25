package com.themoon.y1.managers

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Id3Test {
    private fun frame(id: String, text: String, v4: Boolean): ByteArray {
        val body = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8) // 3 = UTF-8
        val n = body.size
        val size = if (v4) byteArrayOf((n shr 21 and 127).toByte(), (n shr 14 and 127).toByte(), (n shr 7 and 127).toByte(), (n and 127).toByte())
        else byteArrayOf(0, 0, (n shr 8).toByte(), n.toByte())
        return id.toByteArray() + size + byteArrayOf(0, 0) + body
    }

    private fun mp3(version: Int, vararg frames: ByteArray): File {
        val all = ByteArrayOutputStream().also { o -> frames.forEach { o.write(it) } }.toByteArray()
        val n = all.size
        val head = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0,
            (n shr 21 and 127).toByte(), (n shr 14 and 127).toByte(), (n shr 7 and 127).toByte(), (n and 127).toByte())
        return File.createTempFile("id3", ".mp3").apply { writeBytes(head + all); deleteOnExit() }
    }

    @Test fun v24DateGivesTheYear() =
        assertEquals("2024", Id3.year(mp3(4, frame("TIT2", "Song", true), frame("TDRC", "2024-09-27", true))))

    @Test fun v23Tyer() = assertEquals("1999", Id3.year(mp3(3, frame("TYER", "1999", false))))

    // TXXX: description, NUL, value(s); ID3v2.4 values NUL-separated (Picard), ID3v2.3 values joined with "/"
    @Test fun scanTagsReadsTheReleaseTypeAndTheYearInOneWalk() {
        val tags = Id3.scanTags(mp3(4, frame("TDRC", "2024-09-27", true), frame("TXXX", "MusicBrainz Album Type\u0000single\u0000remix", true)))
        assertEquals("2024", tags.year)
        assertEquals("single;remix", tags.releaseType)
    }

    @Test fun scanTagsReadsV23AndMp3tagReleaseType() {
        assertEquals("Album/Live", Id3.scanTags(mp3(3, frame("TXXX", "MusicBrainz Album Type\u0000Album/Live", false))).releaseType)
        assertEquals("ep", Id3.scanTags(mp3(4, frame("TXXX", "RELEASETYPE\u0000ep", true))).releaseType)
    }

    @Test fun scanTagsSkipsOtherTxxxFramesAndKeepsTdrcOverTyer() {
        val tags = Id3.scanTags(mp3(4, frame("TXXX", "MusicBrainz Album Id\u00001b2c3d4e-0000-0000-0000-000000000000", true),
            frame("TYER", "1999", true), frame("TDRC", "2001", true), frame("TYER", "1998", true)))
        assertEquals("2001", tags.year)
        assertNull(tags.releaseType)
    }

    @Test fun noYearFrame() = assertNull(Id3.year(mp3(4, frame("TIT2", "Song", true))))
    @Test fun notId3() = assertNull(Id3.year(File.createTempFile("none", ".mp3").apply { writeBytes(ByteArray(40)); deleteOnExit() }))

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 127).toByte(), (n shr 14 and 127).toByte(), (n shr 7 and 127).toByte(), (n and 127).toByte())

    /** A v2.4 frame with raw [flags] (status, format) and a raw [body]. */
    private fun rawFrame(id: String, flags: ByteArray, body: ByteArray) = id.toByteArray() + syncsafe(body.size) + flags + body

    private fun tag(version: Int, tagFlags: Int, ext: ByteArray, vararg frames: ByteArray): File {
        val all = ext + frames.fold(ByteArray(0)) { a, b -> a + b }
        val head = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, tagFlags.toByte()) + syncsafe(all.size)
        return File.createTempFile("id3", ".mp3").apply { writeBytes(head + all); deleteOnExit() }
    }

    @Test fun v24ExtendedHeaderIsSkipped() {
        val ext = byteArrayOf(0, 0, 0, 6, 1, 0) // size 6 with its own field, one flag byte
        assertEquals("2024", Id3.year(tag(4, 0x40, ext, frame("TDRC", "2024-09-27", true))))
    }

    @Test fun v23ExtendedHeaderIsSkipped() {
        val ext = byteArrayOf(0, 0, 0, 6, 0, 0, 0, 0, 0, 0) // size 6 without its own field
        assertEquals("1999", Id3.year(tag(3, 0x40, ext, frame("TYER", "1999", false))))
    }

    @Test fun dataLengthIndicatorIsSkipped() {
        val utf16 = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + "2024".toByteArray(Charsets.UTF_16LE)
        val body = syncsafe(utf16.size) + utf16
        assertEquals("2024", Id3.year(tag(4, 0, ByteArray(0), rawFrame("TDRC", byteArrayOf(0, 1), body))))
    }

    @Test fun unsynchronisedFrameIsRestored() {
        // UTF-16 BOM FF FE, unsynchronised to FF 00 FE
        val body = byteArrayOf(1, 0xFF.toByte(), 0, 0xFE.toByte()) + "2024".toByteArray(Charsets.UTF_16LE)
        assertEquals("2024", Id3.year(tag(4, 0, ByteArray(0), rawFrame("TDRC", byteArrayOf(0, 2), body))))
    }

    @Test fun v24TagUnsynchronisationWithoutFrameFlagIsRestored() {
        // Only the tag flag (0x80) is set, not the frame flag: the UTF-16 BOM FF FE is stored as FF 00 FE
        val body = byteArrayOf(1, 0xFF.toByte(), 0, 0xFE.toByte()) + "2024".toByteArray(Charsets.UTF_16LE)
        assertEquals("2024", Id3.year(tag(4, 0x80, ByteArray(0), rawFrame("TDRC", byteArrayOf(0, 0), body))))
    }

    @Test fun utf8ByteOrderMarkIsRemoved() {
        val body = byteArrayOf(3, 0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "2024-09-27".toByteArray()
        assertEquals("2024", Id3.year(tag(4, 0, ByteArray(0), rawFrame("TDRC", byteArrayOf(0, 0), body))))
    }

    @Test fun compressedFrameIsSkipped() =
        assertNull(Id3.year(tag(3, 0, ByteArray(0), "TYER".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0x80.toByte()) + byteArrayOf(0, 0, 0, 0, 1))))

    @Test fun v23TagUnsynchronisationKeepsLaterFramesInPlace() {
        // The TIT2 body has FF FE, stored unsynchronised as FF 00 FE: its size stays 5, it takes 6 bytes in the file
        fun v3frame(id: String, body: ByteArray) = id.toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        val tyer = v3frame("TYER", byteArrayOf(3) + "1999".toByteArray())
        val stored = v3frame("TIT2", byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte(), 0x41, 0)).let {
            it.copyOfRange(0, 11) + byteArrayOf(0xFF.toByte(), 0, 0xFE.toByte(), 0x41, 0) // size 5, stored with the extra 00
        } + tyer
        val head = "ID3".toByteArray() + byteArrayOf(3, 0, 0x80.toByte()) + syncsafe(stored.size)
        val f = File.createTempFile("id3", ".mp3").apply { writeBytes(head + stored); deleteOnExit() }
        assertEquals("1999", Id3.year(f))
    }

    private fun file(vararg bytes: ByteArray) = File.createTempFile("id3", ".mp3").apply { writeBytes(bytes.fold(ByteArray(0)) { a, b -> a + b }); deleteOnExit() }
    private fun head(version: Int, size: Int, flags: Int = 0) = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags.toByte()) + syncsafe(size)

    @Test fun fileShorterThanAHeaderGivesNull() {
        assertNull(Id3.year(file("ID3".toByteArray())))
        assertNull(Id3.year(file(ByteArray(0))))
    }

    @Test fun hugeTagOnATinyFileGivesNull() {
        assertNull(Id3.year(file(head(4, 0x0FFFFFFF), ByteArray(20))))
        assertNull(Id3.year(file(head(3, 3 * 1024 * 1024, 0x80), ByteArray(20)))) // unsynchronised, under the 4 MB cap
        assertNull(Id3.year(file(head(4, 100, 0x40), ByteArray(2)))) // extended header cut short
    }

    @Test fun frameSizeFfFfFfFfGivesNull() {
        val bad = "TDRC".toByteArray() + byteArrayOf(-1, -1, -1, -1, 0, 0) + ByteArray(20)
        assertNull(Id3.year(file(head(4, 40), bad)))
        assertNull(Id3.year(file(head(3, 40), bad)))
    }

    @Test fun fileTruncatedMidFrameGivesNull() {
        val full = frame("TDRC", "2024-09-27", true)
        assertNull(Id3.year(file(head(4, full.size), full.copyOf(full.size - 6))))
    }

    @Test fun truncationKeepsATyerYearFoundBefore() {
        val tyer = frame("TYER", "1999", false)
        val cut = frame("TIT2", "A long title", false).let { it.copyOf(it.size - 5) }
        assertEquals("1999", Id3.year(file(head(3, tyer.size + 100), tyer, cut)))
    }

    @Test fun nonAsciiDigitsAreNotAYear() =
        assertNull(Id3.year(mp3(4, frame("TDRC", "\uFF12\uFF10\uFF12\uFF14", true))))

    // Lyrics (USLT: encoding, language, description, text)

    private fun uslt(enc: Int, description: ByteArray, text: ByteArray) = byteArrayOf(enc.toByte()) + "eng".toByteArray() + description + text

    @Test fun v24Utf8Lyrics() {
        val body = uslt(3, byteArrayOf(0), "Line one\nLine two".toByteArray())
        assertEquals("Line one\nLine two", Id3.lyrics(tag(4, 0, ByteArray(0), rawFrame("USLT", byteArrayOf(0, 0), body))))
    }

    @Test fun v23Utf16LyricsWithADescription() {
        // Each UTF-16 string has its own BOM; the description "@" (40 00) must not end at its odd 00 00 pair
        val utf16 = { t: String -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + t.toByteArray(Charsets.UTF_16LE) }
        val body = uslt(1, utf16("@") + byteArrayOf(0, 0), utf16("Hello"))
        val v3 = "USLT".toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        assertEquals("Hello", Id3.lyrics(tag(3, 0, ByteArray(0), v3)))
    }

    @Test fun v24TagUnsynchronisedLyrics() {
        // Tag flag 0x80 only: the UTF-16 BOM FF FE of the text is stored as FF 00 FE
        val body = uslt(1, byteArrayOf(0xFF.toByte(), 0, 0xFE.toByte(), 0, 0), byteArrayOf(0xFF.toByte(), 0, 0xFE.toByte()) + "Hi".toByteArray(Charsets.UTF_16LE))
        assertEquals("Hi", Id3.lyrics(tag(4, 0x80, ByteArray(0), rawFrame("USLT", byteArrayOf(0, 0), body))))
    }

    @Test fun emptyLyricsFrameIsSkipped() {
        // Some taggers write a USLT frame with only a NUL; a later frame with text gives the lyrics
        val empty = rawFrame("USLT", byteArrayOf(0, 0), uslt(3, byteArrayOf(0), byteArrayOf(0)))
        assertNull(Id3.lyrics(tag(4, 0, ByteArray(0), empty)))
        val real = rawFrame("USLT", byteArrayOf(0, 0), uslt(3, byteArrayOf(0), "Words".toByteArray()))
        assertEquals("Words", Id3.lyrics(tag(4, 0, ByteArray(0), empty, real)))
    }

    @Test fun lyricsAfterALargeCover() {
        // The frames before the lyrics are skipped by seeking: a 600 KB cover does not hide them
        val cover = rawFrame("APIC", byteArrayOf(0, 0), ByteArray(600 * 1024))
        val lyrics = rawFrame("USLT", byteArrayOf(0, 0), uslt(3, byteArrayOf(0), "After the cover".toByteArray()))
        assertEquals("After the cover", Id3.lyrics(tag(4, 0, ByteArray(0), cover, lyrics)))
    }

    @Test fun brokenTagsGiveNoLyrics() {
        val full = rawFrame("USLT", byteArrayOf(0, 0), uslt(3, byteArrayOf(0), "Cut here".toByteArray()))
        assertNull(Id3.lyrics(file(head(4, full.size), full.copyOf(full.size - 4))))
        assertNull(Id3.lyrics(file(head(4, 40), "USLT".toByteArray() + byteArrayOf(-1, -1, -1, -1, 0, 0) + ByteArray(20))))
        assertNull(Id3.lyrics(file(head(4, 0x0FFFFFFF), ByteArray(20))))
        assertNull(Id3.lyrics(file("ID3".toByteArray())))
        assertNull(Id3.lyrics(file(ByteArray(40))))
    }

    private fun v3frame(id: String, body: ByteArray) =
        id.toByteArray() + byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte(), 0, 0) + body

    @Test fun v23UnsynchronisedTagThatIsCutKeepsTheLyricsBeforeTheCut() {
        val lyrics = v3frame("USLT", uslt(3, byteArrayOf(0), "Before the cut".toByteArray()))
        val cut = v3frame("TIT2", byteArrayOf(3) + "A long title".toByteArray()).let { it.copyOf(it.size - 5) }
        assertEquals("Before the cut", Id3.lyrics(file(head(3, 5 * 1024 * 1024, 0x80), lyrics, cut)))
    }

    @Test fun v23UnsynchronisedTagOverTheLimitKeepsTheLyricsOfItsStart() {
        // A 5 MB cover after the lyrics: only the first 4 MB of the tag are read
        val lyrics = v3frame("USLT", uslt(3, byteArrayOf(0), "Before the cover".toByteArray()))
        val cover = v3frame("APIC", ByteArray(5 * 1024 * 1024))
        assertEquals("Before the cover", Id3.lyrics(file(head(3, lyrics.size + cover.size, 0x80), lyrics, cover)))
    }

    // MusicBrainz album id (TXXX: encoding, description, value)

    private val mbid = "f36eb891-6276-452a-8fdf-cde443a6f543"

    @Test fun v24Utf8AlbumId() {
        val txxx = { text: String -> rawFrame("TXXX", byteArrayOf(0, 0), byteArrayOf(3) + text.toByteArray()) }
        val f = tag(4, 0, ByteArray(0), frame("TIT2", "Ghosts IV", true), txxx("MusicBrainz Artist Id\u0000abc"),
            txxx("MusicBrainz Album Id\u0000$mbid"), ByteArray(20))
        assertEquals(mbid, Id3.musicBrainzAlbumId(f))
    }

    @Test fun v23Utf16AlbumIdWithABomOnEachString() {
        val utf16 = { t: String -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + t.toByteArray(Charsets.UTF_16LE) }
        val body = byteArrayOf(1) + utf16("MusicBrainz Album Id") + byteArrayOf(0, 0) + utf16(mbid)
        val v3 = "TXXX".toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        assertEquals(mbid, Id3.musicBrainzAlbumId(tag(3, 0, ByteArray(0), v3)))
    }

    @Test fun utf16AlbumIdWithOneBomForBothStrings() {
        // Only the description has a BOM (FF FE): the value has the same byte order
        val body = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + "MusicBrainz Album Id".toByteArray(Charsets.UTF_16LE) +
            byteArrayOf(0, 0) + mbid.toByteArray(Charsets.UTF_16LE)
        assertEquals(mbid, Id3.musicBrainzAlbumId(tag(3, 0, ByteArray(0), v3frame("TXXX", body))))
    }

    @Test fun v24ExtendedHeaderAndUnsynchronisedAlbumId() {
        val ext = byteArrayOf(0, 0, 0, 6, 1, 0)
        val body = byteArrayOf(1, 0xFF.toByte(), 0, 0xFE.toByte()) + "MusicBrainz Album Id".toByteArray(Charsets.UTF_16LE) +
            byteArrayOf(0, 0, 0xFF.toByte(), 0, 0xFE.toByte()) + mbid.toByteArray(Charsets.UTF_16LE)
        assertEquals(mbid, Id3.musicBrainzAlbumId(tag(4, 0xC0, ext, rawFrame("TXXX", byteArrayOf(0, 0), body))))
    }

    @Test fun noOrBadAlbumId() {
        assertNull(Id3.musicBrainzAlbumId(mp3(4, frame("TIT2", "x", true))))
        assertNull(Id3.musicBrainzAlbumId(tag(4, 0, ByteArray(0), rawFrame("TXXX", byteArrayOf(0, 0), byteArrayOf(3) + "MusicBrainz Album Id\u0000not-an-id".toByteArray()))))
        assertNull(Id3.musicBrainzAlbumId(file(byteArrayOf(0xFF.toByte(), 0xFB.toByte()), ByteArray(20))))
    }

    // Text strings

    @Test fun stringsSplitAtTheTerminatorOfTheEncoding() {
        assertEquals(listOf("a", "b"), Id3.strings(byteArrayOf(0) + "a\u0000b\u0000".toByteArray()))
        assertEquals(listOf("", "x\u0000y"), Id3.strings(byteArrayOf(3, 'l'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0) + "x\u0000y".toByteArray(), skip = 3, limit = 2))
        assertEquals(listOf("é"), Id3.strings(byteArrayOf(2, 0xFE.toByte(), 0xFF.toByte(), 0, 0xE9.toByte())))
        assertEquals(emptyList<String>(), Id3.strings(ByteArray(0)))
        assertEquals(emptyList<String>(), Id3.strings(byteArrayOf(1)))
    }
}
