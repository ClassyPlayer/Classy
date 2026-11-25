package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The release type readers of the Vorbis comments (FLAC, Ogg, Opus) and of the MP4 freeform atoms. */
class ReleaseTypeTagsTest {

    @Test fun vorbisCommentsAreJoined() {
        val tags = arrayOfNulls<Any>(14)
        appendReleaseType(tags, "RELEASETYPE=album")
        appendReleaseType(tags, "releasetype= live ")
        appendReleaseType(tags, "MUSICBRAINZ_ALBUMTYPE=")
        assertEquals("album;live", tags[13])
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    /** The body of a "----" atom: mean and name (4 bytes of version and flags first), then data (type and locale first). */
    private fun freeform(name: String, vararg values: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("mean", ByteArray(4) + "com.apple.iTunes".toByteArray()))
        out.write(box("name", ByteArray(4) + name.toByteArray()))
        for (v in values) out.write(box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + v.toByteArray()))
        return out.toByteArray()
    }

    @Test fun mp4MusicBrainzAlbumType() {
        assertEquals("single", mp4FreeformReleaseType(freeform("MusicBrainz Album Type", "single")))
        assertEquals("album;compilation", mp4FreeformReleaseType(freeform("MusicBrainz Album Type", "album", "compilation")))
        assertEquals("ep", mp4FreeformReleaseType(freeform("RELEASETYPE", "ep")))
    }

    @Test fun mp4OtherFreeformAtomsAreNotReleaseTypes() {
        assertNull(mp4FreeformReleaseType(freeform("MusicBrainz Album Id", "1b2c3d4e-0000-0000-0000-000000000000")))
        assertNull(mp4FreeformReleaseType(freeform("MusicBrainz Album Type")))
    }

    @Test fun brokenMp4AtomGivesNothing() {
        val body = freeform("MusicBrainz Album Type", "single")
        assertNull(mp4FreeformReleaseType(body.copyOf(body.size - 3)))
        assertNull(mp4FreeformReleaseType(ByteArray(0)))
    }
}
