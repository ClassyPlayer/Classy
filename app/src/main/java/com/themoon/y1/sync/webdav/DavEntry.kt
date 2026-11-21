package com.themoon.y1.sync.webdav

/** One PROPFIND result. [href] is kept as the server sent it (percent-encoded); it is the ledger key. */
data class DavEntry(
    val href: String,
    val isDir: Boolean,
    val size: Long,
    val etag: String?,
    val lastModified: String?
)
