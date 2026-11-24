package com.themoon.y1.sync.webdav

import okhttp3.Credentials
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
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

class DavHttpException(val code: Int) : IOException("HTTP $code")

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

/** Same scheme, host (any case) and port; no port means the default of the scheme. */
internal fun sameServer(a: URI, b: URI): Boolean {
    fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
    return a.scheme.equals(b.scheme, ignoreCase = true) && a.host?.equals(b.host, ignoreCase = true) == true && port(a) == port(b)
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
    return u.takeIf { !it.host.isNullOrEmpty() }
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

    fun parse(xml: InputStream, requestHref: String): List<DavEntry> {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // A WebDAV answer has no DOCTYPE: refuse one, so a server cannot make the parser read files or URLs.
            // A parser that does not know this feature keeps its default.
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        }
        val doc = f.newDocumentBuilder().parse(xml)
        val self = pathOf(requestHref).trimEnd('/')
        val out = ArrayList<DavEntry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = text(r, "href")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (pathOf(href).trimEnd('/') == self) continue
            val isDir = (r.getElementsByTagNameNS(DAV, "collection").length > 0)
            out.add(
                DavEntry(
                    href = href,
                    isDir = isDir,
                    size = text(r, "getcontentlength")?.trim()?.toLongOrNull() ?: -1L,
                    etag = text(r, "getetag")?.trim()?.takeIf { it.isNotEmpty() },
                    lastModified = text(r, "getlastmodified")?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
        return out
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
 * Minimal WebDAV client: PROPFIND Depth 1 and GET. [trust] pins a self-signed cert (see CertPin);
 * when it is set, hostname checks are skipped because the certificate itself is pinned.
 */
class WebDavClient(
    private val baseUrl: String,
    user: String,
    password: String,
    trust: X509TrustManager?
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
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    /** The path of [baseUrl], as an href (encoded, ends with '/'). */
    val rootHref: String = (baseUri.rawPath ?: "/").let { if (it.endsWith("/")) it else "$it/" }

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

    /** GET into `target.part`, check the length, then replace [target]. A failed or cancelled download leaves [target] as it was. */
    fun download(href: String, target: File, expectedSize: Long, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
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
