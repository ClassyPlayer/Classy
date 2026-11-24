package com.themoon.y1.sync.webdav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.themoon.y1.StoragePaths
import com.themoon.y1.core.ApiCompat
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.sync.SyncLedger
import com.themoon.y1.sync.SyncLocks
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Runs a WebDAV sync (or a move between storages) on one background thread. Files deleted on the NAS are only marked. */
class WebDavSync private constructor(context: Context) {

    interface Listener {
        fun onProgress(text: String, done: Long, total: Long)
        /** One file's progress ([done] of [size] bytes); done < 0 means the file is finished. [path] is its local path: the one id of a download. */
        fun onFile(path: String, done: Long, size: Long) {}
        /** The sync lists the NAS folder [folder]. */
        fun onListing(folder: String) {}
        fun onDone(changed: Boolean)
        /** [changed]: the run had downloaded, deleted or moved files before it stopped (the library needs a rescan). */
        fun onError(e: Throwable, changed: Boolean)
    }

    private val ctx = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val ledgerFile = StoragePaths.primaryFile(LEDGER_FILE)
    private val ledger = SyncLedger(ledgerFile)

    /** Of the ledger file as this object last read or wrote it: an unchanged file is not read again (the UI asks often). */
    private var ledgerStamp = -1L

    private fun stampOfLedger() = ledgerFile.lastModified() * 31 + ledgerFile.length()

    private fun loadLedger() {
        val stamp = stampOfLedger()
        if (stamp == ledgerStamp) return
        ledger.load()
        ledgerStamp = stampOfLedger()
    }

    private fun saveLedger() {
        ledger.save()
        ledgerStamp = stampOfLedger()
    }

    @Volatile
    var isRunning = false
        private set

    @Volatile
    private var cancelled = false

    /** Set by the run on the sync thread: whether it changed files. A run that fails hands it to its own onError. */
    @Volatile
    private var changedByRun = false

    /** Music on the storage the user picked (Save To), not the generic sync root. */
    fun destRoot(onSd: Boolean = WebDavSyncProvider.getInstance(ctx).onSdCard): File =
        File(if (onSd) StoragePaths.SECONDARY_PATH else StoragePaths.PRIMARY_PATH, "Music")

    @Synchronized
    fun start(listener: Listener, removeAllUnticked: Boolean = false): Boolean = launch(listener, isSync = true) { run(it, removeAllUnticked) }

    /** Moves the synced files from the other storage into [destRoot]. Same listener and lock as a sync. */
    @Synchronized
    fun startMove(listener: Listener): Boolean = launch(listener, isSync = false) { move(it) }

    /** [isSync]: the result goes to [WebDavSyncProvider.lastError], which the settings show (Auto Sync has no screen). */
    private fun launch(listener: Listener, isSync: Boolean, work: (Listener) -> Boolean): Boolean {
        if (isRunning) return false
        isRunning = true
        cancelled = false
        changedByRun = false
        executor.execute {
            var result: () -> Unit = {}
            try {
                val changed = SyncLocks.hold(ctx, "Classy:WebDavSync", WAKE_LOCK_MS) { work(listener) }
                if (isSync) WebDavSyncProvider.getInstance(ctx).lastError = ""
                result = { listener.onDone(changed) }
            } catch (e: Throwable) {
                Log.w(TAG, "Sync failed: ${e.message}")
                if (isSync && e !is CancellationException) WebDavSyncProvider.getInstance(ctx).lastError = errorText(e)
                val changed = changedByRun // read here: a next run can reset it before this callback runs
                result = { listener.onError(e, changed) }
            } finally {
                isRunning = false
                main.post(result) // after isRunning: the screen that the callback refreshes sees the run as ended
            }
        }
        return true
    }

    fun cancel() {
        cancelled = true
    }

    /**
     * Runs [change] (a reset, or another server) and forgets every synced file: the files stay on the player, but no
     * later sync deletes them as the files of an unticked folder. False, and nothing changes, while a sync runs.
     */
    @Synchronized
    fun forgetAll(change: () -> Unit): Boolean {
        if (isRunning) return false
        change()
        synchronized(ledger) {
            loadLedger()
            for (href in ledger.getAllItems().keys) ledger.removeItem(href)
            ledger.setLastSyncTime(0)
            saveLedger()
        }
        return true
    }

    private fun progress(l: Listener, text: String, done: Long, total: Long) = main.post { l.onProgress(text, done, total) }

    private fun checkCancel() {
        if (cancelled) throw CancellationException("Sync cancelled")
    }

