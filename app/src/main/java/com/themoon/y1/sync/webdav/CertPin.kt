package com.themoon.y1.sync.webdav

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The server certificate is not the pinned one (or nothing is pinned yet). [fingerprint] is the server's. */
class CertNotTrusted(val fingerprint: String) : CertificateException("Certificate not trusted: $fingerprint")

/** SHA-256 of a DER certificate as uppercase hex pairs joined by ':'. */
fun sha256Fingerprint(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

/** SSL errors wrap the trust manager's exception; find it in the cause chain. */
fun findCertNotTrusted(e: Throwable): CertNotTrusted? {
    var t: Throwable? = e
    while (t != null) {
        if (t is CertNotTrusted) return t
        if (t.cause === t) return null
        t = t.cause
    }
    return null
}

/**
 * Trusts the pinned leaf certificate (trust on first use, for a self-signed server), or a chain that [system] (the
 * certificate authorities of Android) trusts: a server with a public certificate keeps working when it is renewed.
 * Anything else throws [CertNotTrusted] with the leaf's fingerprint, for the user to compare and trust.
 */
class PinningTrustManager(private val pinnedSha256: String?, private val system: X509TrustManager? = null) : X509TrustManager {

    /** True when [leaf] is the pinned certificate. Then the host name does not matter (a NAS by IP address). */
    fun isPinned(leaf: X509Certificate): Boolean = sha256Fingerprint(leaf.encoded).equals(pinnedSha256, ignoreCase = true)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        if (isPinned(leaf)) return
        if (system != null) {
            try {
                system.checkServerTrusted(chain, authType)
                return // the host name is checked after this, by the client's HostnameVerifier
            } catch (_: CertificateException) {
                // not signed by an authority that Android knows: the user can pin it
            }
        }
        throw CertNotTrusted(sha256Fingerprint(leaf.encoded))
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** The default trust manager of the platform (Android's certificate authorities), or null if there is none. */
        fun systemTrustManager(): X509TrustManager? = try {
            val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            f.init(null as KeyStore?)
            f.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
