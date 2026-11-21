package com.themoon.y1.sync.webdav

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
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

/** Trusts exactly one leaf certificate, chosen by the user on first connect (trust on first use). */
class PinningTrustManager(private val pinnedSha256: String?) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        val fp = sha256Fingerprint(leaf.encoded)
        if (!fp.equals(pinnedSha256, ignoreCase = true)) throw CertNotTrusted(fp)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
