package com.themoon.y1

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistsDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cardWhenItCanBeWritten() {
        val card = tmp.newFolder("sdcard1")
        val internal = tmp.newFolder("sdcard0")
        assertEquals(File(card, "Playlists"), StoragePaths.choosePlaylistsDir(card, internal))
        assertEquals(true, File(card, "Playlists").isDirectory)
    }

    @Test
    fun internalWithoutACard() {
        val internal = tmp.newFolder("sdcard0")
        assertEquals(File(internal, "Playlists"), StoragePaths.choosePlaylistsDir(null, internal))
    }

    @Test
    fun internalWhenTheCardIsReadOnly() {
        // Root can write to a read-only folder, so the check means nothing there
        assumeFalse(System.getProperty("user.name") == "root")
        val card = tmp.newFolder("sdcard1")
        val internal = tmp.newFolder("sdcard0")
        card.setWritable(false)
        try {
            assertEquals(File(internal, "Playlists"), StoragePaths.choosePlaylistsDir(card, internal))
            assertEquals(false, File(card, "Playlists").exists())
        } finally {
            card.setWritable(true)
        }
    }

    @Test
    fun internalWhenThePlaylistsFolderOnTheCardIsReadOnly() {
        assumeFalse(System.getProperty("user.name") == "root")
        val card = tmp.newFolder("sdcard1")
        val internal = tmp.newFolder("sdcard0")
        val onCard = File(card, "Playlists").apply { mkdirs() }
        onCard.setWritable(false)
        try {
            assertEquals(File(internal, "Playlists"), StoragePaths.choosePlaylistsDir(card, internal))
        } finally {
            onCard.setWritable(true)
        }
    }
}
