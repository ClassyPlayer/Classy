package com.themoon.y1.controllers.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaylistPathsTest {
    private fun n(path: String) = PlaylistPaths.normalize(path)

    @Test
    fun removesDotParts() {
        assertEquals("/storage/sdcard0/Music/Artist/Album/Track.mp3", n("/storage/sdcard0/Music/./Artist/Album/Track.mp3"))
        assertEquals("/storage/sdcard0/Music/Artist/Track.mp3", n("/storage/sdcard0/Music//Artist/./Track.mp3"))
    }

    @Test
    fun removesDotDotWithThePartBeforeIt() {
        assertEquals("/storage/sdcard0/Music/Other/Track.mp3", n("/storage/sdcard0/Music/Playlists/../Other/Track.mp3"))
        assertEquals("/storage/sdcard0/Track.mp3", n("/storage/sdcard0/Music/Album/../../Track.mp3"))
        // Above the root: nothing to remove
        assertEquals("/Track.mp3", n("/../Track.mp3"))
    }

    @Test
    fun relativePathKeepsLeadingDotDot() {
        assertEquals("../Album/Track.mp3", n("../Album/./Track.mp3"))
        assertEquals("Album/Track.mp3", n("./Artist/../Album/Track.mp3"))
    }

    @Test
    fun plainPathStaysTheSame() {
        val path = "/storage/sdcard1/Music/Mr. Big/..Dots../Track.mp3"
        assertEquals(path, n(path))
    }

    @Test
    fun relativeEntryIsReadFromThePlaylistFolderFirst() {
        val root = Files.createTempDirectory("classy_m3u").toFile().canonicalFile
        try {
            val playlists = File(root, "Playlists").apply { mkdirs() }
            val music = File(root, "Music").apply { mkdirs() }
            File(music, "Album").mkdirs()
            File(music, "Album/Track.mp3").writeText("a")

            // A computer playlist: ../Music/... from the playlist's folder, with Windows backslashes
            assertEquals(File(playlists, "../Music/Album/Track.mp3"),
                PlaylistPaths.resolve("..\\Music\\Album\\Track.mp3", playlists, music))
            // Not next to the playlist: from the Music folder, as before
            assertEquals(File(music, "Album/Track.mp3"), PlaylistPaths.resolve("Album/Track.mp3", playlists, music))
            assertEquals(File(music, "Album/Track.mp3"), PlaylistPaths.resolve("Album/Track.mp3", null, music))
            // Absolute stays as it is
            assertEquals(File("/storage/sdcard1/Music/x.mp3"), PlaylistPaths.resolve("/storage/sdcard1/Music/x.mp3", playlists, music))
        } finally {
            root.walkBottomUp().forEach { Files.deleteIfExists(it.toPath()) }
        }
    }

    @Test
    fun sameNameGetsTheStorage() {
        val files = listOf(
            File("/storage/sdcard1/Playlists/On-The-Go.m3u8"),
            File("/storage/sdcard0/Playlists/on-the-go.m3u"),
            File("/storage/sdcard1/Playlists/Rock.m3u8"),
        )
        val labels = PlaylistPaths.labels(files) { if (it.path.startsWith("/storage/sdcard1")) "MicroSD Card" else "Internal Storage" }
        assertEquals(listOf("On-The-Go (MicroSD Card)", "on-the-go (Internal Storage)", "Rock"), labels)
    }

    @Test
    fun tellsAFileWithoutAFinalLineBreak() {
        val dir = Files.createTempDirectory("classy_m3u").toFile()
        try {
            fun file(text: String) = File(dir, "p${text.hashCode()}.m3u").apply { writeText(text) }
            assertTrue(PlaylistPaths.endsWithoutLineBreak(file("#EXTM3U\n../Music/A.mp3")))
            assertFalse(PlaylistPaths.endsWithoutLineBreak(file("#EXTM3U\n../Music/A.mp3\n")))
            assertFalse(PlaylistPaths.endsWithoutLineBreak(file("#EXTM3U\r\n..\\Music\\A.mp3\r\n")))
            assertFalse(PlaylistPaths.endsWithoutLineBreak(file("")))
            assertFalse(PlaylistPaths.endsWithoutLineBreak(File(dir, "missing.m3u")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pathAfterALinkIsNotTheSameFile() {
        val root = Files.createTempDirectory("classy_m3u").toFile().canonicalFile
        try {
            File(root, "Music/Album").mkdirs()
            File(root, "Other/Album").mkdirs()
            File(root, "Music/Track.mp3").writeText("a")
            File(root, "Other/Track.mp3").writeText("b")
            File(root, "Music/Album/Track.mp3").writeText("c")
            Files.createSymbolicLink(File(root, "Music/Link").toPath(), File(root, "Other/Album").toPath())

            // Music/Link/../Track.mp3 is Other/Track.mp3, not Music/Track.mp3
            val linked = File(root, "Music/Link/../Track.mp3")
            assertFalse(PlaylistPaths.sameFile(File(n(linked.path)), linked))
            val plain = File(root, "Music/Album/../Track.mp3")
            assertTrue(PlaylistPaths.sameFile(File(n(plain.path)), plain))
        } finally {
            root.walkBottomUp().forEach { Files.deleteIfExists(it.toPath()) }
        }
    }
}
