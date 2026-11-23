package com.themoon.y1.photos

import kotlin.math.roundToInt

/**
 * The layout of the Photos screens. The sizes are in base units of a 320 x 240 screen and scaled to the player's
 * screen ([scale] = width / 320: 1.5 on the 480 x 360 Y2), as [com.themoon.y1.views.ClassyMetrics] does for the rest
 * of the UI. No Android types: the maths is unit-tested.
 */
class PhotoGeometry(val width: Int, val height: Int, val top: Int) {

    /** Screen pixels per base unit (1.5 on the Y2). */
    val scale: Float = width / BASE_WIDTH

    /** The grid area: under the status bar ([top]) to the bottom of the screen. */
    val gridHeight: Int get() = (height - top).coerceAtLeast(1)

    /** Rows that fit on one page: 4 on the Y2. */
    val rows: Int get() = (gridHeight / (CELL_HEIGHT * scale)).toInt().coerceAtLeast(1)

    val columns: Int get() = COLUMNS

    /** Thumbnails on one page. */
    val pageSize: Int get() = rows * columns

    private val cellWidth: Float get() = width / columns.toFloat()
    private val cellHeight: Float get() = gridHeight / rows.toFloat()

    /** The thumbnail rect of grid cell [index] when the page starts at row [firstRow]: (left, top, right, bottom). */
    fun thumbRect(index: Int, firstRow: Int): IntArray {
        val row = index / columns - firstRow
        val col = index % columns
        val inset = GAP * scale
        val l = col * cellWidth + inset
        val t = top + row * cellHeight + inset
        return intArrayOf(l.roundToInt(), t.roundToInt(), (l + cellWidth - 2 * inset).roundToInt(), (t + cellHeight - 2 * inset).roundToInt())
    }

    /** The width of the selection frame in pixels (2 points). */
    val frameWidth: Int get() = (FRAME * scale).roundToInt().coerceAtLeast(1)

    /** The first row to show so that [index] is on the page (the page moves a row at a time). */
    fun firstRowFor(index: Int, firstRow: Int, count: Int): Int {
        val row = index / columns
        val lastRow = ((count - 1).coerceAtLeast(0)) / columns
        val maxFirst = (lastRow - rows + 1).coerceAtLeast(0)
        val first = when {
            row < firstRow -> row
            row >= firstRow + rows -> row - rows + 1
            else -> firstRow
        }
        return first.coerceIn(0, maxFirst)
    }

    companion object {
        /** The base screen of the sizes below (4:3, as the Y2). */
        const val BASE_WIDTH = 320f
        const val BASE_HEIGHT = 240f
        const val COLUMNS = 5
        /** One grid cell: 64 x 55 points (5 x 4 cells under the 20 point status bar). */
        const val CELL_HEIGHT = 55f
        /** Space around each thumbnail, in points. */
        const val GAP = 3f
        /** The selection frame, in points. */
        const val FRAME = 2f

        /** The size of a full-screen photo of [w] x [h] in a [boxW] x [boxH] screen (whole photo, black bars). */
        fun fit(w: Int, h: Int, boxW: Int, boxH: Int): IntArray {
            if (w <= 0 || h <= 0) return intArrayOf(boxW, boxH)
            val s = minOf(boxW / w.toFloat(), boxH / h.toFloat())
            return intArrayOf((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
        }

        /** The part of a [w] x [h] photo that fills a [boxW] x [boxH] cell (centre crop): (left, top, right, bottom). */
        fun crop(w: Int, h: Int, boxW: Int, boxH: Int): IntArray {
            if (w <= 0 || h <= 0 || boxW <= 0 || boxH <= 0) return intArrayOf(0, 0, w, h)
            val boxRatio = boxW / boxH.toFloat()
            return if (w / h.toFloat() > boxRatio) {
                val cw = (h * boxRatio).roundToInt().coerceIn(1, w)
                val l = (w - cw) / 2
                intArrayOf(l, 0, l + cw, h)
            } else {
                val ch = (w / boxRatio).roundToInt().coerceIn(1, h)
                val t = (h - ch) / 2
                intArrayOf(0, t, w, t + ch)
            }
        }

        /**
         * The largest power of two to sub-sample a [w] x [h] image when it decodes, so that it stays at least
         * [reqW] x [reqH] ([cover] = it fills the box; else it fits in it).
         */
        fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int, cover: Boolean): Int {
            if (w <= 0 || h <= 0 || reqW <= 0 || reqH <= 0) return 1
            // The smallest size that still gives every pixel of the result
            val need = if (cover) intArrayOf(reqW, reqH) else fit(w, h, reqW, reqH)
            var sample = 1
            while (true) {
                val nw = w / (sample * 2)
                val nh = h / (sample * 2)
                val ok = nw >= need[0] && nh >= need[1]
                if (!ok) return sample
                sample *= 2
            }
        }

        /** "3 of 120". */
        fun position(index: Int, count: Int, of: String): String = "${index + 1} $of $count"
    }
}
