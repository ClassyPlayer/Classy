package com.themoon.y1.sync.webdav

import org.json.JSONObject
import java.io.File

data class WebDavConfig(val url: String, val user: String, val password: String)

/** One-time import of server settings from a JSON file the user drops on storage; the file is deleted after a valid import. */
object WebDavConfigFile {
    const val FILE_NAME = "classy_webdav.json"

    fun importFrom(file: File): Result<WebDavConfig> = runCatching {
        val o = JSONObject(file.readText())
        val url = o.optString("url").trim()
        val user = o.optString("user")
        val password = o.optString("password")
        require(url.startsWith("https://") || url.startsWith("http://")) { "url must start with http:// or https://" }
        require(user.isNotEmpty() && password.isNotEmpty()) { "user and password are required" }
        WebDavConfig(url, user, password)
    }.onSuccess { file.delete() }
}
