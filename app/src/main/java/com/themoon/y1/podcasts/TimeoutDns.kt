package com.themoon.y1.podcasts

import okhttp3.Dns
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A DNS lookup with a time limit. The system lookup (getaddrinfo) has none that OkHttp can stop: with no DNS answer
 * (seen on the Y2 with a Wi-Fi DNS outage) the connection waited with no end, and a call timeout cannot stop it.
 * The lookup runs on its own thread; after [timeoutMs] the connection fails with [UnknownHostException], and the
 * thread ends when the system lookup ends. With [MAX_LOOKUPS] threads in use, a new lookup fails at once.
 */
class TimeoutDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val executor: ExecutorService = SHARED_EXECUTOR,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val lookup = try {
            executor.submit<List<InetAddress>> { delegate.lookup(hostname) }
        } catch (e: RejectedExecutionException) {
            // All lookup threads still wait for the system (DNS outage): fail at once, do not add threads
            throw UnknownHostException("No free DNS lookup thread for $hostname")
        }
        try {
            return lookup.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            lookup.cancel(true)
            throw UnknownHostException("No DNS answer for $hostname in $timeoutMs ms")
        } catch (e: InterruptedException) {
            lookup.cancel(true)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("DNS lookup for $hostname stopped")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is UnknownHostException) throw cause
            throw UnknownHostException("DNS lookup for $hostname failed: $cause").apply { initCause(cause) }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Normal use has up to 4 lookups at a time: 2 downloads, the stream and its cover. */
        const val MAX_LOOKUPS = 8

        /** At most [maxThreads] threads and no queue: a thread that the system lookup blocks cannot add more. */
        fun boundedExecutor(maxThreads: Int): ExecutorService =
            ThreadPoolExecutor(0, maxThreads, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
                Thread(r, "podcast-dns").apply { isDaemon = true }
            }

        private val SHARED_EXECUTOR = boundedExecutor(MAX_LOOKUPS)
    }
}
