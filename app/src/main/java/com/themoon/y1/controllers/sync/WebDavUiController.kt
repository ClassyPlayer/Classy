package com.themoon.y1.controllers.sync

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.controllers.settings.confirmScreen
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.navigation.KeyboardTarget
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.WebDavLevel
import com.themoon.y1.sync.webdav.DavEntry
import com.themoon.y1.sync.webdav.LedgerNotSaved
import com.themoon.y1.sync.webdav.WebDavSync
import com.themoon.y1.sync.webdav.WebDavSyncProvider
import com.themoon.y1.sync.webdav.decodeHref
import com.themoon.y1.sync.webdav.errorText
import com.themoon.y1.sync.webdav.isUnder
import com.themoon.y1.sync.webdav.findCertNotTrusted
import com.themoon.y1.views.Notice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * WebDAV sync: server settings, the NAS folders to sync, Sync Now, Auto Sync and the files that were deleted
 * on the NAS. Gone files are only listed; the user deletes them here. Same list model as [JellyfinUiController].
 */
class WebDavUiController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val onOpenKeyboard: (target: KeyboardTarget) -> Unit,
    private val onRequestWifi: () -> Unit,
    private val isNetworkConnected: () -> Boolean,
    private val onSyncFinished: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val screen = ListScreen(rootView.findViewById(R.id.container_settings_items), null, clickFeedback)
    private val provider: WebDavSyncProvider get() = WebDavSyncProvider.getInstance(activity)
    private val sync: WebDavSync get() = WebDavSync.getInstance(activity)

    /** Server fingerprint waiting for the user's Trust (the certificate is not pinned, or it changed). */
    private var pendingFingerprint: String? = null

    /** The Server URL that [pendingFingerprint] was read from: it is shown and trusted only while that is still the URL. */
    private var pendingFor = ""

    /** Sub-folders per folder href, loaded on demand; null value while loading. */
    private val folders = HashMap<String, List<DavEntry>?>()

    /** The Server URL of [folders]: a listing is of the server that gave it, so another URL starts with an empty cache. */
    private var foldersFor = ""

    private fun dropListingsOfOtherServer() {
        if (foldersFor != provider.url) {
            folders.clear()
            foldersFor = provider.url
        }
    }

    override fun onEnter(state: ScreenState) {
        val level = (state as? ScreenState.WebDav)?.level ?: WebDavLevel.Root
        if (level == WebDavLevel.Root) {
            provider.importConfigIfPresent()?.let { Notice.error(activity, it) }
        }
        dropListingsOfOtherServer()
        sync.onRunEnded = { refresh() } // an Auto Sync that ends now changes Last Synced and the counts
        screen.show(spec(level))
        // Show the last listing at once (Back keeps its row), then list again so NAS changes show up
        if (level is WebDavLevel.Folders && folders[level.href] != null) loadFolder(level.href)
    }

    override fun onExit() {
        sync.onRunEnded = null
        screen.clear()
    }

    override fun onThemeChanged() {
        NavigationManager.getInstance().currentState?.let { onEnter(it) }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta, event.eventTime)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = screen.key(event)

    override fun title(): String? = screen.title

    private val shownLevel: WebDavLevel?
        get() = (NavigationManager.getInstance().currentState as? ScreenState.WebDav)?.level

    private fun open(level: WebDavLevel) {
        (activity as? com.themoon.y1.MainActivity)?.changeScreen(ScreenState.WebDav(level))
    }

    private fun refresh() {
        shownLevel?.let { screen.update(spec(it)) }
    }

    private fun spec(level: WebDavLevel): ListSpec = when (level) {
        WebDavLevel.Root -> rootSpec()
        is WebDavLevel.Folders -> foldersSpec(level.href)
        WebDavLevel.Orphans -> orphansSpec()
    }

    // ---- Rows ----

    /** A section header; the list draws the bars around [title]. */
    private fun header(title: String) = RowSpec(title, RowLook.HEADER, key = "\u0000header $title")

    private fun info(text: String, key: String = "\u0000info $text") = RowSpec(text, RowLook.INFO, key = key)

    private fun value(label: String, value: String, onClick: (() -> Unit)? = null) =
        RowSpec(label, RowLook.VALUE, value = value, onClick = onClick)

    private fun action(label: String, run: () -> Unit) = RowSpec(label, RowLook.MENU, onClick = run)

    private val notSet get() = localize("Not Configured")

    // ---- Settings (root) ----

    private fun rootSpec(): ListSpec {
        val p = provider
        val rows = ArrayList<RowSpec>()
        rows += header(localize("Server"))
        rows += value(localize("Server URL"), p.url.ifEmpty { notSet }) {
            // Another server forgets the synced files: not while a sync uses them
            if (sync.isRunning) Notice.show(activity, localize("Syncing...")) else onOpenKeyboard(KeyboardTarget.WebDavUrl)
        }
        rows += value(localize("Username"), p.user.ifEmpty { notSet }) { onOpenKeyboard(KeyboardTarget.WebDavUser) }
        rows += value(localize("Password"), if (p.password.isEmpty()) notSet else "••••••••") {
            onOpenKeyboard(KeyboardTarget.WebDavPassword(p.user))
        }
        rows += action(localize("Test Connection")) { testConnection() }
        pendingFingerprint?.takeIf { pendingFor == p.url }?.let { fp ->
            rows += header(localize("Certificate"))
            // 32 hex pairs: four lines of eight, so it fits the screen
            fp.split(":").chunked(8).forEachIndexed { i, part -> rows += info(part.joinToString(":"), key = "\u0000fp $i") }
            rows += action(localize("Trust Certificate")) {
                p.pin = fp
                pendingFingerprint = null
                Notice.show(activity, localize("Certificate trusted"))
                refresh()
            }
        }

        rows += header(localize("Sync"))
        rows += value(localize("Folders"), "${p.ticked.size} 〉") {
            if (!p.isConfigured()) Notice.show(activity, notSet)
            else p.rootHref()?.let { open(WebDavLevel.Folders(it)) } ?: Notice.error(activity, localize("Not a valid URL"))
        }
        rows += value(localize("Auto Sync"), onOff(p.autoSync, localize)) {
            p.autoSync = !p.autoSync
            refresh()
        }
        rows += value(localize("Parallel Downloads"), "${p.parallel}") {
            p.parallel = p.parallel % WebDavSyncProvider.MAX_PARALLEL + 1
            refresh()
        }
        rows += value(localize("Save To"), localize(if (p.onSdCard) "SD Card" else "Internal")) {
            when {
                sync.isRunning -> Notice.show(activity, localize("Syncing..."))
                !p.onSdCard && !StoragePaths.hasSecondaryStorage() -> Notice.error(activity, localize("SD card not found"))
                else -> { p.onSdCard = !p.onSdCard; refresh() }
            }
        }
        val misplaced = sync.misplaced().size
        if (misplaced > 0) {
            rows += value(localize("Move Synced Files"), "$misplaced 〉") {
                ActionScreens.open(confirmScreen(
                    localize("Move Synced Files"),
                    localize("Move") + " $misplaced → " + localize(if (p.onSdCard) "SD Card" else "Internal") + "?",
                    localize("Move"),
                    selectAction = false
                ) { launchSync(move = true) })
            }
        }
        rows += action(localize("Sync Now")) { launchSync() }
        rows += value(localize("Deleted on NAS"), "${sync.orphans().size} 〉") { open(WebDavLevel.Orphans) }
        val last = p.lastSync
        rows += value(localize("Last Synced"),
            if (last > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(last)) else localize("Never"))
        // A failed Auto Sync has no screen of its own: its error shows here until a sync works
        p.lastError.takeIf { it.isNotEmpty() && !sync.isRunning }?.let {
            rows += info(localize("Last sync failed") + ": " + localize(it), key = "\u0000lastError")
        }
        rows += value(localize("Log Out / Reset"), "〉 ") {
            ActionScreens.open(ActionScreens.Spec(
                localize("WebDAV Sync"),
                listOfNotNull(p.user.takeIf { it.isNotEmpty() }?.let { "@$it" }, p.url.takeIf { it.isNotEmpty() }),
                listOf(ActionScreens.Action(localize("Log Out / Reset")) {
                    val done = try { p.reset() } catch (e: LedgerNotSaved) { return@Action Notice.error(activity, errorText(e, localize)) }
                    if (!done) return@Action Notice.show(activity, localize("Syncing..."))
                    pendingFingerprint = null
                    folders.clear()
                    refresh()
                }),
                selectAction = false
            ))
        }
        return ListSpec(localize("WebDAV Sync"), rows)
    }

    /**
     * The certificate to trust, or the one-line error of [e]. [asked]: the user started this request (Test Connection,
     * a sync), so a certificate notice shows again; a folder listing in the background tells it once.
     */
    private fun showError(e: Throwable, url: String = provider.url, asked: Boolean = false) {
        if (!handleCert(e, url, asked)) Notice.error(activity, errorText(e, localize))
    }

    /** Remembers a certificate to trust and shows it on the settings; true when [e] was that. */
    private fun handleCert(e: Throwable, url: String, asked: Boolean): Boolean {
        val cert = findCertNotTrusted(e) ?: return false
        // The fingerprint is of the server that [url] named when the request began: a URL that changed meanwhile is another server
        if (url != provider.url) return true
        // Told already (for example the parent folder that lists again after Back): one notice is enough
        if (!asked && pendingFingerprint == cert.fingerprint && pendingFor == url) return true
        pendingFingerprint = cert.fingerprint
        pendingFor = url
        Notice.error(activity, localize("Check the certificate fingerprint, then Trust Certificate."))
        refresh()
        return true
    }

    private fun testConnection() {
        if (!isNetworkConnected()) return onRequestWifi()
        val key = ActionScreens.open(ActionScreens.Spec(localize("Test Connection"), listOf(localize("Connecting..."))))
        val url = provider.url
        val p = provider
        val (user, password, pin) = Triple(p.user, p.password, p.pin) // with the URL: all are of one configuration
        Thread {
            val error = try {
                p.client(url, user, password, pin).let { it.list(it.rootHref) } // a URL that cannot be read is an error here, not a crash
                null
            } catch (e: Throwable) { // also an OutOfMemoryError of a very large answer: a message, not a crash
                e
            }
            activity.runOnUiThread {
                ActionScreens.close(key)
                if (provider.url != url) return@runOnUiThread // the URL changed meanwhile: this answer is of another server
                when {
                    error == null -> Notice.show(activity, localize("Connected"))
                    else -> showError(error, url, asked = true)
                }
            }
        }.start()
    }

    // ---- NAS folders ----

    private fun foldersSpec(href: String): ListSpec {
        dropListingsOfOtherServer()
        val title = WebDavSyncProvider.folderName(href).ifEmpty { localize("Folders") }
        if (!folders.containsKey(href)) loadFolder(href)
        val subs = folders[href] ?: return ListSpec(title, listOf(info(localize("Loading..."))))
        val ticked = provider.ticked
        val rows = ArrayList<RowSpec>()
        rows += info(localize("Center: open · Hold: select"), key = "\u0000hint")
        // The folder itself, also one with files and no sub-folders (the top folder of the URL, for example). Center
        // ticks it; under a ticked folder it shows ticked and cannot be unticked alone, as a sub-folder row
        val selfUnder = ticked.any { it.trimEnd('/') != href.trimEnd('/') && isUnder(href, it) }
        rows += RowSpec(
            localize("This Folder"),
            RowLook.CHOICE,
            checked = selfUnder || href in ticked,
            key = "\u0000self",
            onClick = if (selfUnder) null else ({ toggle(href) })
        )
        for (d in subs) {
            if (!d.isDir) {
                rows += info(WebDavSyncProvider.folderName(d.href), key = d.href)
                continue
            }
            // A ticked folder is not under itself: its own Hold must untick it
            val underTicked = ticked.any { it.trimEnd('/') != d.href.trimEnd('/') && isUnder(d.href, it) }
            rows += RowSpec(
                WebDavSyncProvider.folderName(d.href),
                RowLook.CHOICE,
                checked = underTicked || d.href in ticked,
                key = d.href,
                // Same as Jellyfin Sync: Center opens, Hold ticks
                onClick = { open(WebDavLevel.Folders(d.href)) },
                // Under a ticked folder it is already synced: open it, but tick only the parent
                onHold = if (underTicked) null else ({ toggle(d.href) })
            )
        }
        return ListSpec(title, rows, empty = localize("No folders"), letters = true)
    }

    private fun loadFolder(href: String) {
        dropListingsOfOtherServer()
        val url = provider.url
        val p = provider
        val (user, password, pin) = Triple(p.user, p.password, p.pin)
        val shown = folders[href] != null
        if (!shown) folders[href] = null
        Thread {
            val result = try {
                Result.success(p.client(url, user, password, pin).list(href).sortedWith(compareBy({ !it.isDir }, { decodeHref(it.href).lowercase() })))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            activity.runOnUiThread {
                if (provider.url != url) return@runOnUiThread // another server now: this listing is not for it
                result.onSuccess { folders[href] = it; refresh() }
                result.onFailure { e ->
                    if (shown) { // a refresh: keep the last listing on screen
                        showError(e, url)
                        return@runOnUiThread
                    }
                    folders.remove(href)
                    // Only if the user is still on this folder: it has nothing to show. Back first: the refresh of
                    // showError must not find this folder on screen with no listing, and list it again
                    if (shownLevel == WebDavLevel.Folders(href)) NavigationManager.getInstance().popBackStack()
                    showError(e, url)
                }
            }
        }.start()
    }

    /** Ticks or unticks a folder. Ticking a folder drops its ticked sub-folders: the folder now covers them. */
    private fun toggle(href: String) {
        val t = provider.ticked.toMutableSet()
        if (!t.remove(href)) {
            t.removeAll { isUnder(it, href) }
            t.add(href)
        }
        provider.ticked = t
        refresh()
    }

    // ---- Deleted on the NAS ----

    private fun orphansSpec(): ListSpec {
        val items = sync.orphans()
        val rows = ArrayList<RowSpec>()
        for (item in items) {
            val f = File(item.localPath ?: "")
            rows += RowSpec(f.name, RowLook.VALUE, value = f.parentFile?.name ?: "", key = item.jellyfinId, onClick = {
                confirm(localize("Delete") + " " + f.name + "?", listOf(item.jellyfinId))
            })
        }
        if (items.size > 1) {
            rows += action(localize("Delete All")) {
                confirm(localize("Delete All") + " (${items.size})?", items.map { it.jellyfinId })
            }
        }
        return ListSpec(localize("Deleted on NAS"), rows, empty = localize("Nothing to delete"))
    }

    private fun confirm(question: String, hrefs: List<String>) {
        ActionScreens.open(confirmScreen(localize("Deleted on NAS"), question, localize("Delete"), selectAction = false) {
            val failed = try {
                hrefs.size - sync.deleteTracked(hrefs, onlyOrphans = true)
            } catch (e: LedgerNotSaved) {
                Notice.error(activity, errorText(e, localize))
                onSyncFinished() // the files are deleted: the library must not show them
                refresh()
                return@confirmScreen
            }
            if (failed > 0) Notice.error(activity, localize("Could not delete") + " ($failed)")
            if (failed < hrefs.size) onSyncFinished() // rescan so deleted songs leave the library
            refresh()
        })
    }

    // ---- Sync ----

    private fun confirmRemoveAll() {
        ActionScreens.open(confirmScreen(
            localize("WebDAV Sync"),
            localize("No folder selected: the synced files will be deleted."),
            localize("Delete All") + " (${sync.trackedCount()})",
            selectAction = false
        ) { launchSync(removeAll = true) })
    }

    /** Sync Now, or with [move] the Move Synced Files run (no network): same progress screen and cancel. */
    private fun launchSync(move: Boolean = false, removeAll: Boolean = false) {
        if (sync.isRunning) return Notice.show(activity, localize("Syncing..."))
        if (!move) {
            if (!provider.isConfigured()) return Notice.show(activity, notSet)
            if (provider.ticked.isEmpty()) {
                if (!sync.hasTracked()) return Notice.show(activity, localize("Select folders first"))
                // With no folder ticked, a sync deletes the files it put there: only when the user says so
                if (!removeAll) return confirmRemoveAll()
            }
            if (!isNetworkConnected()) return onRequestWifi()
        }

        val key = ActionScreens.open(ActionScreens.Spec(
            localize(if (move) "Move Synced Files" else "WebDAV Sync"),
            listOf(localize(if (move) "Moving..." else "Syncing...")),
            onCancel = { sync.cancel() }
        ))
        val syncUrl = provider.url // a certificate error is of this server, also if the URL is changed before it arrives
        var lastUpdate = 0L
        var lastDone = 0L
        var speed = 0.0
        val files = LinkedHashMap<String, Int>() // downloading now: local path -> percent (-1 = size unknown); two albums can have a file of one name
        val listener = object : WebDavSync.Listener {
            override fun onFile(path: String, done: Long, size: Long) {
                if (done < 0) files.remove(path) else files[path] = if (size > 0) (done * 100 / size).toInt() else -1
            }

            override fun onListing(folder: String) {
                ActionScreens.update(key, listOf(localize("Listing") + " " + folder))
            }

            override fun onProgress(text: String, done: Long, total: Long) {
                val now = SystemClock.uptimeMillis()
                if (now - lastUpdate < 250) return // at most 4 screen updates a second
                val dt = now - lastUpdate
                if (lastUpdate > 0 && done >= lastDone) { // smoothed, so one slow file does not jump the number
                    val inst = (done - lastDone) * 1000.0 / dt
                    speed = if (speed == 0.0) inst else 0.7 * speed + 0.3 * inst
                }
                lastUpdate = now
                lastDone = done
                val pct = if (total > 0) "${done * 100 / total} %" else ""
                val rate = if (total > 0 && speed >= 1024) " · " + (if (speed >= 1048576) String.format(java.util.Locale.US, "%.1f MB/s", speed / 1048576) else "${(speed / 1024).toInt()} KB/s") else ""
                // A bar for each file in progress (none while its size is not known)
                ActionScreens.update(key, listOf(text, pct + rate) + files.keys.map { File(it).name }, listOf(null, null) + files.values.map { p -> p.takeIf { it >= 0 } })
            }

            override fun onDone(changed: Boolean) {
                ActionScreens.close(key)
                Notice.show(activity, localize(if (move) "Move Complete" else "Sync Complete"))
                if (changed) onSyncFinished()
                refresh()
            }

            override fun onError(e: Throwable, changed: Boolean) {
                ActionScreens.close(key)
                if (changed) onSyncFinished() // files were downloaded, deleted or moved before it stopped
                refresh() // also after a cancel: the counts and Last Synced can have changed
                if (e !is CancellationException) showError(e, syncUrl, asked = true)
            }
        }
        val started = if (move) sync.startMove(listener) else sync.start(listener, removeAll)
        if (!started) ActionScreens.close(key)
    }
}
