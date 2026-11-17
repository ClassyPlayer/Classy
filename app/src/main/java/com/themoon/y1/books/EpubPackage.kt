package com.themoon.y1.books

import org.ccil.cowan.tagsoup.Parser
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.Locale

/** One chapter file of the reading order. [size]: its uncompressed bytes (the weight of the section in the book %). */
class SpineItem(val path: String, val size: Long)

/**
 * One entry of the table of contents: [section] is the spine index, [anchor] the id in it (null: [offset], a char
 * offset in the section; EPUB entries without an anchor start at 0).
 */
class TocEntry(val title: String, val depth: Int, val section: Int, val anchor: String?, val offset: Int = 0)

/**
 * The parts of an EPUB that the reader needs, read from `META-INF/container.xml` and the package document (OPF):
 * metadata, the reading order (spine), the table of contents (EPUB 3 nav, else the EPUB 2 NCX) and the cover image.
 */
class EpubPackage(
    val title: String,
    val author: String,
    val language: String,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    /** The archive path of the cover image, or null. */
    val coverPath: String?,
    /** The book is encrypted (DRM): its chapters cannot be read. */
    val isDrm: Boolean
) {
    companion object {
        /**
         * Reads the package of [zip]. [newParser] makes an XmlPullParser (Android: Xml.newPullParser; tests: KXml).
         * [withToc] false: only metadata, spine and cover (the library scan).
         * Throws [BookException] when the archive is not an EPUB.
         */
        fun read(zip: BookZip, newParser: () -> XmlPullParser, withToc: Boolean = true): EpubPackage {
            val container = zip.bytes("META-INF/container.xml") ?: throw BookException("No META-INF/container.xml")
            val opfPath = rootFile(container, newParser) ?: throw BookException("No package document")
            val opfBytes = zip.bytes(opfPath) ?: throw BookException("Missing $opfPath")
            val opf = Opf.parse(opfBytes, newParser)
            val base = BookPaths.dirOf(opfPath)
            val items = HashMap<String, Opf.Item>()
            for (item in opf.items) items[item.id] = item
            val spine = ArrayList<SpineItem>()
            for (idref in opf.spine) {
                val item = items[idref] ?: continue
                val path = BookPaths.resolve(base, item.href) ?: continue
                val name = zip.find(path) ?: continue
                spine += SpineItem(name, zip.size(name).coerceAtLeast(1))
            }
            if (spine.isEmpty()) throw BookException("Empty spine")
            val cover = coverPath(opf, base, zip, spine)
            val toc = if (withToc) toc(opf, base, zip, spine, newParser) else emptyList()
            return EpubPackage(
                title = opf.title.trim(),
                author = opf.author.trim(),
                language = opf.language.trim(),
                spine = spine,
                toc = toc,
                coverPath = cover,
                isDrm = isDrm(zip, newParser)
            )
        }

        private fun rootFile(container: ByteArray, newParser: () -> XmlPullParser): String? {
            val p = parser(container, newParser)
            var ev = p.next()
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "rootfile") {
                    val type = p.getAttributeValue(null, "media-type")
                    val path = p.getAttributeValue(null, "full-path")
                    if (!path.isNullOrBlank() && (type == null || type == "application/oebps-package+xml")) {
                        return BookPaths.resolve("", path)
                    }
                }
                ev = p.next()
            }
            return null
        }

        /** The cover image: EPUB 3 cover-image, the EPUB 2 cover meta, the cover page of the guide, an image named cover. */
        private fun coverPath(opf: Opf, base: String, zip: BookZip, spine: List<SpineItem>): String? {
            fun imageOf(item: Opf.Item?): String? {
                if (item == null) return null
                val path = BookPaths.resolve(base, item.href) ?: return null
                if (!isImage(item.mediaType, path)) return null
                return zip.find(path)
            }
            opf.items.firstOrNull { "cover-image" in it.properties }?.let { imageOf(it)?.let { p -> return p } }
            opf.coverId?.let { id ->
                val byId = opf.items.firstOrNull { it.id == id } ?: opf.items.firstOrNull { it.href == id }
                imageOf(byId)?.let { return it }
            }
            val pages = ArrayList<String>()
            opf.guideCover?.let { BookPaths.resolve(base, it) }?.let { pages += it }
            spine.firstOrNull()?.let { pages += it.path }
            for (page in pages) {
                val bytes = zip.bytes(page, MAX_COVER_PAGE_BYTES) ?: continue
                firstImage(bytes, BookPaths.dirOf(page))?.let { zip.find(it)?.let { found -> return found } }
            }
            val named = opf.items.firstOrNull { item ->
                val lower = (item.id + " " + item.href).lowercase(Locale.US)
                "cover" in lower && isImage(item.mediaType, item.href)
            }
            return imageOf(named)
        }

        /** The first image of a cover page (an `<img>`, or an SVG `<image>`). */
        private fun firstImage(page: ByteArray, dir: String): String? {
            var found: String? = null
            val text = XhtmlConverter.convert(page) { src -> if (found == null) BookPaths.resolve(dir, src).also { found = it } else null }
            return found ?: text.runs.firstOrNull { it.kind == StyledText.Run.IMAGE }?.href
        }

        private fun isImage(mediaType: String, path: String): Boolean {
            if (mediaType.startsWith("image/")) return mediaType != "image/svg+xml"
            val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
            return ext in IMAGE_EXTENSIONS
        }

        private fun toc(opf: Opf, base: String, zip: BookZip, spine: List<SpineItem>, newParser: () -> XmlPullParser): List<TocEntry> {
            val index = HashMap<String, Int>()
            for ((i, s) in spine.withIndex()) index[s.path.lowercase(Locale.ROOT)] = i
            val nav = opf.items.firstOrNull { "nav" in it.properties }
            if (nav != null) {
                val navPath = BookPaths.resolve(base, nav.href)
                val bytes = navPath?.let { zip.bytes(it) }
                if (navPath != null && bytes != null) {
                    val entries = NavToc.parse(bytes, BookPaths.dirOf(navPath), index)
                    if (entries.isNotEmpty()) return entries
                }
            }
            val ncx = (opf.tocId?.let { id -> opf.items.firstOrNull { it.id == id } })
                ?: opf.items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            if (ncx != null) {
                val ncxPath = BookPaths.resolve(base, ncx.href)
                val bytes = ncxPath?.let { zip.bytes(it) }
                if (ncxPath != null && bytes != null) {
                    try {
                        return NcxToc.parse(bytes, BookPaths.dirOf(ncxPath), index, newParser)
                    } catch (_: Exception) {}
                }
            }
            return emptyList()
        }

        /** True when `META-INF/encryption.xml` encrypts more than fonts (font obfuscation is not DRM), or Adobe rights. */
        private fun isDrm(zip: BookZip, newParser: () -> XmlPullParser): Boolean {
            if (zip.find("META-INF/rights.xml") != null) return true
            val bytes = zip.bytes("META-INF/encryption.xml") ?: return false
            return try {
                val p = parser(bytes, newParser)
                var algorithm: String? = null
                var ev = p.next()
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) when (p.name) {
                        "EncryptionMethod" -> algorithm = p.getAttributeValue(null, "Algorithm")
                        "CipherReference" -> {
                            val uri = p.getAttributeValue(null, "URI").orEmpty().lowercase(Locale.US)
                            val font = FONT_EXTENSIONS.any { uri.endsWith(it) }
                            if (!font && algorithm !in FONT_OBFUSCATION) return true
                        }
                    }
                    ev = p.next()
                }
                false
            } catch (_: Exception) {
                true
            }
        }

        internal fun parser(bytes: ByteArray, newParser: () -> XmlPullParser): XmlPullParser {
            val p = newParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            try {
                // KXml (Android's parser): unknown entities do not stop the parse
                p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
            } catch (_: Exception) {}
            p.setInput(ByteArrayInputStream(bytes), null)
            return p
        }

        private const val MAX_COVER_PAGE_BYTES = 64 * 1024
        private val IMAGE_EXTENSIONS = hashSetOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        private val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".woff", ".woff2")
        private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
    }
}

