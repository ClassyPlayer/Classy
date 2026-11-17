package com.themoon.y1.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class PodcastTrustTest {

    /** The app assets: the unit tests run in the app module folder. */
    private fun asset(path: String): () -> InputStream = { File("src/main/assets", path).inputStream() }

    private fun resource(name: String): X509Certificate =
        PodcastTrust.loadCertificates(listOf({ javaClass.getResourceAsStream("/certs/$name")!! })).single()

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

    @Test
    fun bundledRoots_areTheLetsEncryptRoots() {
        val roots = PodcastTrust.loadCertificates(PodcastTrust.ROOT_ASSETS.map { asset(it) })
        // The fingerprints that https://letsencrypt.org/certificates/ (crt.sh) gives
        assertEquals(
            listOf(
                "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6",
                "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470",
            ),
            roots.map { sha256(it) }
        )
        val issuers = PodcastTrust.trustManager(roots).acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue(issuers.any { it.contains("CN=ISRG Root X1") })
        assertTrue(issuers.any { it.contains("CN=ISRG Root X2") })
    }

    @Test
    fun brokenFile_isSkipped() {
        val roots = PodcastTrust.loadCertificates(
            listOf({ ByteArrayInputStream("not a certificate".toByteArray()) }, asset(PodcastTrust.ROOT_ASSETS[0]))
        )
        assertEquals(1, roots.size)
    }

    @Test
    fun selfSignedServer_isRejected() {
        val trust = PodcastTrust.trustManager(listOf(resource("test-root.pem")))
        try {
            trust.checkServerTrusted(arrayOf(resource("self-signed.pem")), "ECDHE_RSA")
            fail("A self-signed certificate must not be trusted")
        } catch (expected: CertificateException) {
        }
    }

    @Test
    fun serverOfABundledRoot_isTrusted() {
        // The system roots do not know the test root: only the bundled roots accept the chain
        val trust = PodcastTrust.trustManager(listOf(resource("test-root.pem")))
        trust.checkServerTrusted(arrayOf(resource("test-leaf.pem")), "ECDHE_RSA")
    }

    @Test
    fun serverOfABundledRoot_isRejectedWithoutIt() {
        val trust = PodcastTrust.trustManager(emptyList())
        try {
            trust.checkServerTrusted(arrayOf(resource("test-leaf.pem")), "ECDHE_RSA")
            fail("A chain to an unknown root must not be trusted")
        } catch (expected: CertificateException) {
        }
    }
}
