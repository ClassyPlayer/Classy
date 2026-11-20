package com.themoon.y1.books

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubPackageTest {
    private val files = ArrayList<File>()

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
    }

    /** An EPUB file with [entries] (path → text) plus the container pointing at [opf]. */
    private fun epub(opf: String = "OEBPS/content.opf", entries: Map<String, String>): File {
        val f = File.createTempFile("book", ".epub")
        files += f
        ZipOutputStream(FileOutputStream(f)).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                ("<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"$opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>").toByteArray()
            )
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
            }
        }
        return f
    }

    private fun read(f: File): EpubPackage = ZipFileBook.read(f) { EpubPackage.read(it, { KXmlParser() }) }

    private fun opf(manifest: String, spine: String, metadata: String = "", guide: String = "", spineAttrs: String = "") =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">" +
            "<dc:title>The  Book</dc:title><dc:creator opf:role=\"edt\">Ed Itor</dc:creator><dc:creator opf:role=\"aut\">Ann Author</dc:creator>" +
            "<dc:language>en</dc:language>$metadata</metadata><manifest>$manifest</manifest><spine$spineAttrs>$spine</spine>$guide</package>"

    private val chapters = mapOf(
        "OEBPS/Text/ch1.xhtml" to "<html><body><h1 id=\"c1\">One</h1><p>a</p></body></html>",
        "OEBPS/Text/ch 2.xhtml" to "<html><body><h1>Two</h1><p id=\"mid\">b</p></body></html>"
    )
    private val chapterItems = "<item id=\"c1\" href=\"Text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
        "<item id=\"c2\" href=\"Text/ch%202.xhtml\" media-type=\"application/xhtml+xml\"/>"
    private val chapterSpine = "<itemref idref=\"c1\"/><itemref idref=\"c2\"/>"

    @Test
    fun epub3NavAndCoverImage() {
        val nav = "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>" +
            "<nav epub:type=\"landmarks\"><ol><li><a href=\"Text/ch1.xhtml\">Start</a></li></ol></nav>" +
            "<nav epub:type=\"toc\"><ol><li><a href=\"Text/ch1.xhtml#c1\">One</a><ol><li><a href=\"Text/ch%202.xhtml#mid\">Two  b</a></li></ol></li></ol></nav>" +
            "</body></html>"
        val book = read(epub(entries = chapters + mapOf(
            "OEBPS/content.opf" to opf(
                chapterItems + "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                    "<item id=\"img\" href=\"Images/c.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>",
                chapterSpine
            ),
            "OEBPS/nav.xhtml" to nav,
            "OEBPS/Images/c.jpg" to "jpeg"
        )))
        assertEquals("The Book", book.title)
        assertEquals("Ann Author", book.author)
        assertEquals("en", book.language)
        assertEquals(listOf("OEBPS/Text/ch1.xhtml", "OEBPS/Text/ch 2.xhtml"), book.spine.map { it.path })
        assertEquals(listOf("One", "Two b"), book.toc.map { it.title })
        assertEquals(listOf(0, 1), book.toc.map { it.depth })
        assertEquals(listOf(0, 1), book.toc.map { it.section })
        assertEquals(listOf("c1", "mid"), book.toc.map { it.anchor })
        assertEquals("OEBPS/Images/c.jpg", book.coverPath)
        assertFalse(book.isDrm)
    }

    @Test
    fun epub2NcxAndCoverMeta() {
        val ncx = "<?xml version=\"1.0\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\"><navMap>" +
            "<navPoint id=\"p1\"><navLabel><text>Part One</text></navLabel><content src=\"Text/ch1.xhtml\"/>" +
            "<navPoint id=\"p2\"><navLabel><text>Second</text></navLabel><content src=\"Text/ch%202.xhtml\"/></navPoint>" +
            "</navPoint><navPoint id=\"p3\"><navLabel><text>Out</text></navLabel><content src=\"missing.xhtml\"/></navPoint></navMap></ncx>"
        val book = read(epub(entries = chapters + mapOf(
            "OEBPS/content.opf" to opf(
                chapterItems + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
                    "<item id=\"cover-img\" href=\"Images/Front.PNG\" media-type=\"image/png\"/>",
                chapterSpine, metadata = "<meta name=\"cover\" content=\"cover-img\"/>", spineAttrs = " toc=\"ncx\""
            ),
            "OEBPS/toc.ncx" to ncx,
            "OEBPS/images/front.png" to "png"
        )))
        assertEquals(listOf("Part One", "Second"), book.toc.map { it.title })
        assertEquals(listOf(0, 1), book.toc.map { it.depth })
        assertNull(book.toc[0].anchor)
        // The case of the manifest path differs from the archive (made on Windows)
        assertEquals("OEBPS/images/front.png", book.coverPath)
    }

    @Test
    fun coverFromTheGuideCoverPage() {
        val book = read(epub(entries = chapters + mapOf(
            "OEBPS/content.opf" to opf(
                chapterItems + "<item id=\"cp\" href=\"Text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>",
                chapterSpine, guide = "<guide><reference type=\"cover\" href=\"Text/cover.xhtml\"/></guide>"
            ),
            "OEBPS/Text/cover.xhtml" to "<html><body><svg><image xlink:href=\"../Images/cover.jpg\"/></svg></body></html>",
            "OEBPS/Images/cover.jpg" to "jpg"
        )))
        assertEquals("OEBPS/Images/cover.jpg", book.coverPath)
    }

    @Test
    fun coverFromAnImageNamedCover() {
        val book = read(epub(entries = chapters + mapOf(
            "OEBPS/content.opf" to opf(chapterItems + "<item id=\"i1\" href=\"img/cover01.jpg\" media-type=\"image/jpeg\"/>", chapterSpine),
            "OEBPS/img/cover01.jpg" to "jpg"
        )))
        assertEquals("OEBPS/img/cover01.jpg", book.coverPath)
    }

    @Test
    fun noCover() {
        val book = read(epub(entries = chapters + mapOf("OEBPS/content.opf" to opf(chapterItems, chapterSpine))))
        assertNull(book.coverPath)
        assertTrue(book.toc.isEmpty())
    }

    @Test
    fun opfAtTheTopOfTheArchive() {
        val book = read(epub(opf = "content.opf", entries = mapOf(
            "content.opf" to opf("<item id=\"a\" href=\"a.html\" media-type=\"application/xhtml+xml\"/>", "<itemref idref=\"a\"/>"),
            "a.html" to "<p>x</p>"
        )))
        assertEquals(listOf("a.html"), book.spine.map { it.path })
    }

    @Test
    fun fontObfuscationIsNotDrm() {
        val enc = "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">" +
            "<enc:EncryptedData><enc:EncryptionMethod Algorithm=\"http://www.idpf.org/2008/embedding\"/>" +
            "<enc:CipherData><enc:CipherReference URI=\"OEBPS/Fonts/a.otf\"/></enc:CipherData></enc:EncryptedData></encryption>"
        val book = read(epub(entries = chapters + mapOf("OEBPS/content.opf" to opf(chapterItems, chapterSpine), "META-INF/encryption.xml" to enc)))
        assertFalse(book.isDrm)
    }

    @Test
    fun encryptedChaptersAreDrm() {
        val enc = "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">" +
            "<enc:EncryptedData><enc:EncryptionMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#aes128-cbc\"/>" +
            "<enc:CipherData><enc:CipherReference URI=\"OEBPS/Text/ch1.xhtml\"/></enc:CipherData></enc:EncryptedData></encryption>"
        val book = read(epub(entries = chapters + mapOf("OEBPS/content.opf" to opf(chapterItems, chapterSpine), "META-INF/encryption.xml" to enc)))
        assertTrue(book.isDrm)
    }

    @Test(expected = BookException::class)
    fun notAnEpub() {
        read(epub(entries = mapOf("x.txt" to "y")).also {
            // No container: rewrite as a zip without META-INF
            ZipOutputStream(FileOutputStream(it)).use { z -> z.putNextEntry(ZipEntry("x.txt")); z.write(1) }
        })
    }

    @Test
    fun sourceReadsChaptersWithResolvedImages() {
        val f = epub(entries = mapOf(
            "OEBPS/content.opf" to opf("<item id=\"a\" href=\"Text/a.xhtml\" media-type=\"application/xhtml+xml\"/>", "<itemref idref=\"a\"/>"),
            "OEBPS/Text/a.xhtml" to "<html><body><p><img src=\"../IMAGES/Pic.png\"/></p></body></html>",
            "OEBPS/Images/pic.png" to "png"
        ))
        val zip = ZipFileBook.open(f)
        try {
            val source = EpubSource(zip, EpubPackage.read(zip, { KXmlParser() }))
            val text = source.section(0)
            assertEquals("OEBPS/Images/pic.png", text.runs.first { it.kind == StyledText.Run.IMAGE }.href)
            assertEquals("png", String(source.image("OEBPS/Images/pic.png")!!))
        } finally {
            zip.close()
        }
    }

    @Test
    fun pathResolution() {
        assertEquals("OEBPS/Images/a b.jpg", BookPaths.resolve("OEBPS/Text/", "../Images/a%20b.jpg#x"))
        assertEquals("top.html", BookPaths.resolve("OEBPS/", "/top.html"))
        assertEquals("OEBPS/c+d.html", BookPaths.resolve("OEBPS/", "c+d.html"))
        assertNull(BookPaths.resolve("OEBPS/", "http://example.com/x"))
        assertNull(BookPaths.resolve("OEBPS/", "#only"))
        assertEquals("only", BookPaths.fragment("a.html#only"))
        assertEquals("OEBPS/", BookPaths.dirOf("OEBPS/a.html"))
        assertEquals("", BookPaths.dirOf("a.html"))
    }
}
