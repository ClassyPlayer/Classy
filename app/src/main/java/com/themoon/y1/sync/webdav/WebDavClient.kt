package com.themoon.y1.sync.webdav

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.xml.parsers.DocumentBuilderFactory

class DavHttpException(val code: Int) : IOException("HTTP $code")

/** A download did not replace its target: [WebDavClient.download]'s mayReplace said no (the target plays). */
class TargetBusy : IOException("Target in use")

/**
 * Moves [part] over [target]. One rename replaces the old file at once; a file system that cannot (FAT) gets the old
 * file moved aside first, and back if the new one cannot be moved. A failure leaves [part] where it is.
 */
internal fun replaceFile(part: File, target: File) {
    if (part.renameTo(target)) return
    if (!target.exists()) throw IOException("Cannot write ${target.name}")
    // A file system that cannot rename over a file (FAT): move the old file aside, and put it back if the new one fails
    // A name that is free: a ".old" that is already there may be the old file of an earlier failed rollback
    val old = generateSequence(0) { it + 1 }.map { File(target.path + if (it == 0) ".old" else ".old$it") }.first { !it.exists() }
    if (!target.renameTo(old)) throw IOException("Cannot replace ${target.name}")
    if (part.renameTo(target)) {
        old.delete()
        return
    }
    if (!old.renameTo(target)) throw IOException("Cannot write ${target.name}; the old file is ${old.name}")
    throw IOException("Cannot write ${target.name}")
}

/**
 * A new, empty temporary file next to [target], for a download or a copy that then replaces [target]. Made with an
 * exclusive create and a hidden name, so it is never a file that is there already (for example a synced "a.flac.part").
 */
internal fun newPartFile(target: File): File {
    val dir = target.parentFile ?: File(".")
    dir.mkdirs()
    return File.createTempFile(PART_PREFIX, PART_SUFFIX, dir)
}

/** The name of a [newPartFile]: "<prefix><random digits><suffix>". */
internal const val PART_PREFIX = ".partial-"
internal const val PART_SUFFIX = ".part"

/**
 * Same scheme, host (any case) and port; no port means the default of the scheme. OkHttp reads the host, so a name
 * that java.net.URI does not take as a host ("my_nas") works too.
 */
internal fun sameServer(a: URI, b: URI): Boolean {
    val x = HttpUrl.parse(a.toString()) ?: return false
    val y = HttpUrl.parse(b.toString()) ?: return false
    return x.scheme() == y.scheme() && x.host() == y.host() && x.port() == y.port()
}

/**
 * [url] as a server URI: an http or https scheme and a host, else null. A space typed by hand becomes %20.
 * The keyboard, the config file and [WebDavClient] use it, so a saved URL can always make a client.
 */
fun serverUri(url: String): URI? {
    val text = url.trim()
    val u = try {
        URI(text)
    } catch (_: URISyntaxException) {
        try { URI(text.replace(" ", "%20")) } catch (_: URISyntaxException) { return null }
    }
    val scheme = u.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    // A host: java.net.URI has none for a name like "my_nas" (only an authority), so OkHttp, which sends the requests, decides
    if (u.rawAuthority.isNullOrEmpty() || HttpUrl.parse(u.toString())?.host().isNullOrEmpty()) return null
    return u
}

/** The host of a [serverUri] URL (lower case), or null. */
fun serverHost(url: String): String? = serverUri(url)?.let { HttpUrl.parse(it.toString())?.host() }

/** The path of the server URL [uri] as an href (encoded, ends with '/'): the root of the folders. */
fun rootHrefOf(uri: URI): String {
    // The path that OkHttp sends: it removes "." and ".." segments ("/music/./" is "/music/"), so the server's hrefs
    // start with it. Percent encoding stays
    val path = HttpUrl.parse(uri.toString())?.encodedPath() ?: uri.rawPath
    return (path?.takeIf { it.isNotEmpty() } ?: "/").let { if (it.endsWith("/")) it else "$it/" }
}

