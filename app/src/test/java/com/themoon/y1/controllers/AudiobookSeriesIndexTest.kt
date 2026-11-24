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
        val other = File(root, "Another Book").apply { mkdirs() }
        val otherAudio = File(other, "chapter.mp3").apply { writeText("audio") }
        val library = listOf(song(audio), song(otherAudio))

        assertEquals(series, AudiobookSeriesIndex.seriesFolderForFile(library, listOf(root), audio))
        val entries = AudiobookSeriesIndex.rootEntries(library, listOf(root), series.absolutePath, null)
        assertEquals("Saga", entries.first().title)
    }

    @Test fun foldersAboveListsSeriesThenBookFolder() {
        val root = Files.createTempDirectory("audiobooks").toFile()
        val book = File(File(root, "Saga"), "Book 1").apply { mkdirs() }
        val audio = File(book, "ch1.mp3").apply { writeText("audio") }
        assertEquals(listOf(File(root, "Saga"), book), AudiobookSeriesIndex.foldersAbove(listOf(root), audio))
        assertEquals(emptyList<File>(), AudiobookSeriesIndex.foldersAbove(listOf(root), File(root, "loose.mp3")))
    }

    private fun song(file: File) = SongItem(file, file.nameWithoutExtension, "Author", "Book")
}
