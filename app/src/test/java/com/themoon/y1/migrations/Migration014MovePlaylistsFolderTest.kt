package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Migration014MovePlaylistsFolderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun legacy(): File = File(tmp.root, "sdcard0/Y1_Playlists").apply { mkdirs() }
    private fun target(): File = File(tmp.root, "sdcard1/Playlists")

    @Test
    fun filesMoveAndTheOldFolderGoes() {
        val old = legacy()
        File(old, "Rock.m3u8").writeText("#EXTM3U\n/a.mp3\n")
        File(old, "On-The-Go.m3u8").writeText("#EXTM3U\n/b.mp3\n")

        assertEquals(2, Migration014MovePlaylistsFolder.move(old, target()))

        assertEquals("#EXTM3U\n/a.mp3\n", File(target(), "Rock.m3u8").readText())
        assertEquals("#EXTM3U\n/b.mp3\n", File(target(), "On-The-Go.m3u8").readText())
        assertFalse(old.exists())
    }

    @Test
    fun aTakenNameGetsANumber() {
        val old = legacy()
        File(old, "Rock.m3u8").writeText("old")
        target().mkdirs()
        File(target(), "Rock.m3u8").writeText("pc")
        File(target(), "Rock 2.m3u8").writeText("pc 2")

        Migration014MovePlaylistsFolder.move(old, target())

        assertEquals("pc", File(target(), "Rock.m3u8").readText())
        assertEquals("pc 2", File(target(), "Rock 2.m3u8").readText())
        assertEquals("old", File(target(), "Rock 3.m3u8").readText())
    }

    @Test
    fun foldersAndOtherFilesMoveToo() {
        val old = legacy()
        File(old, "Sub").mkdirs()
        File(old, "Sub/Jazz.m3u").writeText("j")
        File(old, "notes.txt").writeText("n")

        assertEquals(2, Migration014MovePlaylistsFolder.move(old, target()))

        assertEquals("j", File(target(), "Sub/Jazz.m3u").readText())
        assertEquals("n", File(target(), "notes.txt").readText())
        assertFalse(old.exists())
    }

    @Test
    fun noOldFolderDoesNothing() {
        val old = File(tmp.root, "sdcard0/Y1_Playlists")
        assertEquals(0, Migration014MovePlaylistsFolder.move(old, target()))
        assertFalse(old.exists())
        assertFalse(target().exists())
    }

    @Test
    fun aSecondRunIsHarmless() {
        val old = legacy()
        File(old, "Rock.m3u8").writeText("r")
        Migration014MovePlaylistsFolder.move(old, target())
        assertEquals(0, Migration014MovePlaylistsFolder.move(old, target()))
        assertEquals(listOf("Rock.m3u8"), target().list()!!.toList())
    }

    @Test
    fun freeNameWithoutExtension() {
        val dir = tmp.newFolder("d")
        File(dir, "README").writeText("x")
        assertEquals("README 2", Migration014MovePlaylistsFolder.freeName(dir, "README").name)
        assertTrue(Migration014MovePlaylistsFolder.freeName(dir, "New.m3u").name == "New.m3u")
    }
}
