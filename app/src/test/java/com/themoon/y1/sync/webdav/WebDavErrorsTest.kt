package com.themoon.y1.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

class WebDavErrorsTest {

    @Test
    fun errorTextMapping() {
        assertEquals("Server unreachable", errorText(UnknownHostException("nas")))
        assertEquals("Server unreachable", errorText(ConnectException("refused")))
        assertEquals("Server unreachable", errorText(SocketTimeoutException("slow")))
        assertEquals("Check username/password", errorText(DavHttpException(401)))
        assertEquals("Check username/password", errorText(DavHttpException(403)))
        assertEquals("HTTP 500", errorText(DavHttpException(500)))
        assertEquals("Not enough space: 1.5 GB / 200 MB", errorText(NotEnoughSpace(1_610_612_736L, 209_715_200L)))
        // the fixed texts go through the translation
        assertEquals("[Server unreachable]", errorText(UnknownHostException("nas")) { "[$it]" })
        assertEquals("[Not enough space]: 1 MB / 1 MB", errorText(NotEnoughSpace(1_048_576L, 1_048_576L)) { "[$it]" })
        assertEquals("Certificate not trusted", errorText(SSLHandshakeException("x").apply { initCause(CertNotTrusted("AA")) }))
        assertEquals("Sync cancelled", errorText(CancellationException("x")))
    }
}