/** Percent-decodes each path segment as UTF-8. '+' stays '+' (paths are not form-encoded). */
fun decodeHref(href: String): String {
    val bytes = ByteArrayOutputStream()
    val out = StringBuilder()
    var i = 0
    fun flush() {
        if (bytes.size() > 0) {
            out.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }
    }
    while (i < href.length) {
        val c = href[i]
        if (c == '%' && i + 2 < href.length) {
            val hi = Character.digit(href[i + 1], 16)
            val lo = Character.digit(href[i + 2], 16)
            if (hi >= 0 && lo >= 0) { // two hex digits, not a sign ("%+1") or a space
                bytes.write(hi * 16 + lo)
                i += 3
                continue
            }
        }
        flush()
        out.append(c)
        i++
    }
    flush()
    return out.toString()
}

object Propfind {
    private const val DAV = "DAV:"

    /** A PROPFIND answer larger than this is refused (a folder of some 100 000 files). */
    private const val MAX_BYTES = 32 * 1024 * 1024

    fun parse(xml: InputStream, requestHref: String): List<DavEntry> {
        // A WebDAV answer has no DOCTYPE: refuse one, so a server cannot make the parser read files or URLs. Checked in
        // the bytes, because Android's DocumentBuilderFactory does not take the feature below (and ignores it)
        val bytes = readLimited(xml)
        if (hasDoctype(bytes)) throw IOException("The server answer has a DOCTYPE")
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        }
        val doc = f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(bytes))
        // Only a WebDAV listing: an XML page of a gateway or a login form (HTTP 200) is not an empty folder
        val root = doc.documentElement
        if (root == null || root.namespaceURI != DAV || root.localName != "multistatus") {
            throw IOException("The server answer is not a WebDAV listing")
        }
        // Decoded: the URL that the user typed ("/Música") and the server's href ("/M%C3%BAsica/") name one folder
        val self = decodeHref(pathOf(requestHref)).trimEnd('/')
        val out = ArrayList<DavEntry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = text(r, "href")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            // A 207 can hold a failure for one resource (RFC 4918): that is an error, not a folder or file that is gone
            child(r, "status")?.let { st -> statusCode(st.textContent).takeIf { it !in 200..299 }?.let { throw DavHttpException(it) } }
            // The properties of the propstat blocks with a 2xx status only: a 404 for one property (no etag on a
            // folder, for example) is normal. No block with 2xx: nothing of this resource can be read
            val propstats = children(r, "propstat")
            val props = propstats.filter { ps -> child(ps, "status")?.let { statusCode(it.textContent) in 200..299 } ?: true }
                .mapNotNull { child(it, "prop") }
            if (propstats.isNotEmpty() && props.isEmpty()) {
                throw DavHttpException(propstats.firstNotNullOfOrNull { ps -> child(ps, "status")?.let { statusCode(it.textContent) } } ?: 500)
            }
            if (decodeHref(pathOf(href)).trimEnd('/') == self) continue
            fun prop(name: String) = props.firstNotNullOfOrNull { text(it, name) }
            out.add(
                DavEntry(
                    href = href,
                    isDir = props.any { it.getElementsByTagNameNS(DAV, "collection").length > 0 },
                    size = prop("getcontentlength")?.trim()?.toLongOrNull() ?: -1L,
                    etag = prop("getetag")?.trim()?.takeIf { it.isNotEmpty() },
                    lastModified = prop("getlastmodified")?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
        return out
    }

    /** The code of a DAV status line ("HTTP/1.1 403 Forbidden"); 500 if it cannot be read. */
    private fun statusCode(line: String): Int = line.trim().split(' ').getOrNull(1)?.toIntOrNull() ?: 500

    /** The child elements of [parent] named [name] in DAV: (not deeper ones). */
    private fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        var n = parent.firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == DAV && n.localName == name) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun child(parent: Element, name: String): Element? = children(parent, name).firstOrNull()

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("The server answer is too large")
        }
    }

    /** True when the bytes have "<!DOCTYPE" (any case; zero bytes are skipped, so UTF-16 is found too). */
    internal fun hasDoctype(bytes: ByteArray): Boolean {
        val word = "<!DOCTYPE"
        var matched = 0
        for (b in bytes) {
            if (b.toInt() == 0) continue
            val c = (b.toInt() and 0xFF).toChar().uppercaseChar()
            matched = if (c == word[matched]) matched + 1 else if (c == '<') 1 else 0
            if (matched == word.length) return true
        }
        return false
    }

    /** Some servers return absolute URLs in href; compare by path only. */
    private fun pathOf(href: String): String =
        if (href.startsWith("http://") || href.startsWith("https://")) URI(href).rawPath ?: href else href

    private fun text(parent: Element, name: String): String? {
        val n = parent.getElementsByTagNameNS(DAV, name)
        return if (n.length > 0) n.item(0).textContent else null
    }
}

