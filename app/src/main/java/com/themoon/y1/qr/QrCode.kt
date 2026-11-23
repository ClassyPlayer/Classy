/*
 * QR Code encoder (byte mode only), ported to Kotlin from the QR Code generator library by Project Nayuki:
 * https://www.nayuki.io/page/qr-code-generator-library
 *
 * Copyright (c) Project Nayuki. (MIT License)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 * - The Software is provided "as is", without warranty of any kind, express or
 *   implied, including but not limited to the warranties of merchantability,
 *   fitness for a particular purpose and noninfringement. In no event shall the
 *   authors or copyright holders be liable for any claim, damages or other
 *   liability, whether in an action of contract, tort or otherwise, arising from,
 *   out of or in connection with the Software or the use or other dealings in the
 *   Software.
 */
package com.themoon.y1.qr

/**
 * A QR code (model 2, versions 1 to 40) of text or bytes, in byte mode. [isDark] gives the modules; the code has no
 * quiet zone (draw 4 light modules around it). No Android classes, so that unit tests can use it.
 */
class QrCode private constructor(val version: Int, val ecc: Ecc, dataCodewords: ByteArray, mask: Int) {

    /** The error correction level. [formatBits] is the value in the format information. */
    enum class Ecc(val formatBits: Int) { LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2) }

    /** Width and height in modules (21 to 177). */
    val size: Int = version * 4 + 17

    /** The mask pattern (0 to 7) of the code. */
    val mask: Int

    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    init {
        require(version in MIN_VERSION..MAX_VERSION) { "Version out of range" }
        require(mask in -1..7) { "Mask out of range" }
        drawFunctionPatterns()
        drawCodewords(addEccAndInterleave(dataCodewords))
        var chosen = mask
        if (chosen == -1) {
            var minPenalty = Int.MAX_VALUE
            for (i in 0..7) {
                applyMask(i)
                drawFormatBits(i)
                val penalty = penaltyScore()
                if (penalty < minPenalty) {
                    chosen = i
                    minPenalty = penalty
                }
                applyMask(i) // XOR again to undo
            }
        }
        this.mask = chosen
        applyMask(chosen)
        drawFormatBits(chosen)
    }

    /** True for a dark module. Coordinates outside the code are light. */
    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]

    private fun setFunctionModule(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        isFunction[y][x] = true
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            setFunctionModule(6, i, i % 2 == 0)
            setFunctionModule(i, 6, i % 2 == 0)
        }
        drawFinderPattern(3, 3)
        drawFinderPattern(size - 4, 3)
        drawFinderPattern(3, size - 4)
        val positions = alignmentPatternPositions()
        val last = positions.size - 1
        for (i in positions.indices) {
            for (j in positions.indices) {
                // Not on the three finder patterns
                if (!(i == 0 && j == 0 || i == 0 && j == last || i == last && j == 0)) {
                    drawAlignmentPattern(positions[i], positions[j])
                }
            }
        }
        drawFormatBits(0) // Dummy value; the constructor draws the real one
        drawVersion()
    }

    private fun drawFormatBits(mask: Int) {
        val data = ecc.formatBits shl 3 or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = (data shl 10 or rem) xor 0x5412
        // First copy, around the top left finder pattern
        for (i in 0..5) setFunctionModule(8, i, bit(bits, i))
        setFunctionModule(8, 7, bit(bits, 6))
        setFunctionModule(8, 8, bit(bits, 7))
        setFunctionModule(7, 8, bit(bits, 8))
        for (i in 9 until 15) setFunctionModule(14 - i, 8, bit(bits, i))
        // Second copy, at the other two finder patterns
        for (i in 0 until 8) setFunctionModule(size - 1 - i, 8, bit(bits, i))
        for (i in 8 until 15) setFunctionModule(8, size - 15 + i, bit(bits, i))
        setFunctionModule(8, size - 8, true) // Always dark
    }

    private fun drawVersion() {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = version shl 12 or rem
        for (i in 0 until 18) {
            val dark = bit(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            setFunctionModule(a, b, dark)
            setFunctionModule(b, a, dark)
        }
    }

    private fun drawFinderPattern(x: Int, y: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) setFunctionModule(xx, yy, dist != 2 && dist != 4)
            }
        }
    }

    private fun drawAlignmentPattern(x: Int, y: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) setFunctionModule(x + dx, y + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }
    }

    private fun alignmentPatternPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        val result = IntArray(count)
        result[0] = 6
        var pos = size - 7
        for (i in count - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    /** Splits the data into blocks, adds the Reed-Solomon codewords to each block, and interleaves the blocks. */
    private fun addEccAndInterleave(data: ByteArray): ByteArray {
        require(data.size == numDataCodewords(version, ecc))
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version]
        val rawCodewords = numRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks
        val divisor = reedSolomonDivisor(blockEccLen)
        val blocks = ArrayList<ByteArray>(numBlocks)
        var k = 0
        for (i in 0 until numBlocks) {
            val dataLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + dataLen)
            k += dataLen
            val block = dat.copyOf(shortBlockLen + 1)
            reedSolomonRemainder(dat, divisor).copyInto(block, block.size - blockEccLen)
            blocks.add(block)
        }
        val result = ByteArray(rawCodewords)
        k = 0
        for (i in 0 until shortBlockLen + 1) {
            for (j in 0 until numBlocks) {
                // A short block has no data byte at this index
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[k++] = blocks[j][i]
                }
            }
        }
        return result
    }

    /** Puts the codewords in the zigzag order of the standard into the modules that are not function modules. */
    private fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5 // Skip the vertical timing pattern
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = bit(data[i ushr 3].toInt(), 7 - (i and 7))
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    /** XORs the data modules with mask pattern [mask]. Two calls with the same mask undo each other. */
    private fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
            }
        }
    }

    /** The penalty score of the standard: a lower score is a code that is easier to read. */
    private fun penaltyScore(): Int {
        var result = 0
        val history = IntArray(7)
        for (pass in 0..1) { // Rows, then columns
            for (a in 0 until size) {
                var runColor = false
                var runLength = 0
                history.fill(0)
                for (b in 0 until size) {
                    val dark = if (pass == 0) modules[a][b] else modules[b][a]
                    if (dark == runColor) {
                        runLength++
                        if (runLength == 5) result += PENALTY_N1 else if (runLength > 5) result++
                    } else {
                        addRunToHistory(runLength, history)
                        if (!runColor) result += countFinderLikePatterns(history) * PENALTY_N3
                        runColor = dark
                        runLength = 1
                    }
                }
                result += terminateAndCount(runColor, runLength, history) * PENALTY_N3
            }
        }
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += PENALTY_N2
            }
        }
        var dark = 0
        for (row in modules) for (m in row) if (m) dark++
        val total = size * size
        val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * PENALTY_N4
        return result
    }

    private fun countFinderLikePatterns(history: IntArray): Int {
        val n = history[1]
        val core = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
        return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
            (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
    }

    private fun terminateAndCount(runColor: Boolean, runLength: Int, history: IntArray): Int {
        var length = runLength
        if (runColor) {
            addRunToHistory(length, history)
            length = 0
        }
        length += size // The light border after the last run
        addRunToHistory(length, history)
        return countFinderLikePatterns(history)
    }

    private fun addRunToHistory(runLength: Int, history: IntArray) {
        var length = runLength
        if (history[0] == 0) length += size // The light border before the first run
        System.arraycopy(history, 0, history, 1, history.size - 1)
        history[0] = length
    }

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 40

        private const val PENALTY_N1 = 3
        private const val PENALTY_N2 = 3
        private const val PENALTY_N3 = 40
        private const val PENALTY_N4 = 10

        /** The QR code of [text] (UTF-8). See [encodeBytes]. */
        fun encodeText(text: String, ecc: Ecc = Ecc.MEDIUM): QrCode = encodeBytes(text.toByteArray(Charsets.UTF_8), ecc)

        /**
         * The smallest QR code that holds [data] with at least error correction [ecc]. With [boostEcc], the code
         * uses a higher level when it fits in the same version. [mask] -1 selects the mask with the lowest penalty.
         * Throws [IllegalArgumentException] when the data is too long for version 40.
         */
        fun encodeBytes(data: ByteArray, ecc: Ecc = Ecc.MEDIUM, mask: Int = -1, boostEcc: Boolean = true): QrCode {
            var version = MIN_VERSION
            var usedBits: Int
            while (true) {
                usedBits = 4 + charCountBits(version) + data.size * 8
                if (data.size < (1 shl charCountBits(version)) && usedBits <= numDataCodewords(version, ecc) * 8) break
                require(version < MAX_VERSION) { "Data too long for a QR code" }
                version++
            }
            var level = ecc
            if (boostEcc) {
                for (e in arrayOf(Ecc.MEDIUM, Ecc.QUARTILE, Ecc.HIGH)) {
                    if (e.ordinal > level.ordinal && usedBits <= numDataCodewords(version, e) * 8) level = e
                }
            }
            val capacityBits = numDataCodewords(version, level) * 8
            val bits = BitWriter(capacityBits)
            bits.append(0x4, 4) // Byte mode
            bits.append(data.size, charCountBits(version))
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            bits.append(0, minOf(4, capacityBits - bits.length)) // Terminator
            bits.append(0, (8 - bits.length % 8) % 8)
            var pad = 0xEC
            while (bits.length < capacityBits) {
                bits.append(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            return QrCode(version, level, bits.toBytes(), mask)
        }

        private fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

        private fun numRawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val count = version / 7 + 2
                result -= (25 * count - 10) * count - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        internal fun numDataCodewords(version: Int, ecc: Ecc): Int =
            numRawDataModules(version) / 8 -
                ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version] * NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]

        private fun reedSolomonDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in result.indices) {
                    var v = reedSolomonMultiply(result[j].toInt() and 0xFF, root)
                    if (j + 1 < result.size) v = v xor (result[j + 1].toInt() and 0xFF)
                    result[j] = v.toByte()
                }
                root = reedSolomonMultiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) {
                    result[i] = (result[i].toInt() xor reedSolomonMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        /** Multiplication in GF(2^8) with the polynomial 0x11D. */
        private fun reedSolomonMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun bit(x: Int, i: Int): Boolean = ((x ushr i) and 1) != 0

        // Index: [Ecc.ordinal][version]; index 0 is not used
        private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
        )

        private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81)
        )
    }

    /** Appends bits, most significant bit first, and packs them into bytes. */
    private class BitWriter(capacityBits: Int) {
        private val bytes = ByteArray((capacityBits + 7) / 8)
        var length = 0
            private set

        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                if ((value ushr i) and 1 != 0) bytes[length ushr 3] = (bytes[length ushr 3].toInt() or (0x80 ushr (length and 7))).toByte()
                length++
            }
        }

        fun toBytes(): ByteArray = bytes
    }
}
