package com.themoon.y1.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

    /** The 15 format bits of the first copy (around the top left finder pattern), bit 0 first. */
    private fun formatBitsFirstCopy(code: QrCode): Int {
        var bits = 0
        val coords = (0..5).map { 8 to it } + listOf(8 to 7, 8 to 8, 7 to 8) + (9 until 15).map { (14 - it) to 8 }
        coords.forEachIndexed { i, (x, y) -> if (code.isDark(x, y)) bits = bits or (1 shl i) }
        return bits
    }

    /** The 15 format bits of the second copy (at the other two finder patterns), bit 0 first. */
    private fun formatBitsSecondCopy(code: QrCode): Int {
        var bits = 0
        val n = code.size
        val coords = (0 until 8).map { (n - 1 - it) to 8 } + (8 until 15).map { 8 to (n - 15 + it) }
        coords.forEachIndexed { i, (x, y) -> if (code.isDark(x, y)) bits = bits or (1 shl i) }
        return bits
    }

    private fun assertFinderPattern(code: QrCode, left: Int, top: Int) {
        for (dy in 0 until 7) {
            for (dx in 0 until 7) {
                val ring = maxOf(Math.abs(dx - 3), Math.abs(dy - 3))
                assertEquals("finder at $left,$top: $dx,$dy", ring != 2, code.isDark(left + dx, top + dy))
            }
        }
    }

    @Test
    fun webServerAddressIsASmallCodeWithMediumCorrection() {
        val code = QrCode.encodeText("http://192.168.1.20:8080")
        assertEquals(2, code.version)
        assertEquals(25, code.size)
        assertEquals(QrCode.Ecc.MEDIUM, code.ecc)
        assertEquals(3, QrCode.encodeText("http://192.168.100.200:8080").version)
    }

    @Test
    fun functionPatternsAndFormatInformation() {
        for (text in listOf("A", "http://192.168.1.20:8080", "x".repeat(200), "y".repeat(1200))) {
            val code = QrCode.encodeText(text)
            val n = code.size
            assertEquals(code.version * 4 + 17, n)
            assertFinderPattern(code, 0, 0)
            assertFinderPattern(code, n - 7, 0)
            assertFinderPattern(code, 0, n - 7)
            for (i in 8 until n - 8) {
                assertEquals(i % 2 == 0, code.isDark(i, 6))
                assertEquals(i % 2 == 0, code.isDark(6, i))
            }
            assertTrue(code.isDark(8, n - 8))
            assertFalse(code.isDark(-1, 0))
            assertFalse(code.isDark(n, n))

            val first = formatBitsFirstCopy(code)
            assertEquals(first, formatBitsSecondCopy(code))
            val data = (first xor 0x5412) ushr 10
            assertEquals(code.ecc.formatBits, data ushr 3)
            assertEquals(code.mask, data and 7)
        }
    }

    @Test
    fun eachMaskAndLevelCanBeSelected() {
        for (ecc in QrCode.Ecc.values()) {
            for (mask in 0..7) {
                val code = QrCode.encodeBytes("Classy".toByteArray(), ecc, mask, boostEcc = false)
                assertEquals(ecc, code.ecc)
                assertEquals(mask, code.mask)
                val data = (formatBitsFirstCopy(code) xor 0x5412) ushr 10
                assertEquals(ecc.formatBits shl 3 or mask, data)
            }
        }
    }

    @Test
    fun largestCodeHolds2953Bytes() {
        val code = QrCode.encodeBytes(ByteArray(2953), QrCode.Ecc.LOW)
        assertEquals(40, code.version)
        assertEquals(177, code.size)
        try {
            QrCode.encodeBytes(ByteArray(2954), QrCode.Ecc.LOW)
            throw AssertionError("2954 bytes must not fit")
        } catch (_: IllegalArgumentException) {
        }
    }

    /** A check of the full matrix, so that a change of the encoder cannot change the codes without notice. */
    @Test
    fun knownCodeOfAnAddress() {
        val code = QrCode.encodeText("http://192.168.1.20:8080")
        val rows = (0 until code.size).joinToString("\n") { y ->
            (0 until code.size).joinToString("") { x -> if (code.isDark(x, y)) "#" else "." }
        }
        assertEquals(KNOWN_ADDRESS_CODE, rows)
    }

    private companion object {
        /** Made with this encoder; the same matrix as the reference library of Project Nayuki (version 2-M, mask 2). */
        val KNOWN_ADDRESS_CODE = listOf(
            "#######..###..#...#######",
            "#.....#..#...#.#..#.....#",
            "#.###.#.##......#.#.###.#",
            "#.###.#.#....#....#.###.#",
            "#.###.#.#.#.#.#...#.###.#",
            "#.....#.#..##..##.#.....#",
            "#######.#.#.#.#.#.#######",
            "........##.#...#.........",
            "#.#####...##.#.##.#####..",
            "..#.##..#.##.##..#.....#.",
            "##...####.##.#.#..##.#.##",
            "#####..##.#.....#...#...#",
            "##....##.##..#....###.###",
            "#...##...##.###......#.#.",
            "#..#..#.##.....#.#.#.#.##",
            "#.##.#....#.#..#.....#..#",
            "#...#.#.#...##..#####.#..",
            "........##....#.#...###..",
            "#######..####...#.#.#####",
            "#.....#.####...##...##..#",
            "#.###.#.######.########..",
            "#.###.#.#.#.#####.###.###",
            "#.###.#.#...#....#....#.#",
            "#.....#...#.#...#.####..#",
            "#######.#....#.#.########"
        ).joinToString("\n")
    }
}
