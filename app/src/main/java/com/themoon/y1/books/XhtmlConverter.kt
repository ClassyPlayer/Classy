package com.themoon.y1.books

import org.ccil.cowan.tagsoup.Parser
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.util.Locale

/**
 * One EPUB chapter (XHTML) as [StyledText]. TagSoup parses it, so real-world HTML works too (entities such as
 * `&nbsp;` that XHTML does not declare, tags that do not close).
 *
 * The styles come from the tags, the inline `style` attribute and a few class names ("center", "italic", "bold");
 * the book's CSS files are not read. Images become [StyledText.IMAGE_CHAR] with the path that [resolve] gives
 * for their `src` (null: the image is not in the book, and its alt text shows).
 */
object XhtmlConverter {

    fun convert(bytes: ByteArray, resolve: (String) -> String?): StyledText =
        convert(InputStreamReader(ByteArrayInputStream(bytes), charsetOf(bytes)), resolve)

    fun convert(reader: Reader, resolve: (String) -> String?): StyledText {
        val handler = Builder(resolve)
        val parser = Parser()
        try {
            parser.setFeature(Parser.namespacesFeature, false)
            parser.setFeature(Parser.defaultAttributesFeature, false)
        } catch (_: Exception) {}
        parser.contentHandler = handler
        parser.parse(InputSource(reader))
        return handler.result()
    }

