package com.themoon.y1.controllers.playlists

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
