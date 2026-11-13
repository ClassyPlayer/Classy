package com.themoon.y1.podcasts

import android.content.Context
import android.util.Log
import com.themoon.y1.ClassyApplication
import okhttp3.OkHttpClient
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The HTTP client for podcast audio: the episode download, the stream and its cover. Android 4.4 HTTPS has no TLS 1.2,
 * which most podcast hosts need, so the client uses Conscrypt. It checks the server certificate and the host name
 * ([PodcastTrust]). OkHttp also follows the redirects of the tracking hosts, also from http to https.
 */
object PodcastHttp {

    private const val TAG = "PodcastHttp"

    val client: OkHttpClient by lazy { build(ClassyApplication.appContext) }

    private fun build(context: Context?): OkHttpClient {
        val roots = context?.let { ctx -> PodcastTrust.loadCertificates(PodcastTrust.ROOT_ASSETS.map { { ctx.assets.open(it) } }) }
        return try {
            val trust: X509TrustManager = PodcastTrust.trustManager(roots.orEmpty())
            val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
            sslContext.init(null, arrayOf<TrustManager>(trust), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trust)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt client not made, HTTPS uses the system TLS: $e")
            OkHttpClient()
        }
    }
}