/** The book cannot be read (not an EPUB, broken archive, DRM). */
open class BookException(message: String) : Exception(message)

/** The package document (OPF): metadata, manifest, spine, the NCX id and the cover hints. */
internal class Opf(
    val title: String,
    val author: String,
    val language: String,
    val items: List<Item>,
    val spine: List<String>,
    val tocId: String?,
    val coverId: String?,
    val guideCover: String?
) {
    class Item(val id: String, val href: String, val mediaType: String, val properties: List<String>)

    companion object {
        fun parse(bytes: ByteArray, newParser: () -> XmlPullParser): Opf {
            val p = EpubPackage.parser(bytes, newParser)
            var title = ""
            var author = ""
            var authorIsAut = false
            var language = ""
            val items = ArrayList<Item>()
            val spine = ArrayList<String>()
            var tocId: String? = null
            var coverId: String? = null
            var guideCover: String? = null
            var ev = p.next()
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "title" -> if (title.isEmpty()) title = text(p)
                    "creator" -> {
                        val role = p.getAttributeValue(OPF_NS, "role") ?: p.getAttributeValue(null, "role")
                        val name = text(p)
                        if (name.isNotEmpty() && (author.isEmpty() || (role == "aut" && !authorIsAut))) {
                            author = name
                            authorIsAut = role == "aut"
                        }
                    }
                    "language" -> if (language.isEmpty()) language = text(p)
                    "meta" -> if (p.getAttributeValue(null, "name") == "cover") {
                        coverId = p.getAttributeValue(null, "content")
                    }
                    "item" -> {
                        val id = p.getAttributeValue(null, "id") ?: ""
                        val href = p.getAttributeValue(null, "href")
                        val type = p.getAttributeValue(null, "media-type") ?: ""
                        val props = p.getAttributeValue(null, "properties")?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
                        if (href != null) items += Item(id, href, type, props)
                    }
                    "spine" -> tocId = p.getAttributeValue(null, "toc")
                    "itemref" -> p.getAttributeValue(null, "idref")?.let { spine += it }
                    "reference" -> if (p.getAttributeValue(null, "type")?.lowercase(Locale.US) == "cover") {
                        guideCover = p.getAttributeValue(null, "href")
                    }
                }
                ev = p.next()
            }
            return Opf(title, author, language, items, spine, tocId, coverId, guideCover)
        }

        /** The text of the element at [p] (its start tag), with the whitespace collapsed. */
        private fun text(p: XmlPullParser): String {
            val sb = StringBuilder()
            var depth = 1
            while (depth > 0) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT -> sb.append(p.text)
                    XmlPullParser.END_DOCUMENT -> depth = 0
                }
            }
            return sb.toString().replace(WS, " ").trim()
        }

        private const val OPF_NS = "http://www.idpf.org/2007/opf"
        private val WS = Regex("\\s+")
    }
}

