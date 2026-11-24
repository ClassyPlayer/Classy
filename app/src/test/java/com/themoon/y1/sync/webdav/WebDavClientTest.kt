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

    /** No temporary file ([newPartFile]) is left next to [target]. */
    private fun assertNoPartFile(target: File) {
        assertEquals(emptyList<String>(), target.parentFile?.list()?.filter { it.startsWith(PART_PREFIX) } ?: emptyList<String>())
    }

    /** An HTTP server that answers each request with a 301 to [location]. */
    private fun serveRedirect(location: String): ServerSocket {
        val s = ServerSocket(0)
        Thread {
            while (!s.isClosed) {
                try {
                    s.accept().use { c ->
                        val r = c.getInputStream().bufferedReader()
                        while (r.readLine()?.isNotEmpty() == true) { }
                        c.getOutputStream().apply {
                            write("HTTP/1.1 301 Moved Permanently\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            flush()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
        return s
    }

    @Test
    fun aRedirectIsAnErrorAndIsNotFollowed() {
        // The password goes with every request: a redirect to another host must not get it
        val other = ServerSocket(0)
        var otherGotAConnection = false
        Thread { try { other.accept().close(); otherGotAConnection = true } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        val redirect = serveRedirect("http://127.0.0.1:${other.localPort}/steal/")
        try {
            val c = WebDavClient("http://127.0.0.1:${redirect.localPort}/music/", "u", "p", null)
            try { c.list("/music/"); fail("expected HTTP 301") } catch (e: DavHttpException) { assertEquals(301, e.code) }
            val target = File(tmp.root, "r/song.flac")
            try { c.download("/music/song.flac", target, 5, { false }, progress = {}); fail("expected HTTP 301") } catch (e: DavHttpException) { assertEquals(301, e.code) }
            assertFalse(target.exists())
            assertNoPartFile(target)
            Thread.sleep(200)
            assertFalse(otherGotAConnection)
        } finally {
            redirect.close()
            other.close()
        }
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
        WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 5, { false }, progress = {})
        assertEquals("hello", target.readText())
        assertNoPartFile(target)
    }

    @Test
    fun aTargetThatMayNotBeReplacedStaysAsItWas() {
        // The file started to play during the download: the old file stays, and no part file is left
        val base = serve("hello".toByteArray())
        val target = File(tmp.root, "a/song.flac").apply { parentFile!!.mkdirs(); writeText("old") }
        try {
            WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 5, { false }, { false }, {})
            fail("expected TargetBusy")
        } catch (_: TargetBusy) {
        }
        assertEquals("old", target.readText())
        assertNoPartFile(target)
    }

    @Test
    fun failedDownloadKeepsOldFile() {
        val base = serve("hel".toByteArray())
        val target = tmp.newFile("song.flac").apply { writeText("old") }
        try {
            WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 5, { false }, progress = {})
            fail("expected an exception for a short download")
        } catch (_: java.io.IOException) {
        }
        assertEquals("old", target.readText())
        assertNoPartFile(target)
    }

    @Test
    fun cancelRemovesPart() {
        val base = serve(ByteArray(200_000) { 1 })
        val target = File(tmp.root, "song.flac")
        try {
            WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 200_000, { true }, progress = {})
            fail("expected cancel")
        } catch (_: CancellationException) {
        }
        assertFalse(target.exists())
        assertNoPartFile(target)
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
        assertNull(serverUri("https://user:secret@nas/music")) // no password in the URL: it shows on the screen
        assertNull(serverUri("http://user:secret@my_nas/dav")) // also for a host that java.net.URI does not read
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

    @Test
    fun aDoctypeIsFoundInTheBytes() {
        assertTrue(Propfind.hasDoctype("<?xml version=\"1.0\"?><!doctype x [<!ENTITY e SYSTEM \"file:///x\">]>".toByteArray()))
        assertTrue(Propfind.hasDoctype("<<!DOCTYPE x>".toByteArray()))
        assertTrue(Propfind.hasDoctype("<!DOCTYPE x>".toByteArray(Charsets.UTF_16LE)))
        assertFalse(Propfind.hasDoctype("<D:multistatus xmlns:D=\"DAV:\"><D:href>/a/DOCTYPE.flac</D:href></D:multistatus>".toByteArray()))
    }

    @Test
    fun aSyncedFileNamedLikeAPartFileIsNotTouched() {
        val base = serve("hello".toByteArray())
        val target = File(tmp.root, "a/song.flac")
        val other = File(tmp.root, "a/song.flac.part").apply { parentFile!!.mkdirs(); writeText("mine") }
        WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 5, { false }, progress = {})
        assertEquals("hello", target.readText())
        assertEquals("mine", other.readText())
        assertEquals(listOf("song.flac", "song.flac.part"), target.parentFile!!.list()!!.sorted()) // no temporary file left
    }

    @Test
    fun anXmlPageThatIsNotAListingIsRefused() {
        // A gateway or login page with HTTP 200 is not an empty folder
        assertRefused { Propfind.parse("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body/></html>".byteInputStream(), "/music/") }
        assertRefused { Propfind.parse("<multistatus><response/></multistatus>".byteInputStream(), "/music/") } // not in DAV:
        assertTrue(Propfind.parse("<D:multistatus xmlns:D=\"DAV:\"/>".byteInputStream(), "/music/").isEmpty()) // an empty folder
    }

    private fun ms(vararg responses: String) = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">${responses.joinToString("")}</D:multistatus>"""

    @Test
    fun aFailedFolderInsideA207IsAnErrorNotAnEmptyFolder() {
        // The folder itself is refused: an error, so its files do not become "Deleted on NAS"
        val forbidden = ms("<D:response><D:href>/music/Rock/</D:href><D:propstat><D:prop><D:resourcetype/></D:prop><D:status>HTTP/1.1 403 Forbidden</D:status></D:propstat></D:response>")
        try { Propfind.parse(forbidden.byteInputStream(), "/music/Rock/"); fail("expected 403") } catch (e: DavHttpException) { assertEquals(403, e.code) }
        val resourceStatus = ms("<D:response><D:href>/music/Rock/a.flac</D:href><D:status>HTTP/1.1 423 Locked</D:status></D:response>")
        // An entry of the folder is refused: an error of the listing, not a folder that is gone (not a DavHttpException)
        try { Propfind.parse(resourceStatus.byteInputStream(), "/music/Rock/"); fail("expected 423") } catch (e: DavEntryRefused) { assertEquals(423, e.code) }
    }

    @Test
    fun aPropertyThatIsMissingDoesNotHideTheOthers() {
        // A folder with no etag: the 404 block is ignored, the 200 block is read
        val xml = ms("""<D:response><D:href>/music/Rock/Live/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            <D:propstat><D:prop><D:getetag/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response>""")
        val e = Propfind.parse(xml.byteInputStream(), "/music/Rock/").single()
        assertTrue(e.isDir)
        assertNull(e.etag)
    }

    @Test
    fun theRootIsThePathThatOkHttpSends() {
        assertEquals("/music/", rootHrefOf(serverUri("https://nas/music/./")!!))
        assertEquals("/music/", rootHrefOf(serverUri("https://nas/music/Rock/..")!!))
        assertEquals("/M%C3%BAsica/", rootHrefOf(serverUri("https://nas/Música")!!))
    }

    @Test
    fun aDownloadStopsAtTheSizeThatTheServerGave() {
        // The listing said 5 bytes; the server sends more: refused, no file, no temporary file
        val base = serve("hello world".toByteArray())
        val target = File(tmp.root, "big/song.flac")
        try {
            WebDavClient(base, "u", "p", null).download("/music/song.flac", target, 5, { false }, progress = {})
            fail("expected an error")
        } catch (_: java.io.IOException) {
        }
        assertFalse(target.exists())
        assertNoPartFile(target)
    }

    @Test
    fun aFolderAtTheTargetIsNotMovedAside() {
        val dir = File(tmp.root, "Music/Foo").apply { mkdirs(); File(this, "mine.mp3").writeText("x") }
        val part = File(tmp.root, "Music/.partial-1.part").apply { writeText("new") }
        try { replaceFile(part, dir); fail("expected an error") } catch (_: java.io.IOException) {}
        assertTrue(File(dir, "mine.mp3").exists())
        assertFalse(File(tmp.root, "Music/Foo.old").exists())
    }

    @Test
    fun aListingLargerThanTheCapIsRefusedBeforeItIsInMemory() {
        val big = object : java.io.InputStream() {
            var n = 0L
            override fun read(): Int = if (n++ < 20L * 1024 * 1024) 'a'.code else -1
        }
        try { Propfind.readLimited(big); fail("expected an error") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("too large")) }
    }
}
