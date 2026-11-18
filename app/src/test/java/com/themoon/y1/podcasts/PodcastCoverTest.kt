package com.themoon.y1.podcasts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PodcastCoverTest {

    /** The start of a PNG: signature and IHDR of [width] x [height] with [interlace] (0 none, 1 Adam7). */
    private fun pngHead(width: Int, height: Int, interlace: Int): ByteArray {
        val bytes = ByteArray(33)
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(bytes)
        bytes[11] = 13
        "IHDR".forEachIndexed { i, c -> bytes[12 + i] = c.code.toByte() }
        for (i in 0..3) {
            bytes[16 + i] = (width ushr (24 - 8 * i)).toByte()
            bytes[20 + i] = (height ushr (24 - 8 * i)).toByte()
        }
        bytes[24] = 8
        bytes[25] = 6
        bytes[28] = interlace.toByte()
        return bytes
    }

    @Test
    fun interlacedPngIsFoundInTheHeader() {
        assertTrue(PodcastCover.isInterlacedPng(pngHead(3000, 3000, 1)))
        assertFalse(PodcastCover.isInterlacedPng(pngHead(3000, 3000, 0)))
    }

    @Test
    fun otherFormatsAndShortDataAreNotInterlacedPngs() {
        val jpeg = ByteArray(40).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[28] = 1 }
        assertFalse(PodcastCover.isInterlacedPng(jpeg))
        assertFalse(PodcastCover.isInterlacedPng(pngHead(10, 10, 1).copyOf(20)))
    }

    @Test
    fun imagesDecodedWholeHaveTheLowerPixelLimit() {
        assertFalse(PodcastCover.canDecode("image/png", 3000, 3000, pngHead(3000, 3000, 1)))
        assertTrue(PodcastCover.canDecode("image/png", 2048, 2048, pngHead(2048, 2048, 1)))
        assertFalse(PodcastCover.canDecode("image/gif", 3000, 3000, ByteArray(PodcastCover.HEAD_BYTES)))
        assertTrue(PodcastCover.canDecode("image/gif", 1400, 1400, ByteArray(PodcastCover.HEAD_BYTES)))
    }

    @Test
    fun otherImagesHaveASourceLimitThatFeedCoversFit() {
        val jpeg = ByteArray(PodcastCover.HEAD_BYTES).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        assertTrue(PodcastCover.canDecode("image/jpeg", 3000, 3000, jpeg))
        assertTrue(PodcastCover.canDecode("image/png", 3000, 3000, pngHead(3000, 3000, 0)))
        assertTrue(PodcastCover.canDecode("image/jpeg", 4096, 4096, jpeg))
        // A progressive JPEG keeps its whole coefficients while it decodes, at any sample size
        assertFalse(PodcastCover.canDecode("image/jpeg", 8000, 8000, jpeg))
        assertFalse(PodcastCover.canDecode("image/jpeg", 12000, 1500, jpeg))
    }

    @Test
    fun anImageWithNoSizeIsNotDecoded() {
        assertFalse(PodcastCover.canDecode(null, -1, -1, ByteArray(PodcastCover.HEAD_BYTES)))
        assertFalse(PodcastCover.canDecode("image/jpeg", 0, 500, ByteArray(PodcastCover.HEAD_BYTES)))
    }

    @Test
    fun sampleKeepsTheLongerSideAtTheTargetOrMore() {
        assertEquals(4, PodcastCover.sampleSize(3000, 3000))
        assertEquals(1, PodcastCover.sampleSize(500, 500))
        assertEquals(1, PodcastCover.sampleSize(999, 400))
        assertEquals(2, PodcastCover.sampleSize(1000, 400))
        // A very wide image is sampled by its longer side
        assertEquals(16, PodcastCover.sampleSize(12000, 800))
    }

    @Test
    fun fitScalesDownToTheMaxSideOnly() {
        assertEquals(500 to 500, PodcastCover.fit(750, 750))
        assertEquals(500 to 333, PodcastCover.fit(750, 500))
        assertEquals(300 to 200, PodcastCover.fit(300, 200))
        assertEquals(500 to 1, PodcastCover.fit(12000, 10))
    }

    @Test
    fun onlyASmallJpegIsKept() {
        assertTrue(PodcastCover.isKept("image/jpeg", 500, 500))
        assertFalse(PodcastCover.isKept("image/jpeg", 3000, 3000))
        assertFalse(PodcastCover.isKept("image/png", 400, 400))
        assertFalse(PodcastCover.isKept(null, -1, -1))
    }

    @Test
    fun readAtMostStopsOverTheLimit() {
        val data = ByteArray(40_000) { it.toByte() }
        assertArrayEquals(data, PodcastCover.readAtMost(ByteArrayInputStream(data), 40_000))
        assertNull(PodcastCover.readAtMost(ByteArrayInputStream(data), 39_999))
    }
}
