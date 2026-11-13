package com.themoon.y1.views

import com.themoon.y1.controllers.videos.VideoScrub
import com.themoon.y1.views.IpodClassicMetrics as M
import com.themoon.y1.views.ScrubBarGeometry as G
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubBarGeometryTest {

    // The progress bar of the normal row, in row coordinates (the time label box is the row height).
    private val rowHeight = M.TIME_BOX_HEIGHT
    private val normalBar = G.Box(
        M.PROGRESS_LEFT.toFloat(), G.centredTop(rowHeight, M.PROGRESS_HEIGHT).toFloat(),
        (M.PROGRESS_LEFT + M.PROGRESS_WIDTH).toFloat(), (G.centredTop(rowHeight, M.PROGRESS_HEIGHT) + M.PROGRESS_HEIGHT).toFloat()
    )
    private val ox = G.outsetX(M.SCRUB_DIAMOND_WIDTH)
    private val oy = G.outsetY(M.SCRUB_DIAMOND_HEIGHT, M.PROGRESS_HEIGHT)
    private val viewWidth = M.PROGRESS_WIDTH + 2 * ox
    private val viewHeight = M.PROGRESS_HEIGHT + 2 * oy

    /** The scrub bar in row coordinates, laid out like IpodClassicNowPlaying.styleScrubBar does. */
    private fun scrubBar(): G.Box {
        // Left time label from TIME_LEFT to PROGRESS_LEFT, then the view with a left margin of -ox.
        val viewLeft = M.TIME_LEFT + (M.PROGRESS_LEFT - M.TIME_LEFT) - ox
        val viewTop = G.centredTop(rowHeight, viewHeight)
        return G.barInView(viewWidth, viewHeight, ox, oy).offset(viewLeft.toFloat(), viewTop.toFloat())
    }

    @Test
    fun scrubBarHasTheSameRectangleAsTheProgressBar() {
        assertEquals(normalBar, scrubBar())
        // The right time label starts where it starts in the normal row (the right margin is -ox).
        val viewLeft = M.PROGRESS_LEFT - ox
        assertEquals(M.PROGRESS_LEFT + M.PROGRESS_WIDTH, viewLeft + viewWidth - ox)
    }

    @Test
    fun viewFrameIsTheBarPlusTheOutsets() {
        val frame = G.viewFrame(normalBar, M.SCRUB_DIAMOND_WIDTH, M.SCRUB_DIAMOND_HEIGHT)
        assertEquals(viewWidth.toFloat(), frame.width, 0f)
        assertEquals(viewHeight.toFloat(), frame.height, 0f)
        assertEquals(normalBar, G.barInView(viewWidth, viewHeight, ox, oy).offset(frame.left, frame.top))
        // The view is not taller than the row, so the row (and the times) do not move.
        assertTrue(viewHeight <= rowHeight)
    }

    @Test
    fun diamondIsCentredOnTheFillEndAndTheBarCentre() {
        val bar = G.Box(0f, 0f, M.PROGRESS_WIDTH.toFloat(), M.PROGRESS_HEIGHT.toFloat())
        for ((progress, max) in listOf(0 to 1000, 1 to 1000, 370 to 1000, 500 to 1000, 999 to 1000, 1000 to 1000, 123456 to 243000)) {
            val d = G.diamond(bar, progress, max, M.SCRUB_DIAMOND_WIDTH, M.SCRUB_DIAMOND_HEIGHT)
            assertEquals(G.fillEnd(bar, progress, max), d.centreX, 0f)
            assertEquals(bar.centreY, d.centreY, 0f)
            assertEquals(M.SCRUB_DIAMOND_WIDTH.toFloat(), d.width, 0f)
            assertEquals(M.SCRUB_DIAMOND_HEIGHT.toFloat(), d.height, 0f)
        }
        // Exact vertical centre: the diamond goes the same distance above and below the bar.
        val d = G.diamond(bar, 500, 1000, M.SCRUB_DIAMOND_WIDTH, M.SCRUB_DIAMOND_HEIGHT)
        assertEquals(bar.top - d.top, d.bottom - bar.bottom, 0f)
        assertEquals(oy.toFloat(), bar.top - d.top, 0f)
    }

    @Test
    fun fillEndIsTheEndOfTheClipDrawableFill() {
        val bar = G.Box(0f, 0f, M.PROGRESS_WIDTH.toFloat(), M.PROGRESS_HEIGHT.toFloat())
        assertEquals(0f, G.fillEnd(bar, 0, 1000), 0f)
        assertEquals(M.PROGRESS_WIDTH.toFloat(), G.fillEnd(bar, 1000, 1000), 0f)
        assertEquals(153f, G.fillEnd(bar, 500, 1000), 0f)
        // API 19 ClipDrawable: w -= w * (10000 - level) / 10000 (integer maths).
        val level = G.level(370, 1000)
        assertEquals(3700, level)
        assertEquals((306 - 306 * (10000 - level) / 10000).toFloat(), G.fillEnd(bar, 370, 1000), 0f)
    }

    @Test
    fun progressOutOfRangeIsClamped() {
        val bar = G.Box(0f, 0f, M.PROGRESS_WIDTH.toFloat(), M.PROGRESS_HEIGHT.toFloat())
        assertEquals(0f, G.fillEnd(bar, -5, 1000), 0f)
        assertEquals(M.PROGRESS_WIDTH.toFloat(), G.fillEnd(bar, 2000, 1000), 0f)
        assertEquals(0f, G.fillEnd(bar, 10, 0), 0f)
    }

    @Test
    fun diamondStaysInsideTheViewAtZeroAndOneHundredPercent() {
        val bar = G.barInView(viewWidth, viewHeight, ox, oy)
        val view = G.Box(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        for (progress in intArrayOf(0, 1, 500, 999, 1000)) {
            val d = G.diamond(bar, progress, 1000, M.SCRUB_DIAMOND_WIDTH, M.SCRUB_DIAMOND_HEIGHT)
            assertTrue("diamond at $progress / 1000 is clipped: $d", view.contains(d))
        }
        // At 0 % and 100 % the diamond centre is on the bar end, not moved inwards.
        assertEquals(bar.left, G.diamond(bar, 0, 1000, M.SCRUB_DIAMOND_WIDTH, M.SCRUB_DIAMOND_HEIGHT).centreX, 0f)
        assertEquals(bar.right, G.diamond(bar, 1000, 1000, M.SCRUB_DIAMOND_WIDTH, M.SCRUB_DIAMOND_HEIGHT).centreX, 0f)
    }

    @Test
    fun videoScrubBarHasTheSameRectangleAsTheProgressBar() {
        // activity_main.xml: the 20 dp video progress bar (1 dp = 1 px on the Y2) with layout weight 1 between the
        // times. ScrubBarStyle gives the scrub bar negative margins on all sides (marginsY), so the row measures only
        // the bar height, and the weight gives the view the bar width plus the two side outsets.
        val barWidth = 300
        val barHeight = 20
        val w = VideoScrub.DIAMOND_WIDTH_DP
        val h = VideoScrub.DIAMOND_HEIGHT_DP
        val ox = G.outsetX(w)
        val oy = G.outsetY(h, barHeight)
        val viewHeight = barHeight + 2 * oy
        assertEquals(barHeight, viewHeight - 2 * oy)

        // LinearLayout center_vertical: (row - child) / 2 + topMargin - bottomMargin; equal margins cancel.
        // The row is the bar height (the 14 sp times are lower); an even row height also keeps the bars together.
        for (rowHeight in intArrayOf(barHeight, 22, 24, 30)) {
            val progressTop = G.centredTop(rowHeight, barHeight)
            val progress = G.Box(0f, progressTop.toFloat(), barWidth.toFloat(), (progressTop + barHeight).toFloat())
            val viewTop = G.centredTop(rowHeight, viewHeight)
            val bar = G.barInView(barWidth + 2 * ox, viewHeight, ox, oy).offset(-ox.toFloat(), viewTop.toFloat())
            assertEquals("row $rowHeight", progress, bar)
        }

        // The diamond is on the bar centre line, oy above and below the bar, and inside the view at 0 % and 100 %.
        val bar = G.barInView(barWidth + 2 * ox, viewHeight, ox, oy)
        val view = G.Box(0f, 0f, (barWidth + 2 * ox).toFloat(), viewHeight.toFloat())
        for (progress in intArrayOf(0, 1, 500, 999, 1000)) {
            val d = G.diamond(bar, progress, 1000, w, h)
            assertEquals(bar.centreY, d.centreY, 0f)
            assertEquals(oy.toFloat(), bar.top - d.top, 0f)
            assertTrue("diamond at $progress / 1000 is clipped: $d", view.contains(d))
        }
    }
}
