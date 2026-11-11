package com.themoon.y1.managers

import com.themoon.y1.migrations.BootScreenMigrator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads `minSdkVersion` from the binary AndroidManifest.xml of an APK. PackageManager.getPackageArchiveInfo cannot
 * do it on Android 4.x: it gives no minSdkVersion field (API 24), and for an APK that needs a newer Android it gives
 * null, as for a damaged file.
 */
object ApkMinSdk {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val UTF8_FLAG = 0x100
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11

    /** `android:minSdkVersion` (android.R.attr.minSdkVersion). */
    internal const val ATTR_MIN_SDK_VERSION = 0x0101020c

    /** The minSdkVersion of [apk]: 1 when the manifest has none, null when the file cannot be read. */
    fun read(apk: File): Int? = try {
        BootScreenMigrator.readZipEntry(apk, "AndroidManifest.xml")?.let { fromManifest(it) }
    } catch (_: Throwable) {
        null
    }

    /**
     * The minSdkVersion in the binary manifest [xml]: 1 when `<uses-sdk>` or its minSdkVersion is missing (as Android
     * reads it), [Int.MAX_VALUE] for a preview codename (only a preview Android can install it), null when [xml] is not
     * a binary manifest.
     */
    fun fromManifest(xml: ByteArray): Int? {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        if (xml.size < 8 || u16(buf, 0) != RES_XML_TYPE) return null
        var strings: List<String?> = emptyList()
        var resourceIds = IntArray(0)
        var pos = u16(buf, 2)
        while (pos + 8 <= xml.size) {
            val type = u16(buf, pos)
            val headerSize = u16(buf, pos + 2)
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > xml.size) return null
            when (type) {
                RES_STRING_POOL_TYPE -> strings = stringPool(buf, pos, headerSize)
                RES_XML_RESOURCE_MAP_TYPE -> resourceIds = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                RES_XML_START_ELEMENT_TYPE -> {
                    // ResXMLTree_attrExt after the node header: ns, name, attributeStart, attributeSize, attributeCount
                    val ext = pos + headerSize
                    if (strings.getOrNull(buf.getInt(ext + 4)) == "uses-sdk") {
                        val attrStart = u16(buf, ext + 8)
                        val attrSize = u16(buf, ext + 10)
                        val attrCount = u16(buf, ext + 12)
                        for (i in 0 until attrCount) {
                            val a = ext + attrStart + i * attrSize
                            val name = buf.getInt(a + 4)
                            val isMinSdk = resourceIds.getOrNull(name) == ATTR_MIN_SDK_VERSION ||
                                (name !in resourceIds.indices && strings.getOrNull(name) == "minSdkVersion")
                            if (!isMinSdk) continue
                            val dataType = xml[a + 15].toInt() and 0xFF
                            val data = buf.getInt(a + 16)
                            return when (dataType) {
                                TYPE_INT_DEC, TYPE_INT_HEX -> data
                                TYPE_STRING -> strings.getOrNull(data)?.toIntOrNull() ?: Int.MAX_VALUE
                                else -> null
                            }
                        }
                        return 1
                    }
                }
            }
            pos += size
        }
        return 1
    }

    private fun u16(buf: ByteBuffer, at: Int): Int = buf.getShort(at).toInt() and 0xFFFF

    private fun stringPool(buf: ByteBuffer, chunk: Int, headerSize: Int): List<String?> {
        val count = buf.getInt(chunk + 8)
        val utf8 = buf.getInt(chunk + 16) and UTF8_FLAG != 0
        val stringsStart = chunk + buf.getInt(chunk + 20)
        return List(count) { i ->
            try {
                val at = stringsStart + buf.getInt(chunk + headerSize + i * 4)
                if (utf8) utf8String(buf, at) else utf16String(buf, at)
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    private fun utf8String(buf: ByteBuffer, at: Int): String {
        var p = at
        // Length in characters, then in bytes: 1 or 2 bytes each
        p += if (buf.get(p).toInt() and 0x80 != 0) 2 else 1
        var len = buf.get(p).toInt() and 0xFF
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF)
            p += 2
        } else {
            p += 1
        }
        val bytes = ByteArray(len)
        for (k in 0 until len) bytes[k] = buf.get(p + k)
        return String(bytes, Charsets.UTF_8)
    }

    private fun utf16String(buf: ByteBuffer, at: Int): String {
        var p = at
        var len = u16(buf, p)
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(buf, p + 2)
            p += 4
        } else {
            p += 2
        }
        val chars = CharArray(len) { buf.getChar(p + it * 2) }
        return String(chars)
    }
}
