package com.themoon.y1.cast

import android.util.Log
import com.themoon.y1.managers.Id3
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * HTTPS cover for the Sonos app (it does not load the HTTP cover of the Y2, test 2026-10-29): the Cover Art
 * Archive image of the MusicBrainz release in the file tags (beets writes "MusicBrainz Album Id").
 */
object CastCover {
    private const val TAG = "CastCover"
    private val cache = HashMap<String, String?>()
    @Volatile private var offlineUntil = 0L

    /** Blocks (network, at most ~3 s once for each album): call it from the cast thread. */
    fun httpsCoverUrl(file: File): String? {
        val mbid = try { musicBrainzAlbumId(file) } catch (_: Exception) { null } ?: return null
        synchronized(cache) { if (cache.containsKey(mbid)) return cache[mbid] }
        if (System.currentTimeMillis() < offlineUntil) return null
        val url = try {
            resolve("https://coverartarchive.org/release/$mbid/front-500")
        } catch (e: Exception) {
            Log.w(TAG, "cover of $mbid: $e")
            offlineUntil = System.currentTimeMillis() + 5 * 60_000L
            return null
        }
        synchronized(cache) { cache[mbid] = url }
        return url
    }

    /** The final image URL after the redirects (the Sonos app may not follow them), or null for no cover (404). */
    private fun resolve(start: String): String? {
        var url = start
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                if (c is HttpsURLConnection) tls12?.let { c.sslSocketFactory = it }
                c.instanceFollowRedirects = false
                c.requestMethod = "HEAD"
                c.connectTimeout = 2500
                c.readTimeout = 2500
                val code = c.responseCode
                when {
                    code in 300..399 -> {
                        val next = c.getHeaderField("Location") ?: return null
                        url = URL(URL(url), next).toString()
                    }
                    code in 200..299 -> return if (url.startsWith("https://")) url else null
                    else -> return null
                }
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    /**
     * Android 4.4 HTTPS uses TLS 1.0, and the Cover Art Archive needs TLS 1.2: use Conscrypt (installed at app
     * start), as the other HTTPS clients of the app do. Only the image URL is read, so the certificate is not checked
     * (the 4.4 root certificates are old).
     */
    private val tls12: SSLSocketFactory? by lazy {
        try {
            SSLContext.getInstance("TLS", "Conscrypt")
                .apply { init(null, arrayOf<TrustManager>(CastV2Renderer.TrustAll), SecureRandom()) }.socketFactory
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt: $e")
            null
        }
    }

    /** The MusicBrainz album id of an MP3 ([Id3.musicBrainzAlbumId]). */
    private fun musicBrainzAlbumId(file: File): String? =
        if (file.name.endsWith(".mp3", ignoreCase = true)) Id3.musicBrainzAlbumId(file) else null
}