    /** The charset of an XHTML file: its byte order mark, its XML declaration or meta charset, else UTF-8. */
    fun charsetOf(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val m = DECLARED.find(head) ?: return Charsets.UTF_8
        return try {
            Charset.forName(m.groupValues[1])
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    private val DECLARED = Regex("""(?:encoding|charset)\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

    private class Open(val tag: String, val start: Int, val kinds: IntArray, val level: Int, val block: Boolean, val skip: Boolean)

    private class Builder(private val resolve: (String) -> String?) : DefaultHandler() {
        private val sb = StringBuilder()
        private val runs = ArrayList<StyledText.Run>()
        private val anchors = HashMap<String, Int>()
        private val stack = ArrayList<Open>()
        private var skipDepth = 0
        private var preDepth = 0
        private var pendingSpace = false

        fun result(): StyledText {
            // No paragraph break at the end of the section
            var end = sb.length
            while (end > 0 && sb[end - 1] == '\n') end--
            sb.setLength(end)
            val kept = runs.mapNotNull { r ->
                val e = minOf(r.end, end)
                if (r.start >= e) null else if (e == r.end) r else StyledText.Run(r.kind, r.start, e, r.value, r.href)
            }
            for (k in anchors.keys.toList()) if (anchors[k]!! > end) anchors[k] = end
            return StyledText(sb.toString(), kept, anchors)
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, atts: Attributes) {
            val tag = nameOf(localName, qName)
            val skipThis = tag in SKIPPED || hidden(atts)
            if (skipDepth > 0 || skipThis) {
                stack += Open(tag, sb.length, NO_KINDS, 0, false, skip = true)
                skipDepth++
                return
            }
            val id = atts.getValue("id") ?: (if (tag == "a") atts.getValue("name") else null)
            if (!id.isNullOrEmpty() && id !in anchors) anchors[id] = sb.length

            when (tag) {
                "br" -> {
                    sb.append('\n')
                    pendingSpace = false
                }
                "hr" -> {
                    breakBlock()
                    val s = sb.length
                    sb.append("* * *")
                    runs += StyledText.Run(StyledText.Run.CENTER, s, sb.length)
                    sb.append('\n')
                    pendingSpace = false
                }
                "img", "image" -> image(atts)
            }
            if (tag in VOID) {
                // Not on the stack: no end tag comes for it (TagSoup reports one; endElement drops it)
                return
            }

            val block = tag in BLOCKS
            if (block) {
                breakBlock()
            } else if (pendingSpace && sb.isNotEmpty() && sb[sb.length - 1] != '\n') {
                // "a <i>b</i>": the space goes before the styled text, not into it
                sb.append(' ')
                pendingSpace = false
            }
            if (tag == "pre") preDepth++
            val kinds = ArrayList<Int>(2)
            var level = 0
            when (tag) {
                "b", "strong" -> kinds += StyledText.Run.BOLD
                "i", "em", "cite", "dfn", "var" -> kinds += StyledText.Run.ITALIC
                "u", "ins" -> kinds += StyledText.Run.UNDERLINE
                "s", "strike", "del" -> kinds += StyledText.Run.STRIKE
                "sup" -> kinds += StyledText.Run.SUP
                "sub" -> kinds += StyledText.Run.SUB
                "small" -> kinds += StyledText.Run.SMALL
                "code", "tt", "kbd", "samp", "pre" -> kinds += StyledText.Run.MONO
                "blockquote" -> kinds += StyledText.Run.QUOTE
                "li", "dd" -> kinds += StyledText.Run.LIST_ITEM
                "center" -> kinds += StyledText.Run.CENTER
                "p" -> kinds += StyledText.Run.PARAGRAPH
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    kinds += StyledText.Run.HEADING
                    level = tag[1] - '0'
                }
            }
            styleKinds(atts, block, kinds)
            stack += Open(tag, sb.length, kinds.toIntArray(), level, block, skip = false)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = nameOf(localName, qName)
            if (tag in VOID && skipDepth == 0) return
            // The open element with this name (TagSoup closes the ones in between itself)
            val i = stack.indexOfLast { it.tag == tag }
            if (i < 0) return
            while (stack.size > i) close(stack.removeAt(stack.size - 1))
        }

        private fun close(open: Open) {
            if (open.skip) {
                skipDepth--
                return
            }
            if (open.tag == "pre") preDepth--
            val end = sb.length
            if (end > open.start) {
                for (k in open.kinds) {
                    // A first-line indent only on body text: not on a centred or right-aligned paragraph
                    if (k == StyledText.Run.PARAGRAPH && open.kinds.any { it == StyledText.Run.CENTER || it == StyledText.Run.RIGHT }) continue
                    runs += StyledText.Run(k, open.start, end, if (k == StyledText.Run.HEADING) open.level else 0)
                }
            }
            if (open.block) breakBlock()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (skipDepth > 0) return
            if (preDepth > 0) {
                for (i in start until start + length) {
                    val c = ch[i]
                    if (c != '\r') sb.append(c)
                }
                return
            }
            for (i in start until start + length) {
                val c = ch[i]
                if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                    pendingSpace = true
                    continue
                }
                if (pendingSpace && sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }

        /** Starts a new paragraph unless one just started. */
        private fun breakBlock() {
            if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
            pendingSpace = false
        }

        private fun image(atts: Attributes) {
            val src = atts.getValue("src") ?: atts.getValue("xlink:href") ?: atts.getValue("href")
            val path = src?.takeIf { it.isNotBlank() }?.let(resolve)
            if (path == null) {
                val alt = atts.getValue("alt")?.trim().orEmpty()
                if (alt.isNotEmpty()) {
                    if (pendingSpace && sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append(' ')
                    pendingSpace = false
                    sb.append(alt)
                }
                return
            }
            if (pendingSpace && sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append(' ')
            pendingSpace = false
            val s = sb.length
            sb.append(StyledText.IMAGE_CHAR)
            runs += StyledText.Run(StyledText.Run.IMAGE, s, s + 1, href = path)
        }

        /** The inline style and the class names that say the same. */
        private fun styleKinds(atts: Attributes, block: Boolean, kinds: MutableList<Int>) {
            val style = atts.getValue("style")?.lowercase(Locale.US)
            if (style != null) {
                for (decl in style.split(';')) {
                    val colon = decl.indexOf(':')
                    if (colon < 0) continue
                    val prop = decl.substring(0, colon).trim()
                    val value = decl.substring(colon + 1).trim()
                    when (prop) {
                        "font-style" -> if (value.startsWith("italic") || value.startsWith("oblique")) kinds.addOnce(StyledText.Run.ITALIC)
                        "font-weight" -> if (value.startsWith("bold") || (value.toIntOrNull() ?: 0) >= 600) kinds.addOnce(StyledText.Run.BOLD)
                        "text-align" -> if (block) when {
                            value.startsWith("center") -> kinds.addOnce(StyledText.Run.CENTER)
                            value.startsWith("right") -> kinds.addOnce(StyledText.Run.RIGHT)
                        }
                    }
                }
            }
            val classes = atts.getValue("class")?.lowercase(Locale.US) ?: return
            for (c in classes.split(' ', '\t')) {
                if (c.isEmpty()) continue
                when {
                    block && (c.contains("center") || c.contains("centre")) -> kinds.addOnce(StyledText.Run.CENTER)
                    c.contains("italic") -> kinds.addOnce(StyledText.Run.ITALIC)
                    c.contains("bold") -> kinds.addOnce(StyledText.Run.BOLD)
                }
            }
        }

        private fun hidden(atts: Attributes): Boolean {
            val style = atts.getValue("style") ?: return false
            return style.replace(" ", "").lowercase(Locale.US).contains("display:none")
        }

        private fun MutableList<Int>.addOnce(k: Int) {
            if (k !in this) add(k)
        }
    }

    private fun nameOf(localName: String?, qName: String?): String {
        val n = if (!localName.isNullOrEmpty()) localName else qName.orEmpty()
        return n.substringAfter(':').lowercase(Locale.US)
    }

    private val NO_KINDS = IntArray(0)

    private val SKIPPED = hashSetOf("head", "script", "style", "title", "noscript", "template", "object", "audio", "video", "math")

    /** Elements with no content: TagSoup still reports an end for them. */
    private val VOID = hashSetOf("br", "hr", "img", "image", "meta", "link", "input", "wbr", "col", "area", "base")

    private val BLOCKS = hashSetOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "ul", "ol", "dl", "dt", "dd", "pre",
        "section", "article", "aside", "header", "footer", "nav", "figure", "figcaption", "table", "tr", "caption",
        "center", "address", "main", "body", "hgroup", "details", "summary"
    )
}
