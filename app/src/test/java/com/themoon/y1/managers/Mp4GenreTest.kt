package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** The genre of M4A files: text (©gen) or, in iTunes Store files, a number (gnre). */
class Mp4GenreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { it.write() }
        return bos.toByteArray()
    }

    private fun atom(type: String, body: ByteArray): ByteArray = bytes {
        writeInt(8 + body.size); writeBytes(type); write(body)
    }

    /** An ilst item [type] with one data atom (type and locale first) of [value]. */
    private fun item(type: String, dataType: Int, value: ByteArray) =
        atom(type, atom("data", bytes { writeInt(dataType); writeInt(0); write(value) }))

    private fun gnre(n: Int) = item("gnre", 0, byteArrayOf((n shr 8).toByte(), n.toByte()))
    private fun gen(text: String) = item("©gen", 1, text.toByteArray())

    /** An .m4a file with the [items] in moov/udta/meta/ilst. */
    private fun m4a(name: String, vararg items: ByteArray): File {
        val ilst = atom("ilst", items.fold(ByteArray(0)) { all, it -> all + it })
        val meta = atom("meta", bytes { writeInt(0); write(ilst) })
        val file = atom("ftyp", "M4A \u0000\u0000\u0000\u0000".toByteArray()) + atom("moov", atom("udta", meta))
        return tmp.newFile(name).apply { writeBytes(file) }
    }

    private fun genre(file: File) = AudioPlayerManager.getInstance().extractAlacMetadata(file, false)[4]

    @Test
    fun gnreIsTheId3NumberPlusOne() {
        assertEquals("Pop", mp4GnreGenre(byteArrayOf(0, 14)))
        assertEquals("Blues", mp4GnreGenre(byteArrayOf(0, 1)))
        assertEquals("R&B", mp4GnreGenre(byteArrayOf(0, 15)))
        assertNull(mp4GnreGenre(byteArrayOf(0, 0)))
        assertNull(mp4GnreGenre(byteArrayOf(0x7F, 0)))
        assertNull(mp4GnreGenre(byteArrayOf(14)))
    }

    @Test
    fun numericGenreOfAnITunesStoreFileIsRead() {
        assertEquals("Pop", genre(m4a("pop.m4a", gnre(14))))
    }

    @Test
    fun textGenreWinsOverTheNumber() {
        assertEquals("Telugu", genre(m4a("a.m4a", gnre(14), gen("Telugu"))))
        assertEquals("Telugu", genre(m4a("b.m4a", gen("Telugu"), gnre(14))))
    }

    @Test
    fun noGenreStaysEmpty() {
        assertNull(genre(m4a("none.m4a", gnre(0))))
    }
}
