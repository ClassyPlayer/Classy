package com.themoon.y1.podcasts

import android.net.Uri
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.source.UnrecognizedInputFormatException
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLProtocolException

class PodcastStreamErrorTest {

    private val spec = DataSpec(Uri.parse("https://example.com/episode.mp3"))

    /** As ExoPlayer gives it: the network error in an HttpDataSourceException in an ExoPlaybackException. */
    private fun sourceError(cause: IOException): ExoPlaybackException =
        ExoPlaybackException.createForSource(
            HttpDataSource.HttpDataSourceException("Unable to connect", cause, spec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        )

    @Test
    fun tlsHandshake_isSecureConnection() {
        val e = sourceError(SSLProtocolException("SSL handshake aborted: unsupported protocol"))
        assertEquals("Secure connection failed", PodcastStreamError.reason(e))
    }

    @Test
    fun unknownHost_isNoConnection() {
        assertEquals("No internet connection", PodcastStreamError.reason(sourceError(UnknownHostException("feeds.npr.org"))))
    }

    @Test
    fun timeout_isNoAnswer() {
        assertEquals("Server does not answer", PodcastStreamError.reason(sourceError(SocketTimeoutException())))
    }

    @Test
    fun httpStatus_showsTheCode() {
        val e = ExoPlaybackException.createForSource(
            HttpDataSource.InvalidResponseCodeException(404, "Not Found", emptyMap(), spec, ByteArray(0))
        )
        assertEquals("Server error 404", PodcastStreamError.reason(e))
    }

    @Test
    fun notAudio_isUnsupportedFormat() {
        val e = ExoPlaybackException.createForSource(UnrecognizedInputFormatException("No extractor", Uri.EMPTY))
        assertEquals("Unsupported audio format", PodcastStreamError.reason(e))
    }

    @Test
    fun otherError_isGeneric() {
        assertEquals("Cannot play this stream", PodcastStreamError.reason(ExoPlaybackException.createForSource(IOException("x"))))
    }
}
