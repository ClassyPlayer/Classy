package com.themoon.y1.podcasts

import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.upstream.HttpDataSource
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The reason that shows on Now Playing when a podcast stream does not play. */
object PodcastStreamError {

    fun reason(error: Throwable): String {
        var e: Throwable? = error
        // The cause chain: ExoPlaybackException > HttpDataSourceException > the network error
        var depth = 0
        while (e != null && depth < 8) {
            when (e) {
                is HttpDataSource.InvalidResponseCodeException -> return "Server error ${e.responseCode}"
                is UnknownHostException -> return "No internet connection"
                is SSLException -> return "Secure connection failed"
                is SocketTimeoutException -> return "Server does not answer"
                is ParserException -> return "Unsupported audio format"
            }
            e = e.cause
            depth++
        }
        return "Cannot play this stream"
    }
}
