package com.themoon.y1.managers

import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaLibraryHiddenFilesTest {

    @Test
    fun hiddenNamesStartWithADot() {
        assertTrue(MediaLibraryManager.isHiddenName("._song.mp3"))
        assertTrue(MediaLibraryManager.isHiddenName(".Trashes"))
        assertFalse(MediaLibraryManager.isHiddenName("song.mp3"))
        assertFalse(MediaLibraryManager.isHiddenName("Mr. Big"))
    }

    @Test
    fun pathWithAHiddenFileOrFolderHasAHiddenPart() {
        assertTrue(MediaLibraryManager.hasHiddenPart("/storage/sdcard0/Music/Album/._song.mp3"))
        assertTrue(MediaLibraryManager.hasHiddenPart("/storage/sdcard1/Music/.Trashes/501/song.mp3"))
        assertFalse(MediaLibraryManager.hasHiddenPart("/storage/sdcard0/Music/Mr. Big/song.mp3"))
        assertFalse(MediaLibraryManager.hasHiddenPart("/storage/sdcard0/Music/Album/song.mp3"))
    }

    @Test
    fun scanSkipsAppleDoubleFilesAndHiddenFolders() {
        val dir = File(System.getProperty("java.io.tmpdir"), "classy_hidden_" + System.currentTimeMillis()).canonicalFile
        try {
            File(dir, ".Trashes").mkdirs()
            File(dir, "song.mp3").writeBytes(ByteArray(2048) { 1 })
            File(dir, "._song.mp3").writeBytes(ByteArray(4096) { 2 })
            File(dir, ".Trashes/old.mp3").writeBytes(ByteArray(2048) { 3 })

            val songs = ArrayList<SongItem>()
            val scanned = MediaLibraryManager.getInstance().scanDirectory(
                folder = dir,
                targetList = songs,
                existingMap = HashMap(),
                blacklist = emptySet(),
                startScanned = 0,
                total = 1,
                isAudiobook = false,
                listener = null
            )

            assertEquals(1, scanned)
            assertEquals(listOf("song.mp3"), songs.map { it.file.name })
        } finally {
            dir.deleteRecursively()
        }
    }
}
