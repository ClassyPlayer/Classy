package com.themoon.y1.sync.webdav

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The config file was valid but could not be deleted: it was not imported, so the password does not stay on the card. */
class ConfigFileStays : IOException("Cannot delete the config file")

data class WebDavConfig(val url: String, val user: String, val password: String)

/** One-time import of server settings from a JSON file the user drops on storage; the file is deleted after a valid import. */
object WebDavConfigFile {
    const val FILE_NAME = "classy_webdav.json"

    fun importFrom(file: File): Result<WebDavConfig> = runCatching {
        val o = JSONObject(file.readText())
        val url = o.optString("url").trim()
        val user = o.optString("user")
        val password = o.optString("password")
        require(serverUri(url) != null) { "url must be http:// or https:// with a host" }
        require(user.isNotEmpty() && password.isNotEmpty()) { "user and password are required" }
        WebDavConfig(url, user, password)
    }.mapCatching { c ->
        // The password must not stay on the card: a file that cannot be deleted is not imported, and the user is told
        if (!file.delete() && file.exists()) throw ConfigFileStays()
        c
    }
}
