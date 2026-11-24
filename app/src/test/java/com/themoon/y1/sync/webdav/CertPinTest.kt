package com.themoon.y1.sync.webdav

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException

class CertPinTest {

    private fun cert(name: String): X509Certificate =
        javaClass.getResourceAsStream("/webdav/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private val fpA = "A1:36:01:5F:1B:67:73:E8:18:E4:2D:90:60:75:E3:1F:6F:9B:50:F4:C2:C8:50:73:29:8B:7C:46:8F:54:8E:D4"

    @Test
    fun fingerprintFormat() {
        assertEquals(fpA, sha256Fingerprint(cert("cert_a.der").encoded))
    }

    @Test
    fun pinnedCertPasses() {
        PinningTrustManager(fpA).checkServerTrusted(arrayOf(cert("cert_a.der")), "RSA")
    }

    @Test
    fun otherCertThrowsWithFingerprint() {
        try {
            PinningTrustManager(fpA).checkServerTrusted(arrayOf(cert("cert_b.der")), "RSA")
            fail("expected CertNotTrusted")
        } catch (e: CertNotTrusted) {
            assertTrue(e.fingerprint.startsWith("9F:17:71:95"))
        }
    }

    @Test
    fun nullPinAlwaysThrows() {
        try {
            PinningTrustManager(null).checkServerTrusted(arrayOf(cert("cert_a.der")), "RSA")
            fail("expected CertNotTrusted")
        } catch (e: CertNotTrusted) {
            assertEquals(fpA, e.fingerprint)
        }
    }

    @Test
    fun findsCertNotTrustedInCauseChain() {
        val inner = CertNotTrusted(fpA)
        val wrapped = SSLHandshakeException("handshake").apply { initCause(RuntimeException(inner)) }
        assertSame(inner, findCertNotTrusted(wrapped))
        assertNull(findCertNotTrusted(RuntimeException("x")))
    }

    private class FakeSystem(val trusts: Boolean) : javax.net.ssl.X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!trusts) throw java.security.cert.CertificateException("unknown authority")
        }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun aCertificateThatTheSystemTrustsForThisHostNeedsNoPin() {
        // A public certificate that is renewed: no Trust Certificate step, before or after. cert_c has the name in its
        // subjectAltName, as a public certificate has (OkHttp does not read the CN)
        PinningTrustManager(null, FakeSystem(trusts = true), "test-nas-c").checkServerTrusted(arrayOf(cert("cert_c.der")), "RSA")
    }

    @Test
    fun aTrustedCertificateForAnotherNameCanBePinned() {
        // A NAS by IP address with a public certificate for its name: not trusted as it is, but the user can pin it
        try {
            PinningTrustManager(null, FakeSystem(trusts = true), "192.168.1.10").checkServerTrusted(arrayOf(cert("cert_a.der")), "RSA")
            fail("expected CertNotTrusted")
        } catch (e: CertNotTrusted) {
            assertEquals(fpA, e.fingerprint)
        }
        PinningTrustManager(fpA, FakeSystem(trusts = true), "192.168.1.10").checkServerTrusted(arrayOf(cert("cert_a.der")), "RSA")
    }

    @Test
    fun aCertificateThatTheSystemRefusesNeedsThePin() {
        try {
            PinningTrustManager(null, FakeSystem(trusts = false), "test-nas-a").checkServerTrusted(arrayOf(cert("cert_a.der")), "RSA")
            fail("expected CertNotTrusted")
        } catch (e: CertNotTrusted) {
            assertEquals(fpA, e.fingerprint)
        }
        PinningTrustManager(fpA, FakeSystem(trusts = false), "test-nas-a").checkServerTrusted(arrayOf(cert("cert_a.der")), "RSA")
    }
}
