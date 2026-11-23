package com.themoon.y1.sync.webdav

import com.themoon.y1.sync.SyncLedger
import java.io.File

data class Download(val entry: DavEntry, val folderHref: String, val target: File)

/**
 * [orphan], [unorphan] and [remove] are ledger keys (hrefs). [orphan]: deleted on the NAS, the user decides.
 * [remove]: its folder is no longer ticked, so the sync deletes the local copy. Nothing here deletes files.
 */
data class Plan(val downloads: List<Download>, val orphan: List<String>, val unorphan: List<String>, val remove: List<String>, val bytes: Long)

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
    return File(to, if (safeNames) rest.split('/').joinToString("/") { fatSafe(it) } else rest)
}

/** True when [href] is below the folder [parent] (a folder named like the start of another one is not). */
fun isUnder(href: String, parent: String): Boolean = href.startsWith(parent.trimEnd('/') + "/")

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
        exists: (File) -> Boolean
    ): Plan {
        val downloads = ArrayList<Download>()
        val unorphan = ArrayList<String>()
        val seen = HashSet<String>()
        val targets = HashSet<String>() // the local files of the selected folders: the remove list leaves them alone
        val planned = HashSet<String>() // one download for each file: FAT names that become one must not write it at once

        for ((folder, entries) in remote) {
            for (e in entries) {
                if (e.isDir) continue
                val target = localFileFor(e.href, baseHref, destRoot, safeNames) ?: continue
                seen.add(e.href)
                targets.add(target.path)
                val item = ledger[e.href]
                // A recorded path that is not this target (the storage changed): the version is of another copy
                if (item == null || item.localPath != target.path || !exists(target) || changed(e, item)) {
                    if (planned.add(target.path)) downloads.add(Download(e, folder, target))
                } else if (item.orphaned) {
                    unorphan.add(e.href)
                }
            }
        }

        // By path, not the stored folder: that one goes stale when the user ticks a parent or a sub-folder instead
        fun covering(href: String) = ticked.firstOrNull { isUnder(href, it) }
        val gone = ledger.values.filter { it.jellyfinId !in seen }
        val orphan = gone.filter { !it.orphaned && covering(it.jellyfinId)?.let { f -> f in remote.keys } == true }.map { it.jellyfinId }
        val remove = gone.filter { covering(it.jellyfinId) == null && it.localPath !in targets }.map { it.jellyfinId }

        return Plan(downloads, orphan, unorphan, remove, downloads.sumOf { maxOf(it.entry.size, 0L) })
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
