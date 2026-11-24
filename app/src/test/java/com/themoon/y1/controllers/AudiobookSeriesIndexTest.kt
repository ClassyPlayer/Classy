package com.themoon.y1.controllers

import com.themoon.y1.controllers.coverflow.AudiobookSeriesIndex
import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudiobookSeriesIndexTest {
    @Test fun rootGroupsFoldersWithBookSubfoldersButKeepsLooseBooksSeparate() {
        val root = Files.createTempDirectory("audiobooks").toFile()
        val series = File(root, "The Example Series").apply { mkdirs() }
        val book2 = File(series, "02 - Second Book").apply { mkdirs() }
        val book1 = File(series, "01 - First Book").apply { mkdirs() }
        val standalone = File(root, "Standalone.m4b").apply { writeText("audio") }
        val first = File(book1, "01.mp3").apply { writeText("audio") }
        val second = File(book2, "01.mp3").apply { writeText("audio") }
        val singleFolder = File(root, "A Single Book").apply { mkdirs() }
        val single = File(singleFolder, "chapter.mp3").apply { writeText("audio") }
        val library = listOf(song(first), song(second), song(standalone), song(single))

        val entries = AudiobookSeriesIndex.rootEntries(library, listOf(root), null, null)
        assertEquals(3, entries.size)
        assertTrue(entries.any { it.isSeries && it.folder == series })
        assertTrue(entries.any { it.folder == null && it.audioFiles == listOf(standalone) })
        assertTrue(entries.any { !it.isSeries && it.folder == singleFolder })
    }

    @Test fun seriesBooksUseNumberedReadingOrderAndRememberLastBook() {
        val root = Files.createTempDirectory("audiobooks").toFile()
        val series = File(root, "Series").apply { mkdirs() }
        val book10 = File(series, "10 - Finale").apply { mkdirs() }
        val book2 = File(series, "2 - Middle").apply { mkdirs() }
        val file10 = File(book10, "a.mp3").apply { writeText("audio") }
        val file2 = File(book2, "a.mp3").apply { writeText("audio") }
        val library = listOf(song(file10), song(file2))

        val entries = AudiobookSeriesIndex.bookEntries(library, series, null)
        assertEquals(listOf("2 - Middle", "10 - Finale"), entries.map { it.title })
        val remembered = AudiobookSeriesIndex.bookEntries(library, series, file10.absolutePath)
        assertEquals("10 - Finale", remembered.first().title)
    }


    @Test fun recentlyPlayedSeriesIsPromotedAndPlaybackPathMapsBackToSeries() {
        val root = Files.createTempDirectory("audiobooks").toFile()
        val series = File(root, "Saga").apply { mkdirs() }
        val book = File(series, "Book 1").apply { mkdirs() }
        val audio = File(book, "chapter.mp3").apply { writeText("audio") }
        val book2 = File(series, "Book 2").apply { mkdirs() }
        val audio2 = File(book2, "chapter.mp3").apply { writeText("audio") }
        val other = File(root, "Another Book").apply { mkdirs() }
        val otherAudio = File(other, "chapter.mp3").apply { writeText("audio") }
        val library = listOf(song(audio), song(audio2), song(otherAudio))

        assertEquals(series, AudiobookSeriesIndex.seriesFolderForFile(library, listOf(root), audio))
        val entries = AudiobookSeriesIndex.rootEntries(library, listOf(root), series.absolutePath, null)
        assertEquals("Saga", entries.first().title)
    }

    @Test fun orderedFilesKeepDiscOrderAndPutUntaggedAfterNumbered() {
        val book = Files.createTempDirectory("book").toFile()
        val d1 = File(book, "Disc 1").apply { mkdirs() }
        val d2 = File(book, "Disc 2").apply { mkdirs() }
        val a = File(d1, "b.mp3"); val b = File(d1, "a.mp3"); val c = File(d2, "a.mp3"); val d = File(d1, "c.mp3")
        val tracks = mapOf(a to 2, b to 0, c to 1, d to 1)
        val ordered = AudiobookSeriesIndex.orderedFiles(listOf(c, b, a, d), book) { tracks[it] ?: 0 }
        assertEquals(listOf(d, a, b, c), ordered)
    }

    @Test fun foldersAboveListsSeriesThenBookFolder() {
        val root = Files.createTempDirectory("audiobooks").toFile()
        val book = File(File(root, "Saga"), "Book 1").apply { mkdirs() }
        val audio = File(book, "ch1.mp3").apply { writeText("audio") }
        assertEquals(listOf(File(root, "Saga"), book), AudiobookSeriesIndex.foldersAbove(listOf(root), audio))
        assertEquals(emptyList<File>(), AudiobookSeriesIndex.foldersAbove(listOf(root), File(root, "loose.mp3")))
    }

    @Test fun m4bFilesInASeriesFolderAreBooksAndALoneM4bFolderIsOneBook() {
        val root = Files.createTempDirectory("audiobooks").toFile()
        val series = File(root, "Saga").apply { mkdirs() }
        val one = File(series, "1 - One.m4b").apply { writeText("audio") }
        val two = File(series, "2 - Two.m4b").apply { writeText("audio") }
        val lone = File(root, "Lone").apply { mkdirs() }
        val loneFile = File(lone, "Lone Book.m4b").apply { writeText("audio") }
        val library = listOf(song(one), song(two), song(loneFile))

        val entries = AudiobookSeriesIndex.rootEntries(library, listOf(root), null, null)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.isSeries && it.folder == series })
        assertTrue(entries.any { !it.isSeries && it.audioFiles == listOf(loneFile) })
        assertEquals(series, AudiobookSeriesIndex.seriesFolderForFile(library, listOf(root), one))
        assertEquals(listOf("1 - One", "2 - Two"), AudiobookSeriesIndex.bookEntries(library, series, null).map { it.title })
        assertEquals(listOf(two), AudiobookSeriesIndex.bookEntries(library, series, null)[1].audioFiles)
    }

    @Test fun aSeriesFolderWithOneBookShowsAsThatBook() {
        val root = Files.createTempDirectory("audiobooks").toFile()
        val series = File(root, "Saga").apply { mkdirs() }
        val book = File(series, "Only Book").apply { mkdirs() }
        val audio = File(book, "ch1.mp3").apply { writeText("audio") }
        val library = listOf(song(audio))

        val entries = AudiobookSeriesIndex.rootEntries(library, listOf(root), null, null)
        assertEquals(1, entries.size)
        assertEquals("Only Book", entries[0].title)
        assertTrue(!entries[0].isSeries)
        assertEquals(book, entries[0].folder)
        assertEquals(null, AudiobookSeriesIndex.seriesFolderForFile(library, listOf(root), audio))
    }

    private fun song(file: File) = SongItem(file, file.nameWithoutExtension, "Author", "Book")
}
