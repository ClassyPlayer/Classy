package com.themoon.y1.podcasts

import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastHttpTest {

    @Test
    fun client_followsRedirects_alsoToHttps() {
        // Podcast hosts send the audio through tracking hosts, often from http to https
        assertTrue(PodcastHttp.client.followRedirects())
        assertTrue(PodcastHttp.client.followSslRedirects())
    }
}
