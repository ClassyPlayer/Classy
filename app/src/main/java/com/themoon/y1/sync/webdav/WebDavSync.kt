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

    /** False when the file was not written (the old one stays on disk). */
    private fun saveLedger(): Boolean {
        val saved = ledger.save()
        ledgerStamp = stampOfLedger()
        return saved
    }

    @Volatile
    var isRunning = false
        private set

    /** Runs on the main thread after each run, after its own listener: the settings screen refreshes (also for Auto Sync). */
    @Volatile
    var onRunEnded: (() -> Unit)? = null

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
                main.post { // after isRunning: the screen that the callback refreshes sees the run as ended
                    result()
                    onRunEnded?.invoke()
                }
            }
        }
        return true
    }

    fun cancel() {
        cancelled = true
    }

    /**
     * Forgets every synced file, then runs [change] (a reset, or another server): the files stay on the player, but no
     * later sync deletes them as the files of an unticked folder. False, and nothing changes, while a sync runs.
     * Throws [LedgerNotSaved], and nothing changes, when the empty ledger cannot be written: else the old records would
     * come back at the next start, and a sync could delete those files.
     */
    @Synchronized
    fun forgetAll(change: () -> Unit): Boolean {
        if (isRunning) return false
        synchronized(ledger) {
            loadLedger()
            // A copy of memory, not the file: memory can be newer (a move whose last save failed)
            val before = ledger.getAllItems()
            val lastTime = ledger.getLastSyncTime()
            for (href in before.keys) ledger.removeItem(href)
            ledger.setLastSyncTime(0)
            if (!saveLedger()) {
                for (item in before.values) ledger.putItem(item)
                ledger.setLastSyncTime(lastTime)
                throw LedgerNotSaved()
            }
        }
        change()
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

        if (plan.conflicts.isNotEmpty()) Log.w(TAG, "${plan.conflicts.size} files skipped: a file that this sync did not make is at their place")
        synchronized(ledger) {
            // Files at their place with the server's size: recorded as synced, not downloaded again
            for (d in plan.adopt) ledger.putItem(SyncLedger.LedgerItem(d.entry.href, d.folderHref, "music", d.target.path, d.target.length(), versionOf(d.entry), false))
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

        sweepPartFiles(plan.downloads.mapNotNull { it.target.parentFile }, synchronized(ledger) { trackedKeys() })
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
                    // Checked again at the end: the user can start the old file while the new one downloads
                    client.download(d.entry.href, d.target, d.entry.size, { cancelled || failure.get() != null },
                        { !player.isFileCurrentlyPlaying(d.target) }) { n ->
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
                        // Checked again: the user can start the old copy while the new one downloads. A playing file is never
                        // deleted, nor one that another record keeps (two names that became one FAT name)
                        val shared = synchronized(ledger) { keptByOther(d.entry.href, old.path) }
                        if (old.exists() && !shared && insideMusic(old) && !player.isFileCurrentlyPlaying(old) && old.delete()) pruneEmptyDirs(old, listOf(destRoot(true), destRoot(false)))
                        else if (old.exists()) Log.w(TAG, "Old copy kept (playing, or cannot be deleted): ${old.path}")
                    }
                } catch (_: TargetBusy) {
                    main.post { l.onFile(d.target.path, -1, 0) } // replaced on the next sync, as a file that played at the start
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }
        }
        pool.shutdown()
        var saved = false
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        } finally {
            if (!pool.isTerminated) pool.shutdownNow()
            saved = synchronized(ledger) { saveLedger() } // also after an error: the finished files stay tracked
            if (finished.get() > 0) changedByRun = true
        }
        failure.get()?.let { throw it }
        // Not a sync that worked: the records are in memory only (the next save writes them)
        if (!saved) throw LedgerNotSaved()
        if (finished.get() > 0) changed = true

        val now = System.currentTimeMillis()
        synchronized(ledger) {
            ledger.setLastSyncTime(now)
            if (!saveLedger()) throw LedgerNotSaved()
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
                client.list(href) { cancelled }
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
        // The target of each file comes from its href, as for a sync: a FAT name ("Why_.mp3") gets its own name back
        // on internal storage ("Why?.mp3"), so the next sync finds it there and does not download it again
        val base = WebDavSyncProvider.getInstance(ctx).rootHref()
        fun target(item: SyncLedger.LedgerItem): File =
            base?.let { localFileFor(item.jellyfinId, it, to, onSd) } ?: movedPath(item.localPath, from, to, onSd)!!
        val total = items.sumOf { File(it.localPath!!).length() }
        // Space only for the files to copy: a complete copy at the target needs none
        val toCopy = items.filter { File(it.localPath!!).exists() && !target(it).exists() }.sumOf { File(it.localPath!!).length() }
        to.mkdirs()
        requireFreeSpace(toCopy, if (to.exists()) to else to.parentFile!!)
        sweepPartFiles(items.mapNotNull { target(it).parentFile }, synchronized(ledger) { trackedKeys() })
        // How many records keep each file, counted once: a shared source (two names that became one FAT name) stays
        // until its last record moves
        val owners = synchronized(ledger) {
            HashMap<String, Int>().also { m -> for (it in ledger.getAllItems().values) it.localPath?.let { p -> m.add(pathKey(p), 1) } }
        }

        val player = AudioPlayerManager.getInstance()
        var done = 0L
        var moved = 0
        var saved = false
        try {
            for ((i, item) in items.withIndex()) {
                checkCancel()
                val src = File(item.localPath!!)
                val dst = target(item)
                if (src.exists()) {
                    // A playing file is never replaced or deleted: the source, or a file already at the target
                    if (player.isFileCurrentlyPlaying(src) || player.isFileCurrentlyPlaying(dst)) continue
                    val dstWasThere = dst.exists()
                    if (dstWasThere) {
                        // Never overwrite a file at the target: only one with the same bytes is this file (a move
                        // that stopped after its copy), and then the source can go
                        if (!sameContent(src, dst)) continue
                    } else {
                        progress(l, "${i + 1}/${items.size} ${src.name}", done, total)
                        copyChecked(src, dst)
                    }
                    done += src.length()
                    // A source that another record still keeps (two names that became one FAT name) stays for that one;
                    // the last record that moves deletes it
                    val shared = (owners[pathKey(src.path)] ?: 0) > 1
                    if (!shared) {
                        // Checked again: the user can start the source during the copy
                        if (player.isFileCurrentlyPlaying(src) || !src.delete()) {
                            if (!dstWasThere) dst.delete() // keep one copy, tracked where it is
                            continue
                        }
                        pruneEmptyDirs(src, listOf(from, to)) // the album folders that the move left empty
                    }
                } else {
                    // The source is not there (an SD card that is out): keep the record. A file of the same size at the
                    // target can be an older version, so it is not taken as this one
                    continue
                }
                moved++
                owners.add(pathKey(src.path), -1)
                owners.add(pathKey(dst.path), 1)
                synchronized(ledger) {
                    item.localPath = dst.path
                    ledger.putItem(item)
                    if (moved % 20 == 0) saveLedger() // keep progress if the device dies mid-move
                }
            }
        } finally {
            saved = synchronized(ledger) { saveLedger() } // also after an error or a cancel: the moved files stay tracked where they are
            if (moved > 0) changedByRun = true
        }
        if (!saved) throw LedgerNotSaved() // the new paths are in memory only (the next save writes them)
        return moved > 0
    }

    /**
     * Deletes the temporary files ([newPartFile]) that a run which stopped (the app or the player went off) left in
     * [dirs], the folders that this run writes to. One run at a time, so no other run is writing them.
     */
    private fun sweepPartFiles(dirs: Collection<File>, tracked: Set<String>) {
        for (dir in dirs.toSet()) {
            dir.listFiles { f -> f.isFile && f.name.startsWith(PART_PREFIX) && f.name.endsWith(PART_SUFFIX) }
                ?.filter { pathKey(it.path) !in tracked } // a synced server file with such a name stays
                ?.forEach { it.delete() }
        }
    }

    /** The [pathKey] of each file that the ledger records. Call it with the ledger lock. */
    private fun trackedKeys(): Set<String> = ledger.getAllItems().values.mapNotNull { it.localPath?.let(::pathKey) }.toSet()

    /** Adds [n] to the count of [key] (HashMap.merge is API 24). */
    private fun HashMap<String, Int>.add(key: String, n: Int) {
        this[key] = (this[key] ?: 0) + n
    }

    /** True when [f] is below one of the Music folders (after "..", links and the like are resolved). */
    private fun insideMusic(f: File): Boolean = try {
        val path = f.canonicalPath
        listOf(destRoot(true), destRoot(false)).any { path.startsWith(it.canonicalPath.trimEnd('/') + "/") }
    } catch (_: IOException) {
        false
    }

    /** The key of a local path: FAT (the SD card) does not tell "A" from "a". */
    private fun pathKey(path: String): String = if (path.startsWith(destRoot(true).path + "/")) path.lowercase() else path

    /** True when a ledger record other than [href] has the file [path]. Call it with the ledger lock. */
    private fun keptByOther(href: String, path: String): Boolean {
        val key = pathKey(path)
        return ledger.getAllItems().values.any { it.jellyfinId != href && it.localPath?.let(::pathKey) == key }
    }

    /** True when [a] and [b] have the same bytes. */
    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                val bx = ByteArray(64 * 1024)
                val by = ByteArray(64 * 1024)
                while (true) {
                    checkCancel()
                    val n = x.read(bx)
                    if (n < 0) return true
                    var off = 0
                    while (off < n) {
                        val m = y.read(by, off, n - off)
                        if (m < 0) return false
                        off += m
                    }
                    for (k in 0 until n) if (bx[k] != by[k]) return false
                }
            }
        }
    }

    /** Copies into a new `.part` file ([newPartFile]), checks the length, then replaces [dst]. On failure [dst] is as it was. */
    private fun copyChecked(src: File, dst: File) {
        val part = newPartFile(dst)
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
    fun deleteTracked(hrefs: Collection<String>, onlyOrphans: Boolean = false): Int = synchronized(ledger) {
        loadUnlessRunning()
        val player = AudioPlayerManager.getInstance()
        // The files that other records keep: two server names that became one FAT name share a file
        val removing = hrefs.toSet()
        val kept = ledger.getAllItems().values.filter { it.jellyfinId !in removing }.mapNotNull { it.localPath?.let(::pathKey) }.toSet()
        var gone = 0
        for (href in hrefs) {
            val item = ledger.getItem(href)
            if (item == null) { gone++; continue }
            // Deleted on NAS: a sync since the list was shown can have found the file on the server again. It stays
            if (onlyOrphans && !item.orphaned) continue
            if (item.localPath?.let(::pathKey) in kept) { ledger.removeItem(href); gone++; continue } // only the record goes
            val f = item.localPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
            // The ledger is a file on shared storage: a path outside the Music folders is not deleted, only its record
            if (f != null && !insideMusic(f)) { ledger.removeItem(href); gone++; continue }
            if (f != null && f.exists() && (player.isFileCurrentlyPlaying(f) || !f.delete())) continue
            if (f != null) pruneEmptyDirs(f, listOf(destRoot(true), destRoot(false)))
            ledger.removeItem(href)
            gone++
        }
        // The deleted records stay out of memory, so the next save writes them; the caller tells the user
        if (!saveLedger()) throw LedgerNotSaved()
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
