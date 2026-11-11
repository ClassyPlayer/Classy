package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApkMinSdkTest {

    /** Typed value of an attribute: (dataType, data). A string value is an index into [strings]. */
    private data class Attr(val name: String, val type: Int, val data: Int)

    /** A minimal binary manifest: `<manifest><uses-sdk .../></manifest>`, as aapt writes it. */
    private fun manifest(utf8: Boolean, usesSdk: List<Attr>?, extraStrings: List<String> = emptyList()): ByteArray {
        // Attribute names with a resource id first (the resource map covers them), then the other strings
        val strings = listOf("minSdkVersion", "targetSdkVersion", "manifest", "uses-sdk") + extraStrings
        val ids = intArrayOf(0x0101020c, 0x01010270)

        val pool = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (s in strings) {
            offsets += pool.size()
            if (utf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                pool.write(s.length); pool.write(b.size); pool.write(b); pool.write(0)
            } else {
                pool.write(s.length and 0xFF); pool.write(s.length shr 8)
                for (c in s) { pool.write(c.code and 0xFF); pool.write(c.code shr 8) }
                pool.write(0); pool.write(0)
            }
        }
        while (pool.size() % 4 != 0) pool.write(0)
        val poolHeader = 28
        val poolSize = poolHeader + offsets.size * 4 + pool.size()
        val sp = le(poolSize).apply {
            putShort(0x0001); putShort(poolHeader.toShort()); putInt(poolSize); putInt(strings.size); putInt(0)
            putInt(if (utf8) 0x100 else 0); putInt(poolHeader + offsets.size * 4); putInt(0)
            offsets.forEach { putInt(it) }; put(pool.toByteArray())
        }.array()

        val rm = le(8 + ids.size * 4).apply {
            putShort(0x0180); putShort(8); putInt(8 + ids.size * 4); ids.forEach { putInt(it) }
        }.array()

        fun start(name: String, attrs: List<Attr>): ByteArray {
            val size = 16 + 20 + attrs.size * 20
            return le(size).apply {
                putShort(0x0102); putShort(16); putInt(size); putInt(1); putInt(-1)
                putInt(-1); putInt(strings.indexOf(name)); putShort(20); putShort(20); putShort(attrs.size.toShort())
                putShort(0); putShort(0); putShort(0)
                for (a in attrs) {
                    putInt(-1); putInt(strings.indexOf(a.name))
                    putInt(if (a.type == 0x03) a.data else -1)
                    putShort(8); put(0); put(a.type.toByte()); putInt(a.data)
                }
            }.array()
        }

        val body = ByteArrayOutputStream()
        body.write(sp); body.write(rm); body.write(start("manifest", emptyList()))
        if (usesSdk != null) body.write(start("uses-sdk", usesSdk))
        val all = body.toByteArray()
        return le(8 + all.size).apply { putShort(0x0003); putShort(8); putInt(8 + all.size); put(all) }.array()
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun readsTheMinSdkVersion() {
        val attrs = listOf(Attr("minSdkVersion", 0x10, 19), Attr("targetSdkVersion", 0x10, 35))
        assertEquals(19, ApkMinSdk.fromManifest(manifest(utf8 = false, usesSdk = attrs)))
        assertEquals(19, ApkMinSdk.fromManifest(manifest(utf8 = true, usesSdk = attrs)))
        assertEquals(17, ApkMinSdk.fromManifest(manifest(utf8 = true, usesSdk = listOf(Attr("minSdkVersion", 0x10, 17)))))
    }

    @Test
    fun withoutMinSdkVersionItIs1() {
        assertEquals(1, ApkMinSdk.fromManifest(manifest(utf8 = true, usesSdk = null)))
        assertEquals(1, ApkMinSdk.fromManifest(manifest(utf8 = true, usesSdk = listOf(Attr("targetSdkVersion", 0x10, 35)))))
    }

    @Test
    fun aPreviewCodenameIsTooNewForEachRelease() {
        // String index 4 = "Tiramisu" (the first extra string)
        val xml = manifest(utf8 = true, usesSdk = listOf(Attr("minSdkVersion", 0x03, 4)), extraStrings = listOf("Tiramisu"))
        assertEquals(Int.MAX_VALUE, ApkMinSdk.fromManifest(xml))
    }

    @Test
    fun notABinaryManifest() {
        assertNull(ApkMinSdk.fromManifest("<manifest/>".toByteArray()))
        assertNull(ApkMinSdk.fromManifest(ByteArray(0)))
    }
}
