package com.themoon.y1.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun game(parent: File, name: String, vararg extra: String): File {
        val dir = File(parent, name)
        File(dir, "Executables").mkdirs()
        File(dir, "Executables/game.bin").writeText("bin")
        extra.forEach { File(dir, it).writeText("x") }
        return dir
    }

    private fun ipg(file: File, vararg entries: String) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for (e in entries) {
                zip.putNextEntry(ZipEntry(e))
                if (!e.endsWith("/")) zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun aGameFolderHasABinInExecutables() {
        val root = tmp.newFolder("Games")
        game(root, "Tetris")
        File(root, "NoBin/Executables").mkdirs()
        File(root, "Notes").mkdirs()

        assertEquals(listOf("Tetris"), GameLibrary.scan(listOf(root)).map { it.title })
    }

    @Test
    fun aNestedGameFolderIsFound() {
        val root = tmp.newFolder("Games")
        val inner = game(File(root, "Zuma"), "Zuma")

        val games = GameLibrary.scan(listOf(root))
        assertEquals(listOf(inner), games.map { it.bundleDir })
        assertEquals(inner, GameLibrary.bundleDir(File(root, "Zuma")))
    }

    @Test
    fun anIpgUnpacksOnceIntoAFolder() {
        val root = tmp.newFolder("Games")
        ipg(File(root, "Vortex.ipg"), "Executables/", "Executables/vortex.bin", "iTunesArtwork")
        val extracted = mutableListOf<String>()

        val games = GameLibrary.scan(listOf(root)) { _, name -> name?.let { extracted += it } }
        assertEquals(listOf("Vortex"), games.map { it.title })
        assertEquals(File(root, "Vortex/iTunesArtwork"), games[0].iconFile)
        assertEquals(listOf("Vortex"), extracted)
        assertTrue(File(root, "Vortex/${GameLibrary.MARKER}").isFile)

        // The second scan uses the folder
        GameLibrary.scan(listOf(root)) { _, name -> name?.let { extracted += it } }
        assertEquals(listOf("Vortex"), extracted)
    }

    @Test
    fun aCutOffFolderIsUnpackedAgainAndKeepsTheSaves() {
        val root = tmp.newFolder("Games")
        ipg(File(root, "Tetris.ipg"), "Tetris/Executables/tetris.bin", "Tetris/Mu.m4a")
        // An older unpack that stopped: the files are there, but empty
        File(root, "Tetris/Tetris/Executables").mkdirs()
        File(root, "Tetris/Tetris/Executables/tetris.bin").createNewFile()
        File(root, "Tetris/Tetris/.fliwheel-saves").mkdirs()
        File(root, "Tetris/Tetris/.fliwheel-saves/save.dat").writeText("score")

        val ready = mutableListOf<List<String>>()
        val games = GameLibrary.scan(listOf(root)) { sofar, name -> if (name == null) ready += sofar.map { it.title } }
        // Not ready before it unpacks
        assertEquals(listOf(emptyList<String>()), ready)
        assertEquals(listOf("Tetris"), games.map { it.title })
        assertEquals(1L, File(root, "Tetris/Tetris/Executables/tetris.bin").length())
        assertEquals("score", File(root, "Tetris/Tetris/.fliwheel-saves/save.dat").readText())
    }

    @Test
    fun aCompleteFolderWithoutTheMarkerIsCheckedOnce() {
        val root = tmp.newFolder("Games")
        ipg(File(root, "Zuma.ipg"), "Executables/zuma.bin")
        game(root, "Zuma")
        File(root, "Zuma/Executables/game.bin").delete()
        File(root, "Zuma/Executables/zuma.bin").writeText("x")

        val unpacked = mutableListOf<String>()
        assertEquals(listOf("Zuma"), GameLibrary.scan(listOf(root)) { _, n -> n?.let { unpacked += it } }.map { it.title })
        assertTrue(unpacked.isEmpty())
        assertTrue(File(root, "Zuma/${GameLibrary.MARKER}").isFile)
    }

    @Test
    fun anEmptyBinIsNoGame() {
        val root = tmp.newFolder("Games")
        File(root, "Broken/Executables").mkdirs()
        File(root, "Broken/Executables/game.bin").createNewFile()
        assertNull(GameLibrary.parseGameDir(File(root, "Broken")))
    }

    @Test
    fun anIpgEntryOutsideTheGameFolderStopsTheUnpacking() {
        val root = tmp.newFolder("Games")
        val bad = File(root, "Bad.ipg")
        ipg(bad, "Executables/bad.bin", "../../evil.txt")

        assertFalse(GameLibrary.unpack(bad, File(root, "Bad")))
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(root.parentFile, "evil.txt").exists())
    }

    @Test
    fun aGameInTwoPlacesShowsOnce() {
        val a = tmp.newFolder("sdcard0", "Games")
        val b = tmp.newFolder("sdcard1", "Games")
        game(a, "Tetris")
        game(b, "tetris")
        game(b, "Bejeweled")

        // The same root twice (two mount paths) counts once too
        val games = GameLibrary.scan(listOf(a, b, File(a.path)))
        assertEquals(listOf("Bejeweled", "Tetris"), games.map { it.title })
    }

    @Test
    fun sortedByTitleIgnoringCase() {
        val root = tmp.newFolder("Games")
        listOf("zuma", "Mahjong", "bejeweled").forEach { game(root, it) }
        assertEquals(listOf("bejeweled", "Mahjong", "zuma"), GameLibrary.scan(listOf(root)).map { it.title })
    }

    @Test
    fun iconOrderAndTheParentFolder() {
        val root = tmp.newFolder("Games")
        assertEquals("iTunesArtwork", GameLibrary.parseGameDir(game(root, "A", "cover.jpg", "icon.png", "iTunesArtwork"))?.iconFile?.name)
        assertEquals("icon.png", GameLibrary.parseGameDir(game(root, "B", "cover.jpg", "icon.png"))?.iconFile?.name)
        assertNull(GameLibrary.parseGameDir(game(root, "C", "icon.raw.lcd5"))?.iconFile)
        // An empty art file (cut off) is no art
        File(root, "E").mkdirs()
        File(root, "E/iTunesArtwork").createNewFile()
        assertEquals("icon.png", GameLibrary.parseGameDir(game(root, "E", "icon.png"))?.iconFile?.name)

        val outer = File(root, "D").apply { mkdirs() }
        File(outer, "cover.jpg").writeText("x")
        val nested = GameLibrary.parseGameDir(game(outer, "D"))
        assertEquals(File(outer, "cover.jpg"), nested?.iconFile)
    }

    @Test
    fun missingRootsAreSkipped() {
        assertTrue(GameLibrary.scan(listOf(File(tmp.root, "none"))).isEmpty())
    }

    @Test
    fun saveDirUsesTheManifestGuidNextToTheGames() {
        val root = tmp.newFolder("Games")
        val bundle = game(File(root, "Tetris"), "Tetris")
        File(bundle, "Manifest.plist").writeText("<key>GUID</key>\n\t<string>66666</string>")
        assertEquals(File(root, "GameData_RW/66666").path, GameLibrary.saveDir(bundle, listOf(root)).path)
    }

    @Test
    fun saveDirFallsBackToTheFolderName() {
        val root = tmp.newFolder("Games")
        val bundle = game(root, "Zuma")
        assertEquals(File(root, "GameData_RW/Zuma").path, GameLibrary.saveDir(bundle, listOf(root)).path)
    }

    @Test
    fun saveDirStaysInsideTheSaveFolder() {
        val root = tmp.newFolder("Games")
        val bundle = game(root, "Evil")
        File(bundle, "Manifest.plist").writeText("<key>GUID</key><string>../../up</string>")
        assertEquals(File(root, "GameData_RW/.._.._up").path, GameLibrary.saveDir(bundle, listOf(root)).path)
    }

    @Test
    fun aDotGuidUsesTheFolderName() {
        val root = tmp.newFolder("Games")
        for (guid in listOf("..", ".", "  ")) {
            val bundle = game(root, "Evil")
            File(bundle, "Manifest.plist").writeText("<key>GUID</key><string>$guid</string>")
            assertEquals(File(root, "GameData_RW/Evil").path, GameLibrary.saveDir(bundle, listOf(root)).path)
        }
    }

    @Test
    fun theSaveFolderIsNotAGame() {
        val root = tmp.newFolder("Games")
        game(root, "Tetris")
        File(root, "GameData_RW/66666").mkdirs()
        File(root, "GameData_RW/66666/game.sav").writeText("save")
        assertEquals(listOf("Tetris"), GameLibrary.scan(listOf(root)).map { it.title })
    }
}