/**
 * Minimal WebDAV client: PROPFIND Depth 1 and GET. [trust] accepts a pinned self-signed certificate, or one that
 * Android trusts for the host of the URL; it checks the host name itself (see CertPin), so OkHttp does not again.
 */
class WebDavClient(
    private val baseUrl: String,
    user: String,
    password: String,
    trust: PinningTrustManager?
) {
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)

    private val baseUri: URI = serverUri(baseUrl) ?: throw IOException("Not a valid URL")
    private val origin: String = baseUri.scheme + "://" + baseUri.rawAuthority

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // No redirects: a server that answers one is an error (HTTP 301...), not a link to follow to another host
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            if (trust != null) {
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf(trust), null)
                sslSocketFactory(ctx.socketFactory, trust)
                hostnameVerifier { _, _ -> true } // the trust manager checked the name, or the certificate is pinned
            }
        }
        .build()

    /** The path of [baseUrl], as an href (encoded, ends with '/'). */
    val rootHref: String = rootHrefOf(baseUri)

    /**
     * The URL of [href]. The password goes with every request, so an absolute href must name the server that the
     * user entered (same scheme, host and port): a link to another host is refused, not followed.
     */
    fun urlFor(href: String): String {
        // A path only: "@host/x" would make the origin's host the user name of another host
        if (!href.startsWith("http://") && !href.startsWith("https://")) {
            if (!href.startsWith("/")) throw IOException("Invalid link from the server")
            return origin + href
        }
        val u = try { URI(href) } catch (e: URISyntaxException) { throw IOException("Invalid link from the server", e) }
        if (!sameServer(u, baseUri)) throw IOException("The server sent a link to another host")
        return href
    }

    /** An absolute href of this server as its path (the ledger key and the folder hrefs are paths); another host is refused. */
    internal fun pathHref(href: String): String {
        if (!href.startsWith("http://") && !href.startsWith("https://")) return href
        urlFor(href)
        return URI(href).rawPath ?: "/"
    }

    fun list(href: String): List<DavEntry> {
        val body = RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), PROPFIND_BODY)
        val req = Request.Builder().url(urlFor(href)).method("PROPFIND", body)
            .header("Depth", "1").header("Authorization", auth).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code() != 207 && !resp.isSuccessful) throw DavHttpException(resp.code())
            val stream = resp.body()?.byteStream() ?: throw IOException("Empty PROPFIND response")
            return Propfind.parse(stream, href).map { it.copy(href = pathHref(it.href)) }
        }
    }

    /**
     * GET into a new hidden `.part` file next to [target] ([newPartFile]), check the length, then replace [target] if [mayReplace] says yes (else [TargetBusy]).
     * A failed or cancelled download leaves [target] as it was.
     */
    fun download(
        href: String,
        target: File,
        expectedSize: Long,
        cancelled: () -> Boolean,
        mayReplace: () -> Boolean = { true },
        progress: (Long) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val part = newPartFile(target)
        try {
            val req = Request.Builder().url(urlFor(href)).header("Authorization", auth).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw DavHttpException(resp.code())
                val input = resp.body()?.byteStream() ?: throw IOException("Empty body")
                var total = 0L
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw CancellationException("Sync cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        progress(total)
                    }
                }
                if (expectedSize >= 0 && total != expectedSize) {
                    throw IOException("Short download: $total of $expectedSize bytes")
                }
            }
            if (!mayReplace()) throw TargetBusy()
            replaceFile(part, target)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    private companion object {
        val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getcontentlength/><D:getetag/><D:getlastmodified/></D:prop></D:propfind>"""
    }
}
