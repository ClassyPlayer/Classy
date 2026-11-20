package com.themoon.y1.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XhtmlConverterTest {

    private fun convert(html: String, resolve: (String) -> String? = { BookPaths.resolve("OEBPS/Text/", it) }) =
        XhtmlConverter.convert(html.toByteArray(Charsets.UTF_8), resolve)

    private fun StyledText.kinds(kind: Int) = runs.filter { it.kind == kind }.map { text.substring(it.start, it.end) }

    @Test
    fun paragraphsAndWhitespace() {
        val t = convert("<html><head><title>T</title><style>p{}</style></head><body><p>One\n  two</p><p>  Three </p></body></html>")
        assertEquals("One two\nThree", t.text)
        assertEquals(listOf("One two", "Three"), t.kinds(StyledText.Run.PARAGRAPH))
    }

    @Test
    fun entitiesThatXhtmlDoesNotDeclare() {
        val t = convert("<body><p>a&nbsp;b &mdash; c &hellip;</p></body>")
        assertEquals("a b — c …", t.text)
    }

    @Test
    fun inlineStylesAndNesting() {
        val t = convert("<body><p>x <i>it <b>bold</b></i> <span style=\"font-weight: bold\">sb</span></p></body>")
        assertEquals("x it bold sb", t.text)
        assertEquals(listOf("it bold"), t.kinds(StyledText.Run.ITALIC))
        assertEquals(listOf("bold", "sb"), t.kinds(StyledText.Run.BOLD).sorted())
    }

    @Test
    fun headingsAndCentredParagraphsHaveNoIndent() {
        val t = convert("<body><h2>Chapter</h2><p class=\"center\">* * *</p><p style=\"text-align:center\">c</p><p>body</p></body>")
        assertEquals("Chapter\n* * *\nc\nbody", t.text)
        val h = t.runs.first { it.kind == StyledText.Run.HEADING }
        assertEquals(2, h.value)
        assertEquals(listOf("* * *", "c"), t.kinds(StyledText.Run.CENTER))
        assertEquals(listOf("body"), t.kinds(StyledText.Run.PARAGRAPH))
    }

    @Test
    fun anchorsForTheTocFragments() {
        val t = convert("<body><p>a</p><h3 id=\"ch2\">Two</h3><p><a name=\"n1\"></a>b</p></body>")
        assertEquals(2, t.anchors["ch2"])
        assertEquals(t.text.indexOf('b'), t.anchors["n1"])
    }

    @Test
    fun imagesWithRelativeAndEncodedPaths() {
        val t = convert("<body><p><img src=\"../Images/My%20Cover.jpg\" alt=\"x\"/></p><p>after</p></body>")
        assertEquals("${StyledText.IMAGE_CHAR}\nafter", t.text)
        val img = t.runs.first { it.kind == StyledText.Run.IMAGE }
        assertEquals("OEBPS/Images/My Cover.jpg", img.href)
    }

    @Test
    fun svgCoverImage() {
        val t = convert(
            "<body><svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">" +
                "<image width=\"600\" height=\"800\" xlink:href=\"../Images/cover.jpeg\"/></svg></body>"
        )
        val img = t.runs.firstOrNull { it.kind == StyledText.Run.IMAGE }
        assertNotNull(t.text, img)
        assertEquals("OEBPS/Images/cover.jpeg", img!!.href)
    }

    @Test
    fun imageInsideAParagraph() {
        val t = convert("<body><p>see <img src=\"../Images/a.png\"/> here</p></body>")
        assertEquals("see ${StyledText.IMAGE_CHAR} here", t.text)
    }

    @Test
    fun missingImageShowsItsAltText() {
        val t = convert("<body><p><img src=\"gone.png\" alt=\"Map\"/></p><p><img/></p></body>") { null }
        assertEquals("Map", t.text)
        assertTrue(t.runs.none { it.kind == StyledText.Run.IMAGE })
    }

    @Test
    fun hiddenAndScriptContentIsSkipped() {
        val t = convert("<body><p>a</p><script>var x = 1;</script><div style=\"display: none\">hidden</div><p>b</p></body>")
        assertEquals("a\nb", t.text)
    }

    @Test
    fun lineBreaksAndRules() {
        val t = convert("<body><p>line one<br/>line two</p><hr/><p>end</p></body>")
        assertEquals("line one\nline two\n* * *\nend", t.text)
    }

    @Test
    fun charsetFromTheXmlDeclaration() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-2\"?><html><body><p>ł</p></body></html>"
            .toByteArray(charset("ISO-8859-2"))
        assertEquals(charset("ISO-8859-2"), XhtmlConverter.charsetOf(bytes))
        assertEquals("ł", XhtmlConverter.convert(bytes) { null }.text)
    }

    @Test
    fun emptyDocument() {
        assertEquals("", convert("").text)
        assertNull(convert("<body></body>").anchors["x"])
    }
}
