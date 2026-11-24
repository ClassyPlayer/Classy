package com.themoon.y1.sync.webdav

import com.themoon.y1.sync.SyncLedger
import java.io.File

/** [replaces]: the copy that the ledger has at another path; the sync deletes it when [target] is complete. */
data class Download(val entry: DavEntry, val folderHref: String, val target: File, val replaces: File? = null)

/**
 * [orphan], [unorphan] and [remove] are ledger keys (hrefs). [orphan]: deleted on the NAS, the user decides.
 * [remove]: its folder is no longer ticked, so the sync deletes the local copy. Nothing here deletes files.
 */
data class Plan(
    val downloads: List<Download>,
    val orphan: List<String>,
    val unorphan: List<String>,
    val remove: List<String>,
    val bytes: Long,
    /** A file that is at the target already, not made by this sync, with the server's size: recorded, not downloaded. */
    val adopt: List<Download> = emptyList(),
    /** Hrefs whose target is a file that this sync did not make, of another size: skipped, the file stays as it is. */
    val conflicts: List<String> = emptyList()
)

/** The ledger stores the server version as "<etag>|<lastModified>" in serverDateModified. */
fun versionOf(e: DavEntry): String = "${e.etag ?: ""}|${e.lastModified ?: ""}"

/**
 * Local file for [href] under [destRoot], mirroring the path below [baseHref].
 * Null when the href is outside the base, empty, or has a "." or ".." segment.
 */
fun localFileFor(href: String, baseHref: String, destRoot: File, safeNames: Boolean = false): File? {
    val path = decodeHref(href)
    val base = decodeHref(baseHref).let { if (it.endsWith("/")) it else "$it/" }
    if (!path.startsWith(base)) return null
    val segments = path.removePrefix(base).split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null
    return File(destRoot, segments.joinToString("/") { if (safeNames) fatSafe(it) else it })
}

/**
 * [name] as a FAT file name (the SD card): the characters FAT does not take, `< > : " | ? * \` and the control
 * characters, become `_`, and a name does not end with a dot or a space. Any other name is as it was.
 * Two names that differ only in such characters now share one file; the later sync of the two replaces the other.
 */
fun fatSafe(name: String): String {
    val s = name.map { c -> if (c < ' ' || c in "<>:\"|?*\\") '_' else c }.joinToString("").trimEnd('.', ' ')
    return s.ifEmpty { "_" }
}

/** Where a synced file under [from] goes under [to], same tree. Null when it is not under [from]. */
fun movedPath(localPath: String?, from: File, to: File, safeNames: Boolean = false): File? {
    val prefix = from.path.trimEnd('/') + "/"
    if (localPath == null || !localPath.startsWith(prefix)) return null
    val rest = localPath.removePrefix(prefix)
    // The ledger is a file on shared storage: a "." or ".." segment must not take a move outside the Music folders
    if (rest.split('/').any { it == "." || it == ".." }) return null
    return File(to, if (safeNames) rest.split('/').joinToString("/") { fatSafe(it) } else rest)
}

/**
 * True when [href] is below the folder [parent] (a folder named like the start of another one is not). Decoded, so
 * "%C3%BA", "%c3%ba" and "ú" are one name.
 */
fun isUnder(href: String, parent: String): Boolean = decodeHref(href).startsWith(decodeHref(parent).trimEnd('/') + "/")

/** Deletes the empty folders above [file] (a deleted file), up to but not including a folder of [roots]. */
fun pruneEmptyDirs(file: File, roots: Collection<File>) {
    var dir = file.parentFile
    while (dir != null && roots.none { it == dir } && dir.delete()) dir = dir.parentFile // delete() fails on a folder that has files
}

object SyncPlan {