/** The EPUB 2 table of contents (`toc.ncx`): nested navPoints with a label and a link. */
internal object NcxToc {
    fun parse(bytes: ByteArray, dir: String, spineIndex: Map<String, Int>, newParser: () -> XmlPullParser): List<TocEntry> {
        val p = EpubPackage.parser(bytes, newParser)
        val out = ArrayList<TocEntry>()
        var depth = -1
        var inNavMap = false
        var label: String? = null
        var src: String? = null
        val pendingDepth = ArrayList<Int>()
        fun flush() {
            val s = src
            val l = label
            if (s != null && l != null && pendingDepth.isNotEmpty()) {
                add(out, l, pendingDepth.last(), dir, s, spineIndex)
                pendingDepth.removeAt(pendingDepth.size - 1)
            }
            label = null
            src = null
        }
        var ev = p.next()
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "navMap" -> inNavMap = true
                    "navPoint" -> if (inNavMap) {
                        // A parent with its label and link goes before its children
                        flush()
                        depth++
                        pendingDepth += depth
                    }
                    "text" -> if (inNavMap && label == null) label = p.nextText().replace(Regex("\\s+"), " ").trim()
                    "content" -> if (inNavMap && src == null) src = p.getAttributeValue(null, "src")
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "navPoint" -> if (inNavMap) {
                        flush()
                        depth--
                    }
                    "navMap" -> inNavMap = false
                }
            }
            ev = p.next()
        }
        return out
    }

    fun add(out: MutableList<TocEntry>, title: String, depth: Int, dir: String, href: String, spineIndex: Map<String, Int>) {
        val path = BookPaths.resolve(dir, href) ?: return
        val section = spineIndex[path.lowercase(Locale.ROOT)] ?: return
        out += TocEntry(title.ifEmpty { "•" }, depth.coerceAtLeast(0), section, BookPaths.fragment(href))
    }
}

/** The EPUB 3 table of contents: the `<nav epub:type="toc">` of the navigation document, with nested `<ol>` lists. */
internal object NavToc {
    fun parse(bytes: ByteArray, dir: String, spineIndex: Map<String, Int>): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        val handler = object : DefaultHandler() {
            var navDepth = 0
            var inToc = false
            var tocNavDepth = 0
            var olDepth = 0
            var linkHref: String? = null
            var linkText: StringBuilder? = null
            var otherNavs = 0

            override fun startElement(uri: String?, localName: String?, qName: String?, atts: Attributes) {
                val tag = name(localName, qName)
                when (tag) {
                    "nav" -> {
                        navDepth++
                        val type = atts.getValue("epub:type") ?: atts.getValue("type") ?: atts.getValue("role") ?: ""
                        if (!inToc && ("toc" in type.split(' ') || type == "doc-toc")) {
                            inToc = true
                            tocNavDepth = navDepth
                        } else if (!inToc) {
                            otherNavs++
                        }
                    }
                    "ol", "ul" -> if (inToc) olDepth++
                    "a" -> if (inToc) {
                        linkHref = atts.getValue("href")
                        linkText = StringBuilder()
                    }
                    "span" -> if (inToc && linkText == null) {
                        // A heading without a link: not an entry
                        linkHref = null
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (name(localName, qName)) {
                    "nav" -> {
                        if (inToc && navDepth == tocNavDepth) inToc = false
                        navDepth--
                    }
                    "ol", "ul" -> if (inToc) olDepth--
                    "a" -> if (inToc) {
                        val href = linkHref
                        val text = linkText?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                        if (href != null) NcxToc.add(out, text, olDepth - 1, dir, href, spineIndex)
                        linkHref = null
                        linkText = null
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                linkText?.append(ch, start, length)
            }
        }
        val parser = Parser()
        try {
            parser.setFeature(Parser.namespacesFeature, false)
        } catch (_: Exception) {}
        parser.contentHandler = handler
        parser.parse(InputSource(InputStreamReader(ByteArrayInputStream(bytes), XhtmlConverter.charsetOf(bytes))))
        return out
    }

    private fun name(localName: String?, qName: String?): String {
        val n = if (!localName.isNullOrEmpty()) localName else qName.orEmpty()
        return n.substringAfter(':').lowercase(Locale.US)
    }
}
