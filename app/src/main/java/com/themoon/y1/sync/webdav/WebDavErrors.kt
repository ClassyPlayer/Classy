package com.themoon.y1.sync.webdav

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CancellationException

/** The ledger file could not be written (for example a full or read-only storage). */
class LedgerNotSaved : IOException("Cannot save the sync record")

class NotEnoughSpace(val need: Long, val free: Long) : IOException("Need $need bytes, free $free")

/** The one-line message shown to the user for a sync or connection error; [localize] translates the fixed texts. */
fun errorText(e: Throwable, localize: (String) -> String = { it }): String = when {
    findCertNotTrusted(e) != null -> localize("Certificate not trusted")
    e is CancellationException -> localize("Sync cancelled")
    e is UnknownHostException || e is ConnectException || e is SocketTimeoutException || e is NoRouteToHostException -> localize("Server unreachable")
    e is DavHttpException && (e.code == 401 || e.code == 403) -> localize("Check username/password")
    e is DavHttpException -> "HTTP ${e.code}"
    e is NotEnoughSpace -> localize("Not enough space") + ": " + size(e.need) + " / " + size(e.free)
    else -> localize(e.message ?: e.javaClass.simpleName)
}

private fun size(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
}
