package com.themoon.y1.podcasts

import android.util.Log
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The certificate check of the podcast client: the root certificates of Android, then the Mozilla roots in
 * `assets/certs`. The roots of Android 4.4 are from 2013: it has no ISRG Root X1/X2 (Let's Encrypt) and no USERTrust
 * ECC, the root of the Sectigo ECC certificates (megaphone.fm, the host of many podcasts, #102).
 */
object PodcastTrust {

    private const val TAG = "PodcastTrust"

    /**
     * The bundled roots: Mozilla's list of roots for TLS servers, as curl publishes it (https://curl.se/ca/cacert.pem,
     * MPL-2.0, the date is in the file header). Update it by downloading the file again and checking its `.sha256`.
     */
    val ROOT_ASSETS = listOf("certs/mozilla-roots.pem")

    /** One certificate. A block with no END stops at the next BEGIN, so it does not take the next certificate with it. */
    private val PEM_BLOCK =
        Regex("-----BEGIN CERTIFICATE-----(?:(?!-----BEGIN CERTIFICATE-----)[\\s\\S])*?-----END CERTIFICATE-----")

    /**
     * The certificates in [pems], each file holding one or more PEM blocks. A file or a certificate that does not load
     * is skipped, so the other roots still work.
     */
    fun loadCertificates(pems: List<() -> InputStream>): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return pems.flatMap { open ->
            val text = try {
                open().use { it.reader(Charsets.US_ASCII).readText() }
            } catch (e: Exception) {
                Log.w(TAG, "Root certificate file not loaded: $e")
                return@flatMap emptyList()
            }
            PEM_BLOCK.findAll(text).mapNotNull { block ->
                try {
                    factory.generateCertificate(block.value.byteInputStream(Charsets.US_ASCII)) as X509Certificate
                } catch (e: Exception) {
                    Log.w(TAG, "Root certificate not loaded: $e")
                    null
                }
            }.toList()
        }
    }

    /** The system roots, and also [extraRoots]. */
    fun trustManager(extraRoots: List<X509Certificate>): X509TrustManager {
        val system = defaultTrustManager(null)
        if (extraRoots.isEmpty()) return system
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            extraRoots.forEachIndexed { i, cert -> setCertificateEntry("bundled-root-$i", cert) }
        }
        return CompositeTrustManager(listOf(system, defaultTrustManager(store)))
    }

    /** The trust manager of [store]; null = the roots of the system. */
    private fun defaultTrustManager(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** A chain is trusted when one of [managers] trusts it. */
    class CompositeTrustManager(private val managers: List<X509TrustManager>) : X509TrustManager {

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            firstThatTrusts { it.checkServerTrusted(chain, authType) }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            firstThatTrusts { it.checkClientTrusted(chain, authType) }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            managers.flatMap { it.acceptedIssuers.asList() }.toTypedArray()

        private inline fun firstThatTrusts(check: (X509TrustManager) -> Unit) {
            var first: CertificateException? = null
            for (manager in managers) {
                try {
                    check(manager)
                    return
                } catch (e: CertificateException) {
                    if (first == null) first = e
                }
            }
            throw first ?: CertificateException("No trust manager")
        }
    }
}
