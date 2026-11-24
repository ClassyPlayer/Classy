package com.themoon.y1.sync.webdav

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavConfigFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun validConfigImportedAndDeleted() {
        val f = tmp.newFile("classy_webdav.json").apply {
            writeText("""{"url":"https://nas.example:5006/music","user":"fakeuser","password":"fake-pass"}""")
        }
        val c = WebDavConfigFile.importFrom(f).getOrThrow()
        assertEquals(WebDavConfig("https://nas.example:5006/music", "fakeuser", "fake-pass"), c)
        assertFalse(f.exists())
    }

    @Test
    fun invalidConfigKept() {
        val f = tmp.newFile("classy_webdav.json").apply { writeText("""{"url":"https://nas.example"}""") }
        assertTrue(WebDavConfigFile.importFrom(f).isFailure)
        assertTrue(f.exists())
        f.writeText("not json")
        assertTrue(WebDavConfigFile.importFrom(f).isFailure)
        assertTrue(f.exists())
    }

    @Test
    fun ftpUrlRejected() {
        val f = tmp.newFile("classy_webdav.json").apply {
            writeText("""{"url":"ftp://nas.example","user":"u","password":"p"}""")
        }
        assertTrue(WebDavConfigFile.importFrom(f).isFailure)
        assertTrue(f.exists())
    }

    @Test
    fun missingFileFails() {
        assertTrue(WebDavConfigFile.importFrom(java.io.File(tmp.root, "nope.json")).isFailure)
    }
}
