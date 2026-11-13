package com.themoon.y1.podcasts

import android.content.Context
import android.util.Log
import com.themoon.y1.ClassyApplication
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The HTTP client for podcast audio: the episode download, the stream and its cover. Android 4.4 HTTPS has no TLS 1.2,
 * which most podcast hosts need, so the client uses Conscrypt. It checks the server certificate and the host name
 * ([PodcastTrust]). OkHttp also follows the redirects of the tracking hosts, also from http to https. A DNS lookup
 * that does not answer fails after [TimeoutDns.DEFAULT_TIMEOUT_MS].
 */
object PodcastHttp {

    private const val TAG = "PodcastHttp"

    val client: OkHttpClient by lazy { build(ClassyApplication.appContext) }

    /** The time limit of a call that loads one small file, for example the cover. */
    const val SMALL_FILE_TIMEOUT_S = 30L

    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 20L

    /**
     * Limits for each step, with no limit for the whole call: the download and the stream read one response body for
     * as long as the episode (OkHttpDataSource keeps one call for the whole stream), so a call timeout would cut them.
     * A connection with no data for [READ_TIMEOUT_S] fails, and ExoPlayer then tries again or shows the error.
     */
    private fun OkHttpClient.Builder.timeouts(): OkHttpClient.Builder =
        dns(TimeoutDns())
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)

    private fun build(context: Context?): OkHttpClient {
        val roots = context?.let { ctx -> PodcastTrust.loadCertificates(PodcastTrust.ROOT_ASSETS.map { { ctx.assets.open(it) } }) }
        return try {
            val trust: X509TrustManager = PodcastTrust.trustManager(roots.orEmpty())
            val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
            sslContext.init(null, arrayOf<TrustManager>(trust), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trust)
                .timeouts()
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt client not made, HTTPS uses the system TLS: $e")
            OkHttpClient.Builder().timeouts().build()
        }
    }
}
