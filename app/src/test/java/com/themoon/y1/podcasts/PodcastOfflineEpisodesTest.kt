package com.themoon.y1.podcasts

import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PodcastOfflineEpisodesTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val channel = "News: Daily"

    /** An episode as [PodcastRepository.fetchEpisodes] makes it. */
    private fun episode(title: String, date: String = "Mon, 02 Nov 2026") =
        SongItem(File("/PODCAST"), title, channel, "https://img/$title.jpg", date, "https://audio/$title.mp3")

    private fun download(name: String, bytes: Int = 10, modified: Long = 1_000L): File =
        File(tmp.root, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    private fun files() = tmp.root.listFiles()!!.toList()

    @Test
    fun fileName_isTheNameOfTheDownload() {
        assertEquals("Part 1_2_ What_.mp3", PodcastOfflineEpisodes.fileName("Part 1/2: What?"))
        assertEquals("News_ Daily", PodcastOfflineEpisodes.safeName(channel))
    }

    @Test
    fun keptList_survivesJson() {
        val kept = listOf(episode("A \"quoted\"\ttitle"), episode("B", date = ""))
        val back = PodcastOfflineEpisodes.fromJson(PodcastOfflineEpisodes.toJson(kept), channel)
        assertEquals(kept.map { it.title }, back.map { it.title })
        assertEquals(kept.map { it.year }, back.map { it.year })
        assertTrue(back.all { it.artist == channel })
    }

    @Test
    fun keptList_hasNoUrls() {
        val json = PodcastOfflineEpisodes.toJson(listOf(episode("A")))
        assertTrue(json, !json.contains("https"))
    }

    @Test
    fun damagedJson_givesNoEpisodes() {
        assertTrue(PodcastOfflineEpisodes.fromJson("[{\"title\":\"A\"", channel).isEmpty())
        assertTrue(PodcastOfflineEpisodes.fromJson("", channel).isEmpty())
    }

    @Test
    fun keptEpisodesWithADownload_comeFirstInFeedOrder_thenOtherDownloadsNewestFirst() {
        download("Ep 3.mp3", modified = 1_000L)
        download("Ep 1.mp3", modified = 3_000L)
        download("Old one.mp3", modified = 2_000L)
        download("Older one.mp3", modified = 500L)
        val kept = listOf(episode("Ep 3"), episode("Ep 2"), episode("Ep 1"))

        val list = PodcastOfflineEpisodes.list(channel, files(), kept)

        assertEquals(listOf("Ep 3", "Ep 1", "Old one", "Older one"), list.map { it.title })
        // A kept episode keeps its date; another download has no date (no "[Unknown Year]" prefix)
        assertEquals("Mon, 02 Nov 2026", list[0].year)
        assertEquals("", list[2].year)
        assertTrue(list.all { it.artist == channel })
        assertEquals(File(tmp.root, "Ep 3.mp3"), list[0].file)
    }

    @Test
    fun keptTitleWithUnsafeCharacters_findsItsDownload() {
        download(PodcastOfflineEpisodes.fileName("Q&A: part 1/2"))
        val list = PodcastOfflineEpisodes.list(channel, files(), listOf(episode("Q&A: part 1/2")))
        assertEquals(listOf("Q&A: part 1/2"), list.map { it.title })
    }

    @Test
    fun partialEmptyHiddenAndOtherFiles_areSkipped() {
        download(".podcast-123.part")
        download("Empty.mp3", bytes = 0)
        download("._Done.mp3")
        download("Done.mp3")
        download("cover.jpg")
        download(PodcastOfflineEpisodes.LIST_FILE)
        File(tmp.root, "Folder.mp3").mkdir()

        val list = PodcastOfflineEpisodes.list(channel, files(), emptyList())

        assertEquals(listOf("Done"), list.map { it.title })
    }

    @Test
    fun keptTitleTwice_givesOneRow() {
        download("Ep 1.mp3")
        val list = PodcastOfflineEpisodes.list(channel, files(), listOf(episode("Ep 1"), episode("Ep 1")))
        assertEquals(1, list.size)
    }

    @Test
    fun keptEpisodeWhoseTitleStartsWithADot_isListed() {
        download(".NET news.mp3")
        val list = PodcastOfflineEpisodes.list(channel, files(), listOf(episode(".NET news")))
        assertEquals(listOf(".NET news"), list.map { it.title })
    }
}
