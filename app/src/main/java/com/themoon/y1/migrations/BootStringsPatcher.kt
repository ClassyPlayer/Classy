package com.themoon.y1.migrations

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipFile

object BootStringsPatcher {

    val WANTED_REPLACEMENTS = listOf(
        "Android is upgrading\u2026" to "Classy is starting\u2026",
        "Optimizing app %1$d of %2$d." to "Updating %1$d of %2$d",
        "Starting apps." to "Starting up\u2026",
        "Finishing boot." to "Finishing up\u2026"
    )

    fun isPatched(apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("resources.arsc") ?: return false
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val str = String(bytes, Charsets.ISO_8859_1)
                str.contains("Classy is starting") && !str.contains("Android is upgrading")
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Patches the boot strings in [apkFile] in-place without changing file size or zip structure.
     * Returns true if successfully patched (or already patched).
     */
    fun patchInPlace(apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        if (isPatched(apkFile)) return true

        try {
            var arscBytes: ByteArray? = null
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("resources.arsc") ?: return false
                arscBytes = zip.getInputStream(entry).use { it.readBytes() }
            }
            val arsc = arscBytes ?: return false
            if (arsc.size < 12) return false

            val buf = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN)
            val headerSize = buf.getShort(14).toInt() and 0xFFFF
            val stringCount = buf.getInt(20)
            val flags = buf.getInt(28)
            val isUtf8 = (flags and 0x100) != 0
            val stringsStart = buf.getInt(32)

            if (!isUtf8) return false

            val offsets = IntArray(stringCount)
            for (i in 0 until stringCount) {
                offsets[i] = buf.getInt(12 + headerSize + i * 4)
            }

            val dataBase = 12 + stringsStart
            var changedCount = 0

            for (i in 0 until stringCount) {
                val off = dataBase + offsets[i]
                if (off >= arsc.size) continue

                val oldFirst = arsc[off].toInt() and 0xFF
                val prefix = 1 + if ((oldFirst and 0x80) != 0) 1 else 0
                if (off + prefix >= arsc.size) continue

                val oldSecond = arsc[off + prefix].toInt() and 0xFF
                val prefix2 = 1 + if ((oldSecond and 0x80) != 0) 1 else 0
                val bodyOff = off + prefix + prefix2
                val bodyLen = if ((oldSecond and 0x80) != 0) {
                    ((oldSecond and 0x7F) shl 8) or (arsc[off + prefix + 1].toInt() and 0xFF)
                } else {
                    oldSecond
                }

                if (bodyOff + bodyLen > arsc.size) continue
                val text = String(arsc, bodyOff, bodyLen, Charsets.UTF_8)

                for ((oldStr, newStr) in WANTED_REPLACEMENTS) {
                    if (text == oldStr) {
                        val room = if (i + 1 < stringCount) offsets[i + 1] - offsets[i] else (arsc.size - (dataBase + offsets[i]))
                        val payload = newStr.toByteArray(Charsets.UTF_8)
                        val u16len = newStr.length
                        val need = payload.size + 1
                        if (prefix + prefix2 + need <= room) {
                            arsc[off] = u16len.toByte()
                            arsc[off + prefix] = payload.size.toByte()
                            System.arraycopy(payload, 0, arsc, bodyOff, payload.size)
                            arsc[bodyOff + payload.size] = 0
                            val padStart = bodyOff + payload.size + 1
                            val padEnd = off + room
                            for (p in padStart until padEnd) {
                                arsc[p] = 0
                            }
                            changedCount++
                        }
                    }
                }
            }

            if (changedCount == 0) return false

            val offsetsInfo = findZipEntryOffsets(apkFile, "resources.arsc") ?: return false

            val crc = CRC32()
            crc.update(arsc)
            val crcVal = crc.value.toInt()

            RandomAccessFile(apkFile, "rw").use { raf ->
                // Write modified ARSC data
                raf.seek(offsetsInfo.dataOffset)
                raf.write(arsc)

                // Update CRC in central directory entry
                raf.seek(offsetsInfo.centralDirPos + 16)
                raf.writeInt(Integer.reverseBytes(crcVal))

                // Check local header and update CRC if no data descriptor
                raf.seek(offsetsInfo.localHeaderOffset)
                val lh = ByteArray(30)
                raf.readFully(lh)
                val lhFlags = (lh[6].toInt() and 0xFF) or ((lh[7].toInt() and 0xFF) shl 8)
                if ((lhFlags and 0x08) == 0) {
                    raf.seek(offsetsInfo.localHeaderOffset + 14)
                    raf.writeInt(Integer.reverseBytes(crcVal))
                }
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private data class ZipOffsets(val dataOffset: Long, val localHeaderOffset: Long, val centralDirPos: Long)

    private fun findZipEntryOffsets(file: File, name: String): ZipOffsets? {
        val len = file.length()
        if (len < 22) return null
        RandomAccessFile(file, "r").use { f ->
            val searchLen = minOf(len, 65557L).toInt()
            val buf = ByteArray(searchLen)
            f.seek(len - searchLen)
            f.readFully(buf)

            var eocd = -1
            val eocdSig = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
            for (i in buf.size - 22 downTo 0) {
                if (buf[i] == eocdSig[0] && buf[i + 1] == eocdSig[1] &&
                    buf[i + 2] == eocdSig[2] && buf[i + 3] == eocdSig[3]
                ) {
                    eocd = i
                    break
                }
            }
            if (eocd == -1) return null

            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val cdSize = bb.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
            val cdOff = bb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL

            f.seek(cdOff)
            val cdBytes = ByteArray(cdSize.toInt())
            f.readFully(cdBytes)
            val cdBuf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)

            var pos = 0
            while (pos < cdBytes.size) {
                if (cdBytes[pos] != 0x50.toByte() || cdBytes[pos + 1] != 0x4b.toByte() ||
                    cdBytes[pos + 2] != 0x01.toByte() || cdBytes[pos + 3] != 0x02.toByte()
                ) break

                val nameLen = cdBuf.getShort(pos + 28).toInt() and 0xFFFF
                val extraLen = cdBuf.getShort(pos + 30).toInt() and 0xFFFF
                val commentLen = cdBuf.getShort(pos + 32).toInt() and 0xFFFF
                val entryName = String(cdBytes, pos + 46, nameLen, Charsets.UTF_8)

                if (entryName == name) {
                    val lho = cdBuf.getInt(pos + 42).toLong() and 0xFFFFFFFFL
                    f.seek(lho + 26)
                    val lhHdr = ByteArray(4)
                    f.readFully(lhHdr)
                    val lhNameLen = ((lhHdr[0].toInt() and 0xFF) or ((lhHdr[1].toInt() and 0xFF) shl 8))
                    val lhExtraLen = ((lhHdr[2].toInt() and 0xFF) or ((lhHdr[3].toInt() and 0xFF) shl 8))
                    val dataOff = lho + 30 + lhNameLen + lhExtraLen
                    return ZipOffsets(dataOff, lho, cdOff + pos)
                }
                pos += 46 + nameLen + extraLen + commentLen
            }
        }
        return null
    }
}