    /** Returns true when files were downloaded (the library needs a rescan). */
    /**
     * [removeAllUnticked]: with no folder ticked, the files of this sync are deleted only when the user said so
     * (Sync Now asks); Auto Sync never does.
     */
    private fun run(l: Listener, removeAllUnticked: Boolean): Boolean {
        val provider = WebDavSyncProvider.getInstance(ctx)
        if (!provider.isConfigured()) throw IllegalStateException("WebDAV is not configured")
        val client = provider.client()
        val ticked = provider.ticked
        if (provider.onSdCard && !StoragePaths.hasSecondaryStorage()) throw IOException("SD card not found")
        synchronized(ledger) { loadLedger() }

        val remote = LinkedHashMap<String, List<DavEntry>>()
        for (folder in ticked) {
            checkCancel()
            main.post { l.onListing(WebDavSyncProvider.folderName(folder)) }
            remote[folder] = try {
                walk(client, folder)
            } catch (e: DavHttpException) {
                if (e.code == 404) emptyList() else throw e // the ticked folder itself is gone: its files become orphans
            }
        }

        val dest = destRoot()
        val plan = synchronized(ledger) {
            SyncPlan.build(remote, ledger.getAllItems(), ticked, dest, client.rootHref, safeNames = provider.onSdCard,
                otherRoot = destRoot(!provider.onSdCard)) { it.exists() }
        }
        dest.mkdirs()
        requireFreeSpace(plan.bytes, if (dest.exists()) dest else File(StoragePaths.PRIMARY_PATH))

        synchronized(ledger) {
            for (href in plan.orphan) ledger.getItem(href)?.let { it.orphaned = true; ledger.putItem(it) }
            for (href in plan.unorphan) ledger.getItem(href)?.let { it.orphaned = false; ledger.putItem(it) }
            saveLedger()
        }

        var changed = false
        // The user unticked these folders: delete what this sync put there (a playing file waits for the next sync)
        checkCancel()
        if (ticked.isNotEmpty() || removeAllUnticked) {
            if (deleteTracked(plan.remove) > 0) { changed = true; changedByRun = true }
        }

        val player = AudioPlayerManager.getInstance()
        val got = AtomicLong(0) // bytes of all files so far, finished or not
        val finished = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null) // the first error stops the other downloads
        val pool = Executors.newFixedThreadPool(provider.parallel)
        for (d in plan.downloads) {
            pool.execute {
                if (failure.get() != null) return@execute
                try {
                    checkCancel()
                    // Replaced on the next sync. Also when the copy it replaces plays: the ledger keeps that one until then
                    if (player.isFileCurrentlyPlaying(d.target) || d.replaces?.let { player.isFileCurrentlyPlaying(it) } == true) return@execute
                    var last = 0L
                    var lastPost = 0L
                    client.download(d.entry.href, d.target, d.entry.size, { cancelled || failure.get() != null }) { n ->
                        val all = got.addAndGet(n - last)
                        last = n
                        // At most ten posts a second for each file, not one for every 64 KB chunk
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPost < PROGRESS_POST_MS && n != d.entry.size) return@download
                        lastPost = now
                        val label = "${finished.get() + 1}/${plan.downloads.size}"
                        main.post { l.onFile(d.target.path, n, d.entry.size); l.onProgress(label, all, plan.bytes) }
                    }
                    main.post { l.onFile(d.target.path, -1, 0) }
                    synchronized(ledger) {
                        ledger.putItem(SyncLedger.LedgerItem(d.entry.href, d.folderHref, "music", d.target.path, d.target.length(), versionOf(d.entry), false))
                        if (finished.incrementAndGet() % 20 == 0) saveLedger() // keep progress if the device dies mid-sync
                    }
                    // The old copy (other storage or path): the record now names the new one
                    d.replaces?.let { old ->
                        if (old.exists() && old.delete()) pruneEmptyDirs(old, listOf(destRoot(true), destRoot(false)))
                        else if (old.exists()) Log.w(TAG, "Cannot delete the old copy ${old.path}")
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        } finally {
            if (!pool.isTerminated) pool.shutdownNow()
            synchronized(ledger) { saveLedger() } // also after an error: the finished files stay tracked
            if (finished.get() > 0) changedByRun = true
        }
        failure.get()?.let { throw it }
        if (finished.get() > 0) changed = true

        val now = System.currentTimeMillis()
        synchronized(ledger) {
            ledger.setLastSyncTime(now)
            saveLedger()
        }
        provider.lastSync = now
        return changed
    }

    /** Throws [NotEnoughSpace] when [need] bytes do not fit on the storage of [dir]. */
    private fun requireFreeSpace(need: Long, dir: File) {
        val free = ApiCompat.availableBytes(StatFs(dir.absolutePath))
        if (need > free) throw NotEnoughSpace(need, free)
    }

    private fun walk(client: WebDavClient, folder: String): List<DavEntry> {
        val files = ArrayList<DavEntry>()
        val queue = ArrayDeque<String>().apply { add(folder) }
        val visited = HashSet<String>()
        while (queue.isNotEmpty()) {
            checkCancel()
            val href = queue.removeFirst()
            if (!visited.add(decodeHref(href).trimEnd('/'))) continue // decoded: one folder can come with two spellings
            if (visited.size > MAX_FOLDERS) throw IOException("Too many folders (a loop on the server?)")
            val list = try {
                client.list(href)
            } catch (e: DavHttpException) {
                if (e.code == 404 && href != folder) continue // a sub-folder that went: only its own files are gone
                throw e
            }
            for (e in list) {
                if (e.isDir) queue.add(e.href) else files.add(e)
            }
        }
        return files
    }

    /** Synced files that are on the other storage, not under [destRoot] (Move Synced Files). */
    fun misplaced(): List<SyncLedger.LedgerItem> = synchronized(ledger) {
        loadUnlessRunning()
        val onSd = WebDavSyncProvider.getInstance(ctx).onSdCard
        val to = destRoot()
        val from = destRoot(!onSd)
        ledger.getAllItems().values.filter { movedPath(it.localPath, from, to, onSd) != null }
    }

    /**
     * Copy, check the length, then delete the old copy: a failed or cancelled move never loses a file.
     * A playing file stays where it is (move again later). Returns true when files moved (rescan).
     */
    private fun move(l: Listener): Boolean {
        val onSd = WebDavSyncProvider.getInstance(ctx).onSdCard
        if (onSd && !StoragePaths.hasSecondaryStorage()) throw IOException("SD card not found")
        val to = destRoot(onSd)
        val from = destRoot(!onSd)
        val items = synchronized(ledger) {
            loadLedger()
            ledger.getAllItems().values.filter { movedPath(it.localPath, from, to, onSd) != null }
        }
        val total = items.sumOf { File(it.localPath!!).length() }
        to.mkdirs()
        requireFreeSpace(total, if (to.exists()) to else to.parentFile!!)

        val player = AudioPlayerManager.getInstance()
        var done = 0L
        var moved = 0
        try {
            for ((i, item) in items.withIndex()) {
                checkCancel()
                val src = File(item.localPath!!)
                val dst = movedPath(item.localPath, from, to, onSd)!!
                if (src.exists()) {
                    if (player.isFileCurrentlyPlaying(src)) continue
                    progress(l, "${i + 1}/${items.size} ${src.name}", done, total)
                    copyChecked(src, dst)
                    done += src.length()
                    if (!src.delete()) { dst.delete(); continue } // keep one copy, tracked where it is
                } else if (!dst.exists() || dst.length() != item.fileSize) {
                    continue // the source is not there (an SD card that is out) and nothing complete is at the target: keep the record
                }
                moved++
                synchronized(ledger) {
                    item.localPath = dst.path
                    ledger.putItem(item)
                    if (moved % 20 == 0) saveLedger() // keep progress if the device dies mid-move
                }
            }
        } finally {
            synchronized(ledger) { saveLedger() } // also after an error or a cancel: the moved files stay tracked where they are
            if (moved > 0) changedByRun = true
        }
        return moved > 0
    }

    /** Copies into `dst.part`, checks the length, then replaces [dst]. On failure [dst] is as it was. */
    private fun copyChecked(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        val part = File(dst.path + ".part")
        try {
            src.inputStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        checkCancel()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (part.length() != src.length()) throw IOException("Copy of ${src.name} is incomplete")
            replaceFile(part, dst)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    /** Reads the ledger from disk, except during a sync: then memory holds entries not saved yet. */
    private fun loadUnlessRunning() {
        if (!isRunning) loadLedger()
    }

    /** How many files this sync tracks. */
    fun trackedCount(): Int = synchronized(ledger) {
        loadUnlessRunning()
        ledger.getAllItems().size
    }

    /** True while this sync tracks any file: a sync with no folders ticked still has work (removing them). */
    fun hasTracked(): Boolean = synchronized(ledger) {
        loadUnlessRunning()
        ledger.getAllItems().isNotEmpty()
    }

    fun orphans(): List<SyncLedger.LedgerItem> = synchronized(ledger) {
        loadUnlessRunning()
        ledger.getAllItems().values.filter { it.orphaned }.sortedBy { it.localPath ?: "" }
    }

    /**
     * Deletes the files this sync tracks under [hrefs] (orphans the user chose, or an unticked folder) and saves the
     * ledger once. A playing file, or one that cannot be deleted, stays tracked. Returns how many are gone.
     */
    fun deleteTracked(hrefs: Collection<String>): Int = synchronized(ledger) {
        loadUnlessRunning()
        val player = AudioPlayerManager.getInstance()
        var gone = 0
        for (href in hrefs) {
            val item = ledger.getItem(href)
            if (item == null) { gone++; continue }
            val f = item.localPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
            if (f != null && f.exists() && (player.isFileCurrentlyPlaying(f) || !f.delete())) continue
            if (f != null) pruneEmptyDirs(f, listOf(destRoot(true), destRoot(false)))
            ledger.removeItem(href)
            gone++
        }
        saveLedger()
        gone
    }

    companion object {
        private const val TAG = "WebDavSync"
        private const val PROGRESS_POST_MS = 100L
        /** A sync that hangs does not keep the player awake for ever. */
        private const val WAKE_LOCK_MS = 6 * 60 * 60 * 1000L
        /** More folders than this in one tree: the server links back to itself. */
        private const val MAX_FOLDERS = 20_000
        const val LEDGER_FILE = "webdav_ledger.json"
        /** Classy rescans its library on this broadcast (MainActivity system receiver). */
        const val ACTION_SYNCED = "com.themoon.y1.RESCAN_LIBRARY"

        @Volatile
        private var instance: WebDavSync? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): WebDavSync =
            instance ?: WebDavSync(context).also { instance = it }
    }
}