    /**
     * [remote] maps each ticked folder that was listed successfully to every file below it.
     * A ticked folder missing from [remote] keeps its ledger items untouched (a failed listing never means "deleted").
     */
    fun build(
        remote: Map<String, List<DavEntry>>,
        ledger: Map<String, SyncLedger.LedgerItem>,
        ticked: Set<String>,
        destRoot: File,
        baseHref: String,
        safeNames: Boolean = false,
        /** The Music folder of the other storage (Save To): a copy there is left to Move Synced Files. */
        otherRoot: File? = null,
        size: (File) -> Long = { it.length() },
        isFile: (File) -> Boolean = { it.isFile },
        exists: (File) -> Boolean
    ): Plan {
        val downloads = ArrayList<Download>()
        val adopt = ArrayList<Download>()
        val conflicts = ArrayList<String>()
        // The files that the ledger records (case-insensitive on FAT): a file at a target that is not one of them is
        // the user's own (copied by USB, or of Jellyfin Sync), and a sync never writes over it
        fun keyOf(path: String) = if (safeNames) path.lowercase() else path
        val recordedPaths = ledger.values.mapNotNull { it.localPath?.takeIf { p -> p.isNotEmpty() }?.let(::keyOf) }.toSet()
        val unorphan = ArrayList<String>()
        val seen = HashSet<String>()
        val targets = HashSet<String>() // the local files of the selected folders: the remove list leaves them alone
        val planned = HashSet<String>() // one download for each file: FAT names that become one must not write it at once

        for ((folder, entries) in remote) {
            for (e in entries) {
                if (e.isDir) continue
                val target = localFileFor(e.href, baseHref, destRoot, safeNames) ?: continue
                seen.add(e.href)
                val key = if (safeNames) target.path.lowercase() else target.path // FAT does not tell Track.flac from track.flac
                targets.add(key)
                val item = ledger[e.href]
                val recorded = item?.localPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
                val here = recorded?.path == target.path && exists(target)
                // Synced before a Save To change: the same file on the other storage, at the path that this href has
                // there (the other storage has the other name rule: FAT names on the SD card). Move Synced Files moves
                // it, so the sync does not get it again
                val onOtherStorage = recorded != null && recorded.path != target.path && otherRoot != null &&
                    localFileFor(e.href, baseHref, otherRoot, !safeNames)?.path == recorded.path && exists(recorded)
                if (item == null || !(here || onOtherStorage) || changed(e, item)) {
                    // The new copy replaces the recorded one: that one must not stay on the player untracked
                    val replaces = recorded?.takeIf { it.path != target.path }
                    if (!planned.add(key)) continue
                    // A local file where the server has a folder: the download cannot make its folder. Skip this file
                    // only, not the whole sync
                    if (generateSequence(target.parentFile) { it.parentFile }.takeWhile { it != destRoot }.any { isFile(it) }) {
                        conflicts.add(e.href)
                        continue
                    }
                    if (key !in recordedPaths && exists(target)) {
                        // Not a file of this sync. With the server's size it is taken as this file (a sync after Log
                        // Out / Reset, a copy made by hand, or a record whose own copy is gone after a crash); else it
                        // is the user's own file (or a folder) and stays
                        val recordGone = item == null || recorded == null || !exists(recorded)
                        if (recordGone && isFile(target) && e.size >= 0 && size(target) == e.size) adopt.add(Download(e, folder, target))
                        else conflicts.add(e.href)
                        continue
                    }
                    downloads.add(Download(e, folder, target, replaces))
                } else if (item.orphaned) {
                    unorphan.add(e.href)
                }
            }
        }

        // By path, not the stored folder: that one goes stale when the user ticks a parent or a sub-folder instead
        fun covering(href: String) = ticked.firstOrNull { isUnder(href, it) }
        val gone = ledger.values.filter { it.jellyfinId !in seen }
        val orphan = gone.filter { !it.orphaned && covering(it.jellyfinId)?.let { f -> f in remote.keys } == true }.map { it.jellyfinId }
        val remove = gone.filter { covering(it.jellyfinId) == null && (if (safeNames) it.localPath?.lowercase() else it.localPath) !in targets }.map { it.jellyfinId }

        return Plan(downloads, orphan, unorphan, remove, downloads.sumOf { maxOf(it.entry.size, 0L) }, adopt, conflicts)
    }

    private fun changed(e: DavEntry, item: SyncLedger.LedgerItem): Boolean {
        val stored = item.serverDateModified ?: ""
        val cut = stored.lastIndexOf('|')
        val oldEtag = if (cut >= 0) stored.substring(0, cut) else ""
        val oldMod = if (cut >= 0) stored.substring(cut + 1) else stored
        if (!e.etag.isNullOrEmpty() && oldEtag.isNotEmpty()) return e.etag != oldEtag
        return (e.size >= 0 && e.size != item.fileSize) || (e.lastModified ?: "") != oldMod
    }
}
