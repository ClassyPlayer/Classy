package com.themoon.y1.sync.webdav

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import okhttp3.internal.tls.OkHostnameVerifier
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
 * Trusts the pinned leaf certificate (trust on first use, for a self-signed server; any host name, so a NAS by IP
 * address works), or a chain that [system] (the certificate authorities of Android) trusts and whose leaf names [host]:
 * a server with a public certificate keeps working when it is renewed. Anything else, also a public certificate for
 * another name, throws [CertNotTrusted] with the leaf's fingerprint, for the user to compare and trust.
 * The host check is here, not in a HostnameVerifier, so that a name that does not match can be pinned too.
 */
class PinningTrustManager(
    private val pinnedSha256: String?,
    private val system: X509TrustManager? = null,
    private val host: String? = null
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        val fp = sha256Fingerprint(leaf.encoded)
        if (fp.equals(pinnedSha256, ignoreCase = true)) return
        if (system != null && host != null && OkHostnameVerifier.INSTANCE.verify(host, leaf)) {
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (_: CertificateException) {
                // not signed by an authority that Android knows: the user can pin it
            }
        }
        throw CertNotTrusted(fp)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /**
         * The default trust manager of the platform (Android's certificate authorities), or null if there is none.
         * Made once: it reads all the system certificates, which is slow on the Y2.
         */
        val systemTrustManager: X509TrustManager? by lazy {
            try {
                val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                f.init(null as KeyStore?)
                f.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }
}
