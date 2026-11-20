package com.themoon.y1.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class PhotoIndexTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24L * 60 * 60 * 1000

    private fun photo(path: String, modified: Long = 0, date: Long = 0, added: Long = 0) =
        PhotoItem(path, 100, modified, date, 1, added)

    /** 2024-07-14 12:00 UTC and 2023-12-31 23:00 UTC. */
    private val july2024 = 1_720_958_400_000L
    private val dec2023 = 1_704_063_600_000L

    @Test
    fun aScanWithoutTheCardKeepsTheRecordsOfTheCard() {
        val internal = photo("/storage/sdcard0/Photos/a.jpg", added = 1)
        val card = photo("/storage/sdcard1/Photos/b.jpg", added = 2)
        val near = photo("/storage/sdcard0/Photos2/c.jpg")
        val records = listOf(internal, card, near)
        // Only the internal folder is walked: the card's record stays, and a folder that only starts the same too
        assertEquals(listOf(card, near), PhotoIndex.notSeen(records, listOf("/storage/sdcard0/Photos")))
        // Both walked: nothing is kept (the scan found again what is still there)
        assertEquals(listOf(near), PhotoIndex.notSeen(records, listOf("/storage/sdcard0/Photos/", "/storage/sdcard1/Photos")))
    }

    @Test
    fun aFolderThatFailedToListKeepsItsRecords() {
        val trip = photo("/s/Photos/Trip/a.jpg")
        val tripDeep = photo("/s/Photos/Trip/Day 1/b.jpg")
        val home = photo("/s/Photos/Home/c.jpg")
        val tripper = photo("/s/Photos/Tripper/d.jpg")
        val records = listOf(trip, tripDeep, home, tripper)
        // Home was listed (its missing photos go), Trip and its subfolders were not
        assertEquals(listOf(trip, tripDeep), PhotoIndex.notSeen(records, listOf("/s/Photos"), listOf("/s/Photos/Trip")))
    }

    @Test
    fun picturesOnly() {
        assertTrue(PhotoIndex.isPhoto("IMG_0001.JPG"))
        assertTrue(PhotoIndex.isPhoto("a.jpeg"))
        assertTrue(PhotoIndex.isPhoto("b.png"))
        assertTrue(PhotoIndex.isPhoto("c.webp"))
        assertFalse(PhotoIndex.isPhoto("._IMG_0001.JPG"))
        assertFalse(PhotoIndex.isPhoto(".hidden.jpg"))
        assertFalse(PhotoIndex.isPhoto("IMG_0002.HEIC"))
        assertFalse(PhotoIndex.isPhoto("notes.txt"))
        assertFalse(PhotoIndex.isPhoto("jpg"))
    }

    @Test
    fun allPhotosOldestFirstAndTheDateTakenComesFirst() {
        val photos = listOf(
            photo("/s/Photos/z.jpg", modified = 5),
            photo("/s/Photos/Trip/b.jpg", modified = 9, date = 1),
            photo("/s/Photos/Trip/a.jpg", modified = 2),
        )
        assertEquals(listOf("b.jpg", "a.jpg", "z.jpg"), PhotoIndex.select(photos, PhotoIndex.ALL, 0, utc).map { it.name })
    }

    @Test
    fun recentlyAddedIsTheLastThirtyDaysNewestFirst() {
        val now = 100 * day
        val photos = listOf(
            photo("/s/Photos/old.jpg", added = now - 31 * day),
            photo("/s/Photos/a.jpg", added = now - 2 * day),
            photo("/s/Photos/b.jpg", added = now - 1 * day),
        )
        assertEquals(listOf("b.jpg", "a.jpg"), PhotoIndex.select(photos, PhotoIndex.RECENT, now, utc).map { it.name })
    }

    @Test
    fun byDateGroupsYearsAndMonths() {
        val photos = listOf(
            photo("/s/Photos/a.jpg", date = july2024),
            photo("/s/Photos/b.jpg", date = july2024 + day),
            photo("/s/Photos/c.jpg", modified = dec2023),
        )
        assertEquals(listOf(2023 to 1, 2024 to 2), PhotoIndex.years(photos, utc))
        assertEquals(listOf(7 to 2), PhotoIndex.months(photos, 2024, utc))
        assertEquals(listOf(12 to 1), PhotoIndex.months(photos, 2023, utc))
        val key = PhotoIndex.month(2024, 7)
        assertEquals(2024 to 7, PhotoIndex.parseMonth(key))
        assertEquals(listOf("a.jpg", "b.jpg"), PhotoIndex.select(photos, key, 0, utc).map { it.name })
        assertNull(PhotoIndex.parseMonth(PhotoIndex.ALL))
    }

    @Test
    fun foldersShowOnlyFoldersWithPhotos() {
        val r = "/s/Photos"
        val photos = listOf(
            photo("$r/top.jpg"),
            photo("$r/Zoo/1.jpg"),
            photo("$r/beach/2.jpg"),
            photo("$r/beach/Day 2/3.jpg"),
            photo("$r/beach/Day 2/4.jpg"),
        )
        assertEquals(listOf("$r/beach" to 3, "$r/Zoo" to 1), PhotoIndex.subfolders(photos, r))
        assertEquals(listOf("$r/beach/Day 2" to 2), PhotoIndex.subfolders(photos, "$r/beach/"))
        assertTrue(PhotoIndex.subfolders(photos, "$r/Zoo").isEmpty())
        assertEquals(3, PhotoIndex.countUnder(photos, "$r/beach"))
        // A folder's Photos row: only the photos right in it; its Slideshow: also the deeper ones
        assertEquals(listOf("2.jpg"), PhotoIndex.select(photos, PhotoIndex.inFolder("$r/beach"), 0, utc).map { it.name })
        assertEquals(3, PhotoIndex.select(photos, PhotoIndex.underFolder("$r/beach"), 0, utc).size)
        assertEquals("$r/beach", PhotoIndex.folderOf(PhotoIndex.inFolder("$r/beach/")))
        // "/s/Photos/beach" must not match "/s/Photos/beachside"
        val more = photos + photo("$r/beachside/5.jpg")
        assertEquals(3, PhotoIndex.select(more, PhotoIndex.underFolder("$r/beach"), 0, utc).size)
    }

    @Test
    fun theIndexFileRoundTrips() {
        val photos = listOf(
            PhotoItem("/s/Photos/a b.jpg", 1234, 5678, 9012, 6, 777),
            PhotoItem("/s/Photos/Trip/ł.png", 1, 2, 0, 1, 2),
        )
        val back = PhotoIndex.decode(PhotoIndex.encode(photos))
        assertEquals(2, back.size)
        assertEquals("/s/Photos/a b.jpg", back[0].path)
        assertEquals(6, back[0].orientation)
        assertEquals(9012L, back[0].date)
        assertEquals(777L, back[0].added)
        assertTrue(back[1].isSameFile(1, 2))
        assertFalse(back[1].isSameFile(1, 3))
    }

    @Test
    fun aBrokenOrOldIndexGivesNoPhotos() {
        assertTrue(PhotoIndex.decode("").isEmpty())
        // Version 1 had the album in place of the found time: read again
        assertTrue(PhotoIndex.decode("classy-photos 1\n/a.jpg\t1\t2\t3\t1\t\n").isEmpty())
        assertEquals(1, PhotoIndex.decode("classy-photos 2\n/a.jpg\t1\t2\t3\t1\t4\nbroken line\n").size)
    }

    @Test
    fun exifDates() {
        assertEquals(1_000_000_000_000L, PhotoIndex.parseExifDate("2001:09:09 01:46:40", utc))
        assertEquals(0L, PhotoIndex.parseExifDate("0000:00:00 00:00:00", utc))
        assertEquals(0L, PhotoIndex.parseExifDate("    :  :     :  :  ", utc))
        assertEquals(0L, PhotoIndex.parseExifDate(null, utc))
    }
}
