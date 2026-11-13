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
 * The certificate check of the podcast client: the root certificates of Android, then the Let's Encrypt roots in
 * `assets/certs`. Android 4.4 does not have ISRG Root X1 (2015) and X2 (2020), and many podcast hosts use Let's Encrypt.
 */
object PodcastTrust {

    private const val TAG = "PodcastTrust"

    /** The bundled roots, from https://letsencrypt.org/certificates/ (SHA-256 fingerprints in the PR, #102). */
    val ROOT_ASSETS = listOf("certs/isrg-root-x1.pem", "certs/isrg-root-x2.pem")

    /** The certificates in [pems]. A file that does not load is skipped, so the other roots still work. */
    fun loadCertificates(pems: List<() -> InputStream>): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return pems.mapNotNull { open ->
            try {
                open().use { factory.generateCertificate(it) as X509Certificate }
            } catch (e: Exception) {
                Log.w(TAG, "Root certificate not loaded: $e")
                null
            }
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
