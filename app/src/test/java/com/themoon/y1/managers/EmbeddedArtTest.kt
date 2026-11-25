package com.themoon.y1.managers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException

/** The one embedded-cover reader of Now Playing, Cover Flow, Play On and Music Quiz (AudioPlayerManager.readEmbeddedArt). */
class EmbeddedArtTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val picture = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { it.write() }
        return bos.toByteArray()
    }

    /** A FLAC file: STREAMINFO, then a PICTURE block when [art] is not null. */
    private fun flac(name: String, art: ByteArray?): File {
        val mime = "image/jpeg".toByteArray()
        val data = bytes {
            writeBytes("fLaC")
            writeByte(if (art == null) 0x80 else 0x00) // STREAMINFO, the last block without a picture
            writeByte(0); writeByte(0); writeByte(34)
            write(ByteArray(34))
            if (art != null) {
                val block = bytes {
                    writeInt(3) // front cover
                    writeInt(mime.size); write(mime)
                    writeInt(0) // no description
                    write(ByteArray(16)) // width, height, depth, colours
                    writeInt(art.size); write(art)
                }
                writeByte(0x80 or 6)
                writeByte(block.size shr 16); writeByte(block.size shr 8); writeByte(block.size)
                write(block)
            }
        }
        return tmp.newFile(name).apply { writeBytes(data) }
    }

    private fun atom(type: String, body: ByteArray): ByteArray = bytes {
        writeInt(8 + body.size); writeBytes(type); write(body)
    }

    /** An .m4a file with a covr atom in moov/udta/meta/ilst. */
    private fun m4a(name: String, art: ByteArray): File {
        val data = atom("data", bytes { writeInt(13); writeInt(0); write(art) })
        val meta = atom("meta", bytes { writeInt(0); write(atom("ilst", atom("covr", data))) })
        val file = atom("ftyp", "M4A \u0000\u0000\u0000\u0000".toByteArray()) + atom("moov", atom("udta", meta))
        return tmp.newFile(name).apply { writeBytes(file) }
    }

    private val reader get() = AudioPlayerManager.getInstance()

    @Test
    fun flacPicture_isRead() {
        assertArrayEquals(picture, reader.readEmbeddedArt(flac("a.flac", picture)))
    }

    @Test
    fun flacWithoutPicture_isNoCover() {
        assertNull(reader.readEmbeddedArt(flac("b.flac", null)))
    }

    @Test
    fun m4aCover_isReadByTheOwnParser() {
        assertArrayEquals(picture, reader.readEmbeddedArt(m4a("c.m4a", picture)))
    }

    @Test(expected = java.io.EOFException::class)
    fun cutFlacPicture_isAnError_notNoCover() {
        val file = flac("cut.flac", picture)
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 4))
        reader.readEmbeddedArt(file)
    }

    @Test(expected = FileNotFoundException::class)
    fun missingFile_isAnError_notNoCover() {
        reader.readEmbeddedArt(File(tmp.root, "gone.mp3"))
    }

    @Test
    fun retriever_readsAllFormatsButOpusAndFlac() {
        for (name in listOf("a.mp3", "a.M4A", "a.alac", "a.ogg", "a.wav", "a.aac")) assertTrue(name, retrieverReadsArt(name))
        for (name in listOf("a.opus", "a.FLAC")) assertFalse(name, retrieverReadsArt(name))
    }
}
