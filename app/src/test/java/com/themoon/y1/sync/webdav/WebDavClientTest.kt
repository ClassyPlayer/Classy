package com.themoon.y1.sync.webdav

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CancellationException

class WebDavClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var server: ServerSocket? = null

    @After
    fun stop() {
        server?.close()
    }

    private val multistatus = """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/music/Rock/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
              <D:getlastmodified>Tue, 10 Nov 2026 10:00:00 GMT</D:getlastmodified>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/music/Rock/Live/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/music/Rock/01%20Song.flac</D:href>
            <D:propstat><D:prop>
              <D:resourcetype/>
              <D:getcontentlength>1234</D:getcontentlength>
              <D:getetag>"abc-1"</D:getetag>
              <D:getlastmodified>Wed, 11 Nov 2026 10:00:00 GMT</D:getlastmodified>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/music/Rock/cover.jpg</D:href>
            <D:propstat><D:prop>
              <D:resourcetype/>
              <D:getcontentlength>99</D:getcontentlength>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    private fun parse(requestHref: String = "/music/Rock/") =
        Propfind.parse(multistatus.byteInputStream(), requestHref)

    @Test
    fun parsesSynologyMultistatus() {
        val e = parse()
        assertEquals(3, e.size)
        val dir = e.first { it.href == "/music/Rock/Live/" }
        assertTrue(dir.isDir)
        val song = e.first { it.href == "/music/Rock/01%20Song.flac" }
        assertFalse(song.isDir)
        assertEquals(1234L, song.size)
        assertEquals("\"abc-1\"", song.etag)
        assertEquals("Wed, 11 Nov 2026 10:00:00 GMT", song.lastModified)
    }

    @Test
    fun missingEtagIsNull() {
        val cover = parse().first { it.href.endsWith("cover.jpg") }
        assertNull(cover.etag)
        assertNull(cover.lastModified)
        assertEquals(99L, cover.size)
    }

    @Test
    fun skipsSelfEntry() {
        assertTrue(parse("/music/Rock/").none { it.href == "/music/Rock/" })
        assertTrue(parse("/music/Rock").none { it.href == "/music/Rock/" })
    }

    @Test
    fun decodesEncodedHrefs() {
        assertEquals("/music/Björk/01 Joga & x.flac", decodeHref("/music/Bj%C3%B6rk/01%20Joga%20%26%20x.flac"))
        assertEquals("/a+b/c", decodeHref("/a+b/c"))
    }

    /** One-response-per-connection HTTP server; declares [declaredLength] but sends [body]. */
    private fun serve(body: ByteArray, declaredLength: Long = body.size.toLong()): String {
        val s = ServerSocket(0)
        server = s
        Thread {
            while (!s.isClosed) {
                try {
                    s.accept().use { c ->
                        val r = c.getInputStream().bufferedReader()
                        while (r.readLine()?.isNotEmpty() == true) { }
                        val out = c.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: $declaredLength\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                    }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${s.localPort}/music/"
    }

    @Test
    fun downloadRenamesPartOnSuccess() {
        val base = serve("hello".toByteArray())
        val target = File(tmp.root, "a/song.flac")
        WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 5, { false }, {})
        assertEquals("hello", target.readText())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun failedDownloadKeepsOldFile() {
        val base = serve("hel".toByteArray())
        val target = tmp.newFile("song.flac").apply { writeText("old") }
        try {
            WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 5, { false }, {})
            fail("expected an exception for a short download")
        } catch (_: java.io.IOException) {
        }
        assertEquals("old", target.readText())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun cancelRemovesPart() {
        val base = serve(ByteArray(200_000) { 1 })
        val target = File(tmp.root, "song.flac")
        try {
            WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 200_000, { true }, {})
            fail("expected cancel")
        } catch (_: CancellationException) {
        }
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun urlForJoinsHostAndHref() {
        val c = WebDavClient("https://nas:5006/music/", "u", "p", null)
        assertEquals("https://nas:5006/music/Rock/01%20Song.flac", c.urlFor("/music/Rock/01%20Song.flac"))
    }

    private fun assertRefused(block: () -> Unit) {
        try { block() } catch (e: Exception) { return }
        fail("expected an exception")
    }

    @Test
    fun anAbsoluteHrefMustNameTheServerTheUserEntered() {
        val c = WebDavClient("https://nas.example:5006/dav/", "u", "p", null)
        assertEquals("https://nas.example:5006/dav/a.flac", c.urlFor("https://nas.example:5006/dav/a.flac"))
        assertEquals("https://nas.example:5006/music/a.flac", c.urlFor("/music/a.flac"))
        // The password goes with every request: another host, scheme or port gets nothing
        assertRefused { c.urlFor("https://evil.example/dav/a.flac") }
        assertRefused { c.urlFor("http://nas.example:5006/dav/a.flac") }
        assertRefused { c.urlFor("https://nas.example:5007/dav/a.flac") }
        assertRefused { c.urlFor("https://nas.example:5006@evil.example/a.flac") }
        // Not a path: the origin plus "@evil.example/a" would name another host
        assertRefused { c.urlFor("@evil.example/a.flac") }
        assertRefused { c.urlFor("a.flac") }
    }

    @Test
    fun anAbsoluteHrefOfTheServerBecomesItsPath() {
        val c = WebDavClient("https://nas.example:5006/dav/", "u", "p", null)
        assertEquals("/dav/Rock/a%20b.flac", c.pathHref("https://nas.example:5006/dav/Rock/a%20b.flac"))
        assertEquals("/dav/Rock/", c.pathHref("/dav/Rock/"))
        assertRefused { c.pathHref("https://evil.example/dav/Rock/a.flac") }
        assertRefused { c.pathHref("http://nas.example:5006/dav/a.flac") }
    }

    @Test
    fun theDefaultPortIsTheSameServer() {
        val c = WebDavClient("https://nas.example/dav/", "u", "p", null)
        assertEquals("https://NAS.example:443/dav/a", c.urlFor("https://NAS.example:443/dav/a"))
    }

    @Test
    fun aSpaceInTheUrlIsEncoded() {
        assertEquals("/My%20Music/", WebDavClient("https://nas.example/My Music/", "u", "p", null).rootHref)
    }

    @Test
    fun anHrefWithWhitespaceIsTrimmed() {
        val xml = """<D:multistatus xmlns:D="DAV:"><D:response><D:href>
            /music/Rock/x.flac
          </D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>5</D:getcontentlength></D:prop></D:propstat></D:response></D:multistatus>"""
        assertEquals("/music/Rock/x.flac", Propfind.parse(xml.byteInputStream(), "/music/Rock/").single().href)
    }

    @Test
    fun aDoctypeIsRefused() {
        val xml = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>&x;</D:href></D:response></D:multistatus>"""
        assertRefused { Propfind.parse(xml.byteInputStream(), "/music/") }
    }

    @Test
    fun aPercentSignWithoutTwoHexDigitsStaysAsItIs() {
        assertEquals("/a%+1/b", decodeHref("/a%+1/b"))
        assertEquals("/a%zz", decodeHref("/a%zz"))
        assertEquals("/a b", decodeHref("/a%20b"))
    }

    @Test
    fun replaceFileReplacesTheOldFileAndTakesThePart() {
        val target = tmp.newFile("song.flac").apply { writeText("old") }
        val part = tmp.newFile("song.flac.part").apply { writeText("new") }
        replaceFile(part, target)
        assertEquals("new", target.readText())
        assertFalse(part.exists())
    }

    @Test
    fun replaceFileWorksWhenThereIsNoOldFile() {
        val target = File(tmp.root, "fresh.flac")
        val part = tmp.newFile("fresh.flac.part").apply { writeText("x") }
        replaceFile(part, target)
        assertEquals("x", target.readText())
    }

    @Test
    fun serverUriNeedsSchemeAndHost() {
        assertEquals("nas", serverUri(" https://nas:5006/music ")?.host)
        assertEquals("/My Music", serverUri("http://nas/My Music")?.path) // a typed space becomes %20
        assertNull(serverUri("https://"))
        assertNull(serverUri("https:///music"))
        assertNull(serverUri("https://nas/100%"))
        assertNull(serverUri("ftp://nas/music"))
        assertNull(serverUri("nas:5006"))
        assertEquals("my_nas", serverHost("http://my_nas:5005/dav")) // java.net.URI has no host for it; OkHttp has
        assertEquals("nas", serverHost("HTTPS://NAS:5006/dav"))
        assertEquals("/", rootHrefOf(serverUri("https://nas:5006")!!))
        assertEquals("/My%20Music/", rootHrefOf(serverUri("https://nas/My Music")!!))
    }

    @Test
    fun sameServerReadsHostsThatUriDoesNot() {
        assertTrue(sameServer(java.net.URI("http://my_nas:5005/a"), java.net.URI("http://MY_NAS:5005/b")))
        assertFalse(sameServer(java.net.URI("http://my_nas:5005/a"), java.net.URI("http://my_nas:5006/a")))
        assertTrue(sameServer(java.net.URI("https://nas/a"), java.net.URI("https://nas:443/b")))
    }

    @Test(expected = java.io.IOException::class)
    fun clientRefusesUrlWithNoHost() {
        WebDavClient("https://", "u", "p", null)
    }

    @Test
    fun theFolderItselfIsNotItsOwnSubFolderWhenTheSpellingDiffers() {
        val xml = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/M%c3%basica/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
          <D:response><D:href>/M%C3%BAsica/Rock/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
        </D:multistatus>"""
        // The typed URL has the raw name; the server sends it encoded, here in lower case
        assertEquals(listOf("/M%C3%BAsica/Rock/"), Propfind.parse(xml.byteInputStream(), "/Música/").map { it.href })
    }
}
