package com.themoon.y1

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.net.Socket
import java.nio.file.Files

class Y1WebServerTest {

    private fun request(server: Y1WebServer, request: String): String {
        Socket("127.0.0.1", server.getListeningPort()).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.shutdownOutput()
            val buffer = ByteArray(1024)
            val response = StringBuilder()
            var count: Int
            while (socket.getInputStream().read(buffer).also { count = it } != -1) {
                response.append(String(buffer, 0, count, Charsets.UTF_8))
            }
            return response.toString()
        }
    }

    private fun startServer(roots: WebRoots): Y1WebServer {
        val server = Y1WebServer(null, roots, 0)
        server.start()
        val deadline = System.currentTimeMillis() + 3000
        while (server.getListeningPort() <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(server.getListeningPort() > 0)
        return server
    }

    private fun stopServer(server: Y1WebServer) {
        server.stopServer()
        server.join(3000)
        assertFalse(server.isAlive)
    }

    private fun ok(response: String) = response.startsWith("HTTP/1.1 200")

    private fun body(response: String) = response.substringAfter("\r\n\r\n")

    @Test
    fun uploadTraversalFailuresAndShutdown() {
        val root = Files.createTempDirectory("inni-server-test").toFile()
        val server = startServer(WebRoots(root) { null })
        try {
            assertTrue(request(server, "POST /api/upload?dir=Internal%20Storage&name=song.mp3 HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc").startsWith("HTTP/1.1 200"))
            val song = File(root, "song.mp3")
            assertEquals("abc", Files.readAllBytes(song.toPath()).toString(Charsets.UTF_8))
            assertTrue(request(server, "POST /api/save?path=song.mp3 HTTP/1.1\r\nContent-Length: 5\r\n\r\nx").startsWith("HTTP/1.1 400"))
            assertEquals("abc", Files.readAllBytes(song.toPath()).toString(Charsets.UTF_8))
            assertTrue(request(server, "GET /api/file?path=..%2Foutside HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST /api/delete?path= HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(root.isDirectory)
            assertTrue(
                request(server, "POST /api/delete?path=song.mp3 HTTP/1.1\r\nHost: localhost\r\nOrigin: https://other.example\r\n\r\n")
                    .startsWith("HTTP/1.1 400")
            )
            assertTrue(song.exists())
            assertTrue(request(server, "POST /api/rename?dir=Internal%20Storage&old=song.mp3&new=renamed.mp3 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
            assertTrue(request(server, "GET /api/file?path=renamed.mp3 HTTP/1.1\r\n\r\n").endsWith("abc"))
            assertTrue(request(server, "GET /missing HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
        } finally {
            stopServer(server)
            root.listFiles()?.forEach { Files.delete(it.toPath()) }
            Files.delete(root.toPath())
        }
    }

    @Test
    fun internalStorageAndSdCardAreTopLevelFolders() {
        val internal = Files.createTempDirectory("inni-server-internal").toFile()
        val card = Files.createTempDirectory("inni-server-card").toFile()
        var cardInPlayer = true
        val server = startServer(WebRoots(internal) { if (cardInPlayer) card else null })
        try {
            assertEquals(
                """[{"name":"Internal Storage","isDir":true,"storage":true},{"name":"SD Card","isDir":true,"storage":true}]""",
                body(request(server, "GET /api/list?dir= HTTP/1.1\r\n\r\n"))
            )
            assertTrue(ok(request(server, "POST /api/upload?dir=Internal%20Storage%2FMusic&name=a.mp3 HTTP/1.1\r\nContent-Length: 1\r\n\r\na")))
            assertTrue(ok(request(server, "POST /api/upload?dir=SD%20Card%2FMusic&name=b.mp3 HTTP/1.1\r\nContent-Length: 1\r\n\r\nb")))
            assertTrue(File(internal, "Music/a.mp3").isFile)
            assertTrue(File(card, "Music/b.mp3").isFile)
            assertEquals("""[{"name":"b.mp3","isDir":false}]""", body(request(server, "GET /api/list?dir=SD%20Card%2FMusic HTTP/1.1\r\n\r\n")))
            assertTrue(request(server, "GET /api/file?path=SD%20Card%2FMusic%2Fb.mp3 HTTP/1.1\r\n\r\n").endsWith("b"))

            // A path without a storage folder is on internal storage, as before
            assertTrue(request(server, "GET /api/file?path=Music%2Fa.mp3 HTTP/1.1\r\n\r\n").endsWith("a"))
            assertTrue(ok(request(server, "POST /api/upload?dir=Music&name=c.mp3 HTTP/1.1\r\nContent-Length: 1\r\n\r\nc")))
            assertTrue(File(internal, "Music/c.mp3").isFile)

            // No path goes out of its storage, and the top level and the storage folders cannot change
            for (path in listOf("Internal%20Storage%2F..%2Fx", "SD%20Card%2F..%2Fx", "SD%20Card%2F..", "..%2Fx", "%2Ftmp")) {
                assertTrue(path, request(server, "GET /api/file?path=$path HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
                assertTrue(path, request(server, "POST /api/save?path=$path HTTP/1.1\r\nContent-Length: 1\r\n\r\nx").startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(server, "POST /api/upload?dir=SD%20Card%2F..&name=x HTTP/1.1\r\nContent-Length: 1\r\n\r\nx").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST /api/upload?dir=&name=x HTTP/1.1\r\nContent-Length: 1\r\n\r\nx").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST /api/create?dir=&name=x HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST /api/rename?dir=&old=SD%20Card&new=x HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST /api/delete?path=SD%20Card HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST /api/delete?path=Internal%20Storage%2F HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(card.isDirectory && internal.isDirectory)

            assertTrue(ok(request(server, "POST /api/delete?path=SD%20Card%2FMusic HTTP/1.1\r\n\r\n")))
            assertFalse(File(card, "Music").exists())

            // Without a card: only internal storage, and SD Card paths fail (no "SD Card" folder on internal storage)
            cardInPlayer = false
            assertEquals(
                """[{"name":"Internal Storage","isDir":true,"storage":true}]""",
                body(request(server, "GET /api/list?dir= HTTP/1.1\r\n\r\n"))
            )
            assertTrue(request(server, "GET /api/list?dir=SD%20Card HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST /api/upload?dir=SD%20Card&name=x HTTP/1.1\r\nContent-Length: 1\r\n\r\nx").startsWith("HTTP/1.1 400"))
            assertFalse(File(internal, "SD Card").exists())
        } finally {
            stopServer(server)
            internal.deleteRecursively()
            card.deleteRecursively()
        }
    }
}
