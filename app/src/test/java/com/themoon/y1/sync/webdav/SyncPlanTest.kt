package com.themoon.y1.sync.webdav

import com.themoon.y1.sync.SyncLedger
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SyncPlanTest {

    private val root = File("/sd/Music/WebDAV")
    private val base = "/music/"
    private val rock = "/music/Rock/"
    private val jazz = "/music/Jazz/"

    private fun file(href: String, size: Long = 10, etag: String? = "\"e1\"", mod: String? = "Mon, 01 Jan 2026 00:00:00 GMT") =
        DavEntry(href, false, size, etag, mod)

    private fun led(href: String, folder: String, size: Long = 10, etag: String? = "\"e1\"", mod: String? = "Mon, 01 Jan 2026 00:00:00 GMT", orphaned: Boolean = false) =
        SyncLedger.LedgerItem(href, folder, "music", localFileFor(href, base, root)!!.path, size, "${etag ?: ""}|${mod ?: ""}", orphaned)

    private fun build(
        remote: Map<String, List<DavEntry>>,
        ledger: List<SyncLedger.LedgerItem> = emptyList(),
        ticked: Set<String> = remote.keys,
        // As on the player: the files that the ledger records are there, and no other file
        exists: (File) -> Boolean = { f -> ledger.any { it.localPath == f.path } },
        otherRoot: File? = null,
        size: (File) -> Long = { -1L },
        isFile: (File) -> Boolean = exists
    ) = SyncPlan.build(remote, ledger.associateBy { it.jellyfinId }, ticked, root, base, otherRoot = otherRoot, size = size, isFile = isFile, exists = exists)

    @Test
    fun tickedSubfolderKeepsItsFullPath() {
        val live = "/music/Rock/Live/"
        val p = build(mapOf(live to listOf(file("/music/Rock/Live/a.flac"))))
        assertEquals(File(root, "Rock/Live/a.flac"), p.downloads.single().target)
    }

    @Test
    fun movedPathKeepsTheTreeBelowTheRoot() {
        val from = File("/storage/sdcard0/Music")
        val to = File("/storage/sdcard1/Music")
        assertEquals(File(to, "Rock/Live/a.flac"), movedPath("/storage/sdcard0/Music/Rock/Live/a.flac", from, to))
        assertNull(movedPath("/storage/sdcard1/Music/Rock/a.flac", from, to)) // already there
        assertNull(movedPath("/storage/sdcard0/Musical/a.flac", from, to)) // same prefix, other folder
        assertNull(movedPath("/storage/sdcard0/Music/../../data/x", from, to)) // a ledger path must stay below Music
        assertNull(movedPath("/storage/sdcard0/Music/./a.flac", from, to))
        assertNull(movedPath(null, from, to))
    }

    @Test
    fun newFileDownloads() {
        val p = build(mapOf(rock to listOf(file("/music/Rock/a.flac"))))
        assertEquals(listOf("/music/Rock/a.flac"), p.downloads.map { it.entry.href })
        assertEquals(File(root, "Rock/a.flac"), p.downloads[0].target)
        assertEquals(rock, p.downloads[0].folderHref)
    }

    @Test
    fun localMissingDownloads() {
        val h = "/music/Rock/a.flac"
        val p = build(mapOf(rock to listOf(file(h))), listOf(led(h, rock)), exists = { false })
        assertEquals(1, p.downloads.size)
    }

    @Test
    fun etagChangeDownloads() {
        val h = "/music/Rock/a.flac"
        val p = build(mapOf(rock to listOf(file(h, etag = "\"e2\""))), listOf(led(h, rock)))
        assertEquals(1, p.downloads.size)
    }

    @Test
    fun noEtagSizeOrMtimeChangeDownloads() {
        val h = "/music/Rock/a.flac"
        assertEquals(1, build(mapOf(rock to listOf(file(h, size = 11, etag = null))), listOf(led(h, rock, etag = null))).downloads.size)
        assertEquals(1, build(mapOf(rock to listOf(file(h, etag = null, mod = "Tue, 02 Jan 2026 00:00:00 GMT"))), listOf(led(h, rock, etag = null))).downloads.size)
    }

    @Test
    fun sameIsSkipped() {
        val h = "/music/Rock/a.flac"
        val p = build(mapOf(rock to listOf(file(h))), listOf(led(h, rock)))
        assertTrue(p.downloads.isEmpty())
        assertTrue(p.orphan.isEmpty())
        assertTrue(p.unorphan.isEmpty())
    }

    @Test
    fun goneFromServerIsOrphaned() {
        val p = build(mapOf(rock to emptyList()), listOf(led("/music/Rock/old.flac", rock)))
        assertEquals(listOf("/music/Rock/old.flac"), p.orphan)
    }

    @Test
    fun aLedgerCopyAtAnotherPathDoesNotSkipTheDownload() {
        // The files were synced to the other storage: the version recorded is not of the copy at this target
        val h = "/music/Rock/a.flac"
        val other = led(h, rock).also { it.localPath = "/internal/Music/WebDAV/Rock/a.flac" }
        // Not the same tree on the other storage: download here, and the old copy goes when the new one is complete
        assertEquals(File(other.localPath!!), build(mapOf(rock to listOf(file(h))), listOf(other)).downloads.single().replaces)
    }

    @Test
    fun aCopyOnTheOtherStorageIsLeftToMove() {
        // Save To changed: the file is on the other storage, same tree. Move Synced Files moves it; no second copy
        val h = "/music/Rock/a.flac"
        val onInternal = led(h, rock).also { it.localPath = "/internal/Music/Rock/a.flac" }
        val p = SyncPlan.build(mapOf(rock to listOf(file(h))), mapOf(h to onInternal), setOf(rock), File("/sd/Music"), base,
            otherRoot = File("/internal/Music")) { true }
        assertTrue(p.downloads.isEmpty())
    }

    @Test
    fun aCopyOnInternalWithANameThatFatChangesIsLeftToMove() {
        // To the SD card: the target has the FAT name, and Move gives the copy that same name
        val h = "/music/Rock/02%20What%3F.mp3"
        val onInternal = led(h, rock).also { it.localPath = "/internal/Music/Rock/02 What?.mp3" }
        val p = SyncPlan.build(mapOf(rock to listOf(file(h))), mapOf(h to onInternal), setOf(rock), File("/sd/Music"), base,
            safeNames = true, otherRoot = File("/internal/Music")) { true }
        assertTrue(p.downloads.isEmpty())
    }

    @Test
    fun aFatNamedCopyOnTheSdCardIsLeftToMoveWhenSaveToIsInternalAgain() {
        // Synced to the SD card as "02 What_.mp3"; the internal target is "02 What?.mp3": the same file, not a new one
        val h = "/music/Rock/02%20What%3F.mp3"
        val onSd = led(h, rock).also { it.localPath = "/sd/Music/Rock/02 What_.mp3" }
        val p = SyncPlan.build(mapOf(rock to listOf(file(h))), mapOf(h to onSd), setOf(rock), File("/internal/Music"), base,
            safeNames = false, otherRoot = File("/sd/Music")) { true }
        assertTrue(p.downloads.isEmpty())
    }

    @Test
    fun aChangedCopyOnTheOtherStorageDownloadsAndReplacesIt() {
        val h = "/music/Rock/a.flac"
        val onInternal = led(h, rock).also { it.localPath = "/internal/Music/Rock/a.flac" }
        val p = SyncPlan.build(mapOf(rock to listOf(file(h, etag = "\"e2\""))), mapOf(h to onInternal), setOf(rock), File("/sd/Music"), base,
            otherRoot = File("/internal/Music")) { it.path.startsWith("/internal/") } // the old copy is there, the target not yet
        assertEquals(File("/internal/Music/Rock/a.flac"), p.downloads.single().replaces)
    }

    @Test
    fun aMissingCopyOnTheOtherStorageDownloads() {
        // The SD card with the old copies is out: get the file again here, and drop the record of the old one
        val h = "/music/Rock/a.flac"
        val onSd = led(h, rock).also { it.localPath = "/sd/Music/Rock/a.flac" }
        val p = SyncPlan.build(mapOf(rock to listOf(file(h))), mapOf(h to onSd), setOf(rock), File("/internal/Music"), base,
            otherRoot = File("/sd/Music")) { false } // the card is out, and the file is not on internal storage yet
        assertEquals(File("/sd/Music/Rock/a.flac"), p.downloads.single().replaces)
    }

    @Test
    fun aDownloadToItsOwnPathReplacesNothing() {
        val h = "/music/Rock/a.flac"
        assertNull(build(mapOf(rock to listOf(file(h, etag = "\"e2\""))), listOf(led(h, rock))).downloads.single().replaces)
    }

    @Test
    fun twoNamesThatShareAFatFileAreDownloadedOnce() {
        val a = "/music/A:/a.flac"
        val b = "/music/A?/a.flac"
        val p = SyncPlan.build(mapOf("/music/A:/" to listOf(file(a)), "/music/A?/" to listOf(file(b))), emptyMap(), setOf("/music/A:/", "/music/A?/"), root, base, safeNames = true) { false }
        assertEquals(1, p.downloads.size)
    }

    @Test
    fun namesThatDifferOnlyInCaseShareAFatFile() {
        val a = "/music/Rock/Track.flac"
        val b = "/music/Rock/track.flac"
        val p = SyncPlan.build(mapOf(rock to listOf(file(a), file(b))), emptyMap(), setOf(rock), root, base, safeNames = true) { false }
        assertEquals(1, p.downloads.size)
        // and on a file system that tells them apart, both are downloaded
        val q = SyncPlan.build(mapOf(rock to listOf(file(a), file(b))), emptyMap(), setOf(rock), root, base, safeNames = false) { false }
        assertEquals(2, q.downloads.size)
    }

    @Test
    fun aFileOfAnUntickedFolderThatAKeptFolderSharesIsNotRemoved() {
        val kept = "/music/A?/a.flac"
        val dropped = "/music/A:/a.flac"
        val shared = localFileFor(kept, base, root, safeNames = true)!!.path
        val item = SyncLedger.LedgerItem(dropped, "/music/A:/", "music", shared, 10, "\"e1\"|Mon, 01 Jan 2026 00:00:00 GMT", false)
        val p = SyncPlan.build(mapOf("/music/A?/" to listOf(file(kept))), mapOf(dropped to item), setOf("/music/A?/"), root, base, safeNames = true) { true }
        assertTrue(p.remove.isEmpty())
    }

    @Test
    fun untickedFolderItemsRemoved() {
        val p = build(mapOf(rock to emptyList()), listOf(led("/music/Jazz/x.flac", jazz)), ticked = setOf(rock))
        assertEquals(listOf("/music/Jazz/x.flac"), p.remove)
        assertTrue(p.orphan.isEmpty())
    }

    @Test
    fun nothingTickedRemovesEverything() {
        val p = build(emptyMap(), listOf(led("/music/Rock/a.flac", rock), led("/music/Jazz/x.flac", jazz, orphaned = true)), ticked = emptySet())
        assertEquals(setOf("/music/Rock/a.flac", "/music/Jazz/x.flac"), p.remove.toSet())
        assertTrue(p.orphan.isEmpty())
        assertTrue(p.downloads.isEmpty())
    }

    @Test
    fun goneFromServerIsNeverRemoved() {
        val p = build(mapOf(rock to emptyList()), listOf(led("/music/Rock/old.flac", rock)))
        assertTrue(p.remove.isEmpty())
    }

    @Test
    fun staleStoredFolderUsesPath() {
        // Synced while Rock was ticked; now only Rock/Live is. A Live file deleted on the NAS is marked, not removed.
        val live = "/music/Rock/Live/"
        val p = build(mapOf(live to emptyList()), listOf(led("/music/Rock/Live/a.flac", rock), led("/music/Rock/b.flac", rock)), ticked = setOf(live))
        assertEquals(listOf("/music/Rock/Live/a.flac"), p.orphan)
        assertEquals(listOf("/music/Rock/b.flac"), p.remove)
    }

    @Test
    fun siblingWithSamePrefixIsNotCovered() {
        val p = build(mapOf(rock to emptyList()), listOf(led("/music/Rocksteady/x.flac", "/music/Rocksteady/")), ticked = setOf(rock))
        assertEquals(listOf("/music/Rocksteady/x.flac"), p.remove)
    }

    @Test
    fun unlistedTickedFolderIsNotOrphaned() {
        // The Jazz listing failed or was skipped: never treat its files as deleted.
        val p = build(mapOf(rock to emptyList()), listOf(led("/music/Jazz/x.flac", jazz)), ticked = setOf(rock, jazz))
        assertTrue(p.orphan.isEmpty())
        assertTrue(p.remove.isEmpty())
    }

    @Test
    fun alreadyOrphanedIsNotRepeated() {
        val p = build(mapOf(rock to emptyList()), listOf(led("/music/Rock/old.flac", rock, orphaned = true)))
        assertTrue(p.orphan.isEmpty())
    }

    @Test
    fun reappearedClearsOrphan() {
        val h = "/music/Rock/a.flac"
        val p = build(mapOf(rock to listOf(file(h))), listOf(led(h, rock, orphaned = true)))
        assertEquals(listOf(h), p.unorphan)
        assertTrue(p.downloads.isEmpty())
    }

    @Test
    fun rejectsDotDotPaths() {
        assertNull(localFileFor("/music/Rock/../../etc/x", base, root))
        assertNull(localFileFor("/music/Rock/%2E%2E/x", base, root))
        assertNull(localFileFor("/other/a.flac", base, root))
        assertNull(localFileFor("/music/", base, root))
        val p = build(mapOf(rock to listOf(file("/music/Rock/../../x.flac"))))
        assertTrue(p.downloads.isEmpty())
    }

    @Test
    fun decodesTargetNames() {
        assertEquals(File(root, "Björk/01 Joga.flac"), localFileFor("/music/Bj%C3%B6rk/01%20Joga.flac", base, root))
    }

    @Test
    fun bytesSumsDownloads() {
        val p = build(mapOf(rock to listOf(file("/music/Rock/a.flac", size = 100), file("/music/Rock/b.flac", size = 23))))
        assertEquals(123L, p.bytes)
    }

    @Test
    fun isUnderNeedsAFolderBoundary() {
        assertTrue(isUnder("/m/Rock/a.flac", "/m/Rock/"))
        assertTrue(isUnder("/m/Rock/a.flac", "/m/Rock")) // a server that sends no trailing slash
        assertFalse(isUnder("/m/Rock Pop/a.flac", "/m/Rock"))
        assertFalse(isUnder("/m/Rock", "/m/Rock"))
        assertTrue(isUnder("/m/M%C3%BAsica/a.flac", "/m/Música/"))
        assertTrue(isUnder("/m/M%c3%basica/a.flac", "/m/M%C3%BAsica"))
    }

    @Test
    fun fatSafeReplacesWhatFatDoesNotTake() {
        assertEquals("Why_ Not_.flac", fatSafe("Why: Not?.flac"))
        assertEquals("a_b_c_d_e_f_g_h", fatSafe("a<b>c\"d|e*f\\g\u0001h"))
        assertEquals("Song", fatSafe("Song. ")) // no dot or space at the end
        assertEquals("_", fatSafe("..."))
        assertEquals("Björk - Jóga ♪.flac", fatSafe("Björk - Jóga ♪.flac")) // any other name is as it was
    }

    @Test
    fun localFileForKeepsNamesUnlessTheDestinationIsFat() {
        val root = File("/sd/Music")
        assertEquals(File(root, "Rock/Why: Not?.flac"), localFileFor("/music/Rock/Why%3A%20Not%3F.flac", "/music/", root))
        assertEquals(File(root, "Rock/Why_ Not_.flac"), localFileFor("/music/Rock/Why%3A%20Not%3F.flac", "/music/", root, safeNames = true))
        // a folder name too
        assertEquals(File(root, "AC_DC/x.flac"), localFileFor("/music/AC%3ADC/x.flac", "/music/", root, safeNames = true))
        // the dot segments are still refused
        assertNull(localFileFor("/music/%2E%2E/x.flac", "/music/", root, safeNames = true))
    }

    @Test
    fun movedPathUsesTheFatNamesOnlyWhenAsked() {
        val from = File("/internal/Music")
        val to = File("/sd/Music")
        assertEquals(File(to, "Rock/Why: Not.flac"), movedPath("/internal/Music/Rock/Why: Not.flac", from, to))
        assertEquals(File(to, "Rock/Why_ Not.flac"), movedPath("/internal/Music/Rock/Why: Not.flac", from, to, safeNames = true))
    }

    @Test
    fun theSafeNameIsTheOneThatIsLookedFor() {
        val root = File("/sd/Music")
        val e = DavEntry("/music/Rock/Why%3A.flac", false, 10, "\"e1\"", "m")
        val plan = SyncPlan.build(
            mapOf("/music/Rock/" to listOf(e)), emptyMap(), setOf("/music/Rock/"), root, "/music/", safeNames = true
        ) { false }
        assertEquals(File(root, "Rock/Why_.flac"), plan.downloads.single().target)
    }
    @Test
    fun aFileThatTheSyncDidNotMakeIsNeverOverwritten() {
        // The user's own file at the target, another size than on the server: skipped, not downloaded
        val h = "/music/Rock/a.flac"
        val target = File(root, "Rock/a.flac").path
        val p = build(mapOf(rock to listOf(file(h, size = 10))), exists = { it.path == target }, size = { 99L })
        assertTrue(p.downloads.isEmpty())
        assertEquals(listOf(h), p.conflicts)
        assertTrue(p.adopt.isEmpty())
    }

    @Test
    fun aFileWithTheServerSizeIsTakenOverNotDownloaded() {
        // For example after Log Out / Reset: the copies are there, untracked, with the server's size
        val h = "/music/Rock/a.flac"
        val target = File(root, "Rock/a.flac").path
        val p = build(mapOf(rock to listOf(file(h, size = 10))), exists = { it.path == target }, size = { 10L })
        assertTrue(p.downloads.isEmpty())
        assertEquals(File(root, "Rock/a.flac"), p.adopt.single().target)
    }

    @Test
    fun aFileWhoseRecordedCopyIsGoneIsTakenOver() {
        // A crash after a move deleted the source and before the save: the record names the gone copy, the file is here
        val h = "/music/Rock/a.flac"
        val gone = led(h, rock).also { it.localPath = "/elsewhere/a.flac" }
        val p = build(mapOf(rock to listOf(file(h, size = 10))), listOf(gone), exists = { it.path == File(root, "Rock/a.flac").path }, size = { 10L })
        assertEquals(File(root, "Rock/a.flac"), p.adopt.single().target)
    }

    @Test
    fun aFolderIsNeverTakenOverAndAFileInThePathSkipsOnlyThatFile() {
        val h = "/music/Rock/a.flac"
        // A folder at the target, of the right "size": not taken over
        val p = build(mapOf(rock to listOf(file(h, size = 4096))), exists = { it.path == File(root, "Rock/a.flac").path }, size = { 4096L }, isFile = { false })
        assertTrue(p.adopt.isEmpty() && p.downloads.isEmpty())
        // A local file "Rock" where the server has a folder: only this file is skipped
        val q = build(mapOf(rock to listOf(file(h))), exists = { it.path == File(root, "Rock").path }, isFile = { it.path == File(root, "Rock").path })
        assertEquals(listOf(h), q.conflicts)
    }

    @Test
    fun aChangedFileOfThisSyncIsStillReplaced() {
        val h = "/music/Rock/a.flac"
        assertEquals(1, build(mapOf(rock to listOf(file(h, etag = "\"e2\""))), listOf(led(h, rock))).downloads.size)
    }

}

class PruneEmptyDirsTest {
    @org.junit.Test fun removesEmptyParentsButNotTheRootOrAFolderWithFiles() {
        val root = java.nio.file.Files.createTempDirectory("music").toFile()
        val album = File(root, "Artist/Album/Disc 1").apply { mkdirs() }
        File(root, "Artist/Other").mkdirs()
        File(root, "Artist/Other/keep.mp3").writeText("x")
        val gone = File(album, "a.mp3")
        pruneEmptyDirs(gone, listOf(root))
        org.junit.Assert.assertFalse(File(root, "Artist/Album").exists())
        org.junit.Assert.assertTrue(File(root, "Artist").exists())
        val only = File(root, "Solo/x.mp3").apply { parentFile.mkdirs() }
        pruneEmptyDirs(only, listOf(root))
        org.junit.Assert.assertFalse(File(root, "Solo").exists())
        org.junit.Assert.assertTrue(root.exists())
        root.deleteRecursively()
    }

}
