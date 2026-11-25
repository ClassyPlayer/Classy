package com.themoon.y1.controllers.coverflow

import com.themoon.y1.controllers.coverflow.CoverFlowAlbums.Filter
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverFlowAlbumsTest {

    @After fun resetGrouping() { AlbumKey.byArtist = false }

    private fun track(album: String, n: Int, type: String = "", artist: String = "Artist") =
        SongItem(File("/Music/$artist/$album/$n.mp3"), "T$n", artist, album, "2020", "Pop", artist, "").also { it.releaseType = type }

    private val library = listOf(
        track("LP", 1, "album"), track("LP", 2, "album"), track("LP", 3, "album"),
        track("Hit", 1, "single"),
        track("Four Songs", 1, "ep"), track("Four Songs", 2, "ep"),
        track("Untagged Pair", 1), track("Untagged Pair", 2),
        track("Store - Single", 1),
        track("Live Show", 1, "album;live")
    )

    private fun shown(filter: Filter) = CoverFlowAlbums.select(library, filter).map { it.album }

    @Test fun allShowsEveryAlbum() =
        assertEquals(listOf("LP", "Hit", "Four Songs", "Untagged Pair", "Store - Single", "Live Show"), shown(Filter.ALL))

    @Test fun noSinglesHidesTaggedAndTitledSingles() =
        assertEquals(listOf("LP", "Four Songs", "Untagged Pair", "Live Show"), shown(Filter(false, true, 0)))

    @Test fun noEpsHidesOnlyEps() =
        assertEquals(listOf("LP", "Hit", "Untagged Pair", "Store - Single", "Live Show"), shown(Filter(true, false, 0)))

    @Test fun untaggedTrackCountHidesShortUntaggedAlbums() =
        assertEquals(listOf("LP", "Live Show"), shown(Filter(false, false, 3)))

    @Test fun firstSongOfEachAlbumIsKept() {
        val first = CoverFlowAlbums.select(library, Filter.ALL).first()
        assertEquals("T1", first.title)
    }

    @Test fun albumsWithTheSameTitleSplitByArtistWhenGroupedSo() {
        AlbumKey.byArtist = true
        val songs = listOf(track("Same", 1, "single", "A"), track("Same", 1, "album", "B"))
        assertEquals(listOf("B"), CoverFlowAlbums.select(songs, Filter(false, true, 0)).map { it.artist })
    }

    @Test fun summaryNamesWhatShows() {
        assertEquals("All Albums", CoverFlowAlbums.summary(Filter.ALL))
        assertEquals("No Singles", CoverFlowAlbums.summary(Filter(false, true, 3)))
        assertEquals("No EPs", CoverFlowAlbums.summary(Filter(true, false, 0)))
        assertEquals("Albums Only", CoverFlowAlbums.summary(Filter(false, false, 0)))
    }

    @Test fun untaggedSteps() {
        assertEquals(CoverFlowAlbums.UNTAGGED_SINGLE_TRACKS.size, CoverFlowAlbums.UNTAGGED_SINGLE_LABELS.size)
        assertEquals(listOf(0, 1, 3, 5), (0..3).map { CoverFlowAlbums.untaggedSingleTracks(it) })
        assertEquals(0, CoverFlowAlbums.untaggedSingleTracks(9))
        assertEquals(0, CoverFlowAlbums.validUntaggedStep(-1))
        assertEquals(2, CoverFlowAlbums.validUntaggedStep(2))
    }

    @Test fun filterShowsOtherTypesAlways() {
        val f = Filter(false, false, 0)
        assertFalse(f.showsAll)
        assertTrue(f.shows(null))
        assertTrue(f.shows(com.themoon.y1.models.ReleaseTypes.Primary.BROADCAST))
    }
}
