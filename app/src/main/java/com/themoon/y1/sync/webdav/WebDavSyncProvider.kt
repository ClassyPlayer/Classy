package com.themoon.y1.sync.webdav

import android.content.Context
import android.content.SharedPreferences
import com.themoon.y1.StoragePaths
import com.themoon.y1.sync.MediaSyncProvider
import java.io.File

/** WebDAV server settings and folder selection, kept in private prefs. */
class WebDavSyncProvider private constructor(context: Context) : MediaSyncProvider {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) { prefs.edit().putString("url", v.trim()).apply() }

    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(v) { prefs.edit().putString("user", v).apply() }

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(v) { prefs.edit().putString("password", v).apply() }

    /** SHA-256 fingerprint of the trusted server certificate (HTTPS only). */
    var pin: String?
        get() = prefs.getString("pin", null)
        set(v) { prefs.edit().putString("pin", v).apply() }

    /** Sync into the microSD card (/storage/sdcard1/Music) instead of internal storage. */
    var onSdCard: Boolean
        get() = prefs.getBoolean("dest_sd", false)
        set(v) { prefs.edit().putBoolean("dest_sd", v).apply() }

    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", false)
        set(v) { prefs.edit().putBoolean("auto_sync", v).apply() }

    /** Files downloaded at the same time, 1 to [MAX_PARALLEL]; each uses its own connection. */
    var parallel: Int
        get() = prefs.getInt("parallel", 1).coerceIn(1, MAX_PARALLEL)
        set(v) { prefs.edit().putInt("parallel", v.coerceIn(1, MAX_PARALLEL)).apply() }

    /** Hrefs of the folders to sync. */
    var ticked: Set<String>
        get() = HashSet(prefs.getStringSet("ticked", emptySet()) ?: emptySet())
        set(v) { prefs.edit().putStringSet("ticked", HashSet(v)).apply() }

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0L)
        set(v) { prefs.edit().putLong("last_sync", v).apply() }

    /** When Auto Sync last started a sync, also one that failed: a down server is not tried at every broadcast. */
    var lastAutoAttempt: Long
        get() = prefs.getLong("last_auto_attempt", 0L)
        set(v) { prefs.edit().putLong("last_auto_attempt", v).apply() }

    /** Throws an IOException for a URL that [serverUri] does not take: call it where an error can show. */
    fun client(url: String = this.url, user: String = this.user, password: String = this.password, pin: String? = this.pin): WebDavClient =
        WebDavClient(url, user, password, if (url.startsWith("https://")) PinningTrustManager(pin) else null)

    /** Imports [WebDavConfigFile.FILE_NAME] from primary storage if present. Returns an error text, or null. */
    fun importConfigIfPresent(): String? {
        val f = File(StoragePaths.PRIMARY_PATH, WebDavConfigFile.FILE_NAME)
        if (!f.exists()) return null
        return WebDavConfigFile.importFrom(f).fold(
            onSuccess = { c ->
                if (c.url != url) pin = null
                url = c.url
                user = c.user
                password = c.password
                null
            },
            onFailure = { "${WebDavConfigFile.FILE_NAME}: " + if (it is ConfigFileStays) "cannot delete it, delete it by hand" else "invalid" }
        )
    }

    override fun getId(): String = "webdav"

    override fun getDisplayName(): String = "WebDAV"

    override fun isConfigured(): Boolean = url.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty()

    override fun getStatusSummary(): String = if (isConfigured()) "@$user" else "Not Configured"

    override fun testConnection(callback: MediaSyncProvider.ConnectionCallback) {
        Thread {
            try {
                val c = client()
                c.list(c.rootHref)
                callback.onSuccess(url, "WebDAV")
            } catch (e: Exception) {
                callback.onError(errorText(e))
            }
        }.start()
    }

    /** Top-level folders of the server URL. */
    override fun fetchLibraries(token: MediaSyncProvider.CancellationToken?): List<MediaSyncProvider.LibraryInfo> {
        val c = client()
        return c.list(c.rootHref).filter { it.isDir }.map {
            MediaSyncProvider.LibraryInfo(it.href, folderName(it.href), "music")
        }
    }

    override fun clearConfiguration() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "webdav_sync"
        const val MAX_PARALLEL = 4

        @Volatile
        private var instance: WebDavSyncProvider? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): WebDavSyncProvider =
            instance ?: WebDavSyncProvider(context).also { instance = it }

        fun folderName(href: String): String = decodeHref(href).trimEnd('/').substringAfterLast('/')
    }
}
