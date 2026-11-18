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

    private fun bundledRoots(): List<X509Certificate> =
        PodcastTrust.loadCertificates(PodcastTrust.ROOT_ASSETS.map { asset(it) })

    @Test
    fun bundledRoots_haveTheRootsThatAndroid44Lacks() {
        val fingerprints = bundledRoots().map { sha256(it) }
        // The fingerprints that https://letsencrypt.org/certificates/ and crt.sh give
        val needed = mapOf(
            "ISRG Root X1" to "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6",
            "ISRG Root X2" to "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470",
            // The root of dcs-spotify.megaphone.fm (Sectigo ECC), the last redirect of megaphone podcasts
            "USERTrust ECC" to "4FF460D54B9C86DABFBCFC5712E0400D2BED3FBC4D4FBDAA86E06ADCD2A9AD7A",
        )
        needed.forEach { (name, sha) -> assertTrue("$name missing", sha in fingerprints) }
        assertTrue("Mozilla bundle not complete: ${fingerprints.size}", fingerprints.size > 100)
        // Every block of the file loads: a damaged update fails here, not on the device with one root less
        val blocks = PodcastTrust.ROOT_ASSETS.sumOf { path ->
            File("src/main/assets", path).readText().split("-----BEGIN CERTIFICATE-----").size - 1
        }
        assertEquals("Roots that did not load", blocks, fingerprints.size)
        assertEquals("Duplicate roots", fingerprints.size, fingerprints.toSet().size)
    }

    @Test
    fun bundledRoots_areTrustAnchors() {
        val roots = bundledRoots()
        val issuers = PodcastTrust.trustManager(roots).acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue(issuers.any { it.contains("CN=USERTrust ECC Certification Authority") })
        assertTrue(issuers.any { it.contains("CN=ISRG Root X2") })
    }

    @Test
    fun brokenFile_isSkipped() {
        val roots = PodcastTrust.loadCertificates(
            listOf({ ByteArrayInputStream("not a certificate".toByteArray()) }, { resource("test-root.pem").pemStream() })
        )
        assertEquals(1, roots.size)
    }

    @Test
    fun brokenBlock_skipsOnlyThatCertificate() {
        val broken = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
        val text = "# comment\n" + broken + resource("test-root.pem").pem() + "\n" + resource("test-leaf.pem").pem()
        val roots = PodcastTrust.loadCertificates(listOf({ ByteArrayInputStream(text.toByteArray()) }))
        assertEquals(listOf(resource("test-root.pem"), resource("test-leaf.pem")), roots)
    }

    @Test
    fun blockWithNoEnd_doesNotTakeTheNextCertificate() {
        val unterminated = "-----BEGIN CERTIFICATE-----\nAAAA\n"
        val text = unterminated + resource("test-root.pem").pem() + unterminated + resource("test-leaf.pem").pem()
        val roots = PodcastTrust.loadCertificates(listOf({ ByteArrayInputStream(text.toByteArray()) }))
        assertEquals(listOf(resource("test-root.pem"), resource("test-leaf.pem")), roots)
    }

    private fun X509Certificate.pem(): String =
        "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded) +
            "\n-----END CERTIFICATE-----\n"

    private fun X509Certificate.pemStream(): InputStream = ByteArrayInputStream(pem().toByteArray())

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
