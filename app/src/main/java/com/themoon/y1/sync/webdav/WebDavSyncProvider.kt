package com.themoon.y1.sync.webdav

import android.content.Context
import android.content.SharedPreferences
import com.themoon.y1.StoragePaths
import com.themoon.y1.sync.MediaSyncProvider
import java.io.File

/** WebDAV server settings and folder selection, kept in private prefs. */
class WebDavSyncProvider private constructor(context: Context) : MediaSyncProvider {

    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    /** The error of the last sync ([errorText], not translated yet), or empty after a sync that worked. */
    var lastError: String
        get() = prefs.getString("last_error", "") ?: ""
        set(v) { prefs.edit().putString("last_error", v).apply() }

    /** When Auto Sync last started a sync, also one that failed: a down server is not tried at every broadcast. */
    var lastAutoAttempt: Long
        get() = prefs.getLong("last_auto_attempt", 0L)
        set(v) { prefs.edit().putLong("last_auto_attempt", v).apply() }

    /** Throws an IOException for a URL that [serverUri] does not take: call it where an error can show. */
    fun client(url: String = this.url, user: String = this.user, password: String = this.password, pin: String? = this.pin): WebDavClient {
        val https = serverUri(url)?.scheme.equals("https", ignoreCase = true) // also "HTTPS://" from the config file
        return WebDavClient(url, user, password,
            if (https) PinningTrustManager(pin, PinningTrustManager.systemTrustManager, serverHost(url)) else null)
    }

    /** The href of the top folder of the Server URL, or null for a URL that [serverUri] does not take. No client, no I/O. */
    fun rootHref(): String? = serverUri(url)?.let { rootHrefOf(it) }

    /**
     * Sets the Server URL. The same folder of the same server (scheme, host, port, and the path with any spelling, for
     * example a slash at the end) keeps the pin, the ticked folders and the synced files. Anything else starts again: no
     * pin, no ticked folders, and the synced files are forgotten (they stay on the player; see [WebDavSync.forgetAll]).
     * Another top folder must start again too: the local paths are below it, and the ticked folders may be outside it.
     * False while a sync runs, and [LedgerNotSaved] when the forgotten files cannot be saved: then nothing changes.
     */
    fun changeUrl(newUrl: String): Boolean {
        val sync = WebDavSync.getInstance(app)
        synchronized(sync) { // the lock of WebDavSync.start: no sync starts while the URL changes
            val v = newUrl.trim()
            if (v == url) return true
            if (sync.isRunning) return false
            val old = serverUri(url)
            val new = serverUri(v)
            if (old != null && new != null && sameServer(old, new) &&
                decodeHref(rootHrefOf(old)) == decodeHref(rootHrefOf(new))) {
                url = v
                return true
            }
            return sync.forgetAll {
                pin = null
                ticked = emptySet()
                lastSync = 0L
                lastError = ""
                url = v
            }
        }
    }

    /**
     * Log Out / Reset: forgets all the settings and the synced files (they stay on the player). False while a sync runs;
     * [LedgerNotSaved] when the ledger cannot be written. Then nothing changes.
     */
    fun reset(): Boolean = WebDavSync.getInstance(app).forgetAll { clearConfiguration() }

    /**
     * Imports [WebDavConfigFile.FILE_NAME] from primary storage if present. Returns an error text, or null.
     * Not while a sync runs: the file stays, and the next visit of the screen imports it.
     */
    fun importConfigIfPresent(): String? {
        val f = File(StoragePaths.PRIMARY_PATH, WebDavConfigFile.FILE_NAME)
        if (!f.exists()) return null
        val sync = WebDavSync.getInstance(app)
        synchronized(sync) { // no sync starts between the check and the import, so changeUrl cannot refuse
            if (sync.isRunning) return null
            return WebDavConfigFile.importFrom(f).fold(
                onSuccess = { c ->
                    // The file is deleted already: if the ledger cannot be saved, the user must put it there again
                    try { changeUrl(c.url) } catch (e: LedgerNotSaved) { return "${WebDavConfigFile.FILE_NAME}: ${e.message}" }
                    user = c.user
                    password = c.password
                    null
                },
                onFailure = { "${WebDavConfigFile.FILE_NAME}: " + if (it is ConfigFileStays) "cannot delete it, delete it by hand" else "invalid" }
            )
        }
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
            } catch (e: Throwable) {
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
