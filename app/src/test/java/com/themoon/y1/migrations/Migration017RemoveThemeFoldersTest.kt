package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Migration017RemoveThemeFoldersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun deletesTheFolderWithAllInIt() {
        val themes = File(tmp.root, "Y1_Themes")
        File(themes, "Classic Light").mkdirs()
        File(themes, "Classic Light/config.json").writeText("{}")
        File(themes, "Old.zip").writeText("zip")

        assertTrue(Migration017RemoveThemeFolders.delete(themes))
        assertFalse(themes.exists())
        assertTrue(tmp.root.exists())
    }

    @Test
    fun aMissingFolderIsNoProblem() {
        assertFalse(Migration017RemoveThemeFolders.delete(File(tmp.root, "Y1_Themes")))
    }

    @Test
    fun runsAfterMigration16() {
        val manager = MigrationManager(null, MoveMisplacedPrefsTest.FakePrefs())
        manager.registerMigration(Migration017RemoveThemeFolders())
        manager.registerMigration(Migration016PhotosMenuItem())
        assertEquals(listOf(16, 17), manager.getRegisteredMigrations().map { it.version })
    }
}
