package com.themoon.y1.podcasts

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch

class TimeoutDnsTest {

    private val address = InetAddress.getByAddress("feeds.npr.org", byteArrayOf(10, 0, 0, 1))

    @Test
    fun answer_isReturned() {
        val dns = TimeoutDns(Dns { listOf(address) }, timeoutMs = 1000)
        assertEquals(listOf(address), dns.lookup("feeds.npr.org"))
    }

    @Test
    fun noAnswer_failsAfterTheLimit() {
        // As getaddrinfo with no DNS answer: the lookup does not end
        val never = CountDownLatch(1)
        val dns = TimeoutDns(Dns { never.await(); emptyList() }, timeoutMs = 200)
        val start = System.nanoTime()
        try {
            dns.lookup("feeds.npr.org")
            fail("The lookup must fail")
        } catch (expected: UnknownHostException) {
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms in 150..2000)
    }

    @Test
    fun unknownHost_isPassedOn() {
        val dns = TimeoutDns(Dns { throw UnknownHostException("no such host") }, timeoutMs = 1000)
        try {
            dns.lookup("example.invalid")
            fail("The lookup must fail")
        } catch (e: UnknownHostException) {
            assertEquals("no such host", e.message)
        }
    }

    @Test
    fun otherError_isUnknownHost() {
        val dns = TimeoutDns(Dns { throw SecurityException("no permission") }, timeoutMs = 1000)
        try {
            dns.lookup("feeds.npr.org")
            fail("The lookup must fail")
        } catch (e: UnknownHostException) {
            assertTrue(e.cause is SecurityException)
        }
    }
}
