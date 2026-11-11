package com.themoon.y1.games

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/** One iPod Click Wheel game: [bundleDir] has the Executables folder; [iconFile] is null when it has no art. */
data class GameItem(val title: String, val bundleDir: File, val iconFile: File?)

/**
 * The games in the Games folders: game folders (decrypted, with Executables/ *.bin), and .ipg files, which it
 * unpacks into a folder of the same name. Pure file rules, so the tests can run it on a temp folder.
 *
 * The folder of an .ipg counts only when it is complete: a marker file ([MARKER]) is written after the whole .ipg
 * is in it. A folder without the marker (unpacked by an older version, or cut off: files of 0 bytes) is checked
 * once against the .ipg, and unpacked again over itself when a file is missing or short. The folder is never
 * deleted: older versions kept the game saves in it (.fliwheel-saves), which the core moves to [saveDir].
 */
object GameLibrary {
    private const val TAG = "GameLibrary"
    private const val EXECUTABLES = "Executables"
    const val MARKER = ".classy-unpacked"
    const val SAVES = "GameData_RW"
    private val UNSAFE_ID_CHARS = Regex("[^A-Za-z0-9._ -]")
    private val GUID = Regex("<key>GUID</key>\\s*<string>([^<]+)</string>")

    /**
     * The games in [roots], sorted by title. A game in two places (the same folder by two mount paths, or the same
     * title on both cards) shows once.
     *
     * The ready games come first ([onProgress] with null), then each .ipg that needs unpacking ([onProgress] with
     * its name, before it unpacks: that can take a while). [onFailed] gets the name of each .ipg that could not be
     * unpacked or has no game in it: the list says so, instead of leaving it out silently.
     */
    fun scan(
        roots: List<File>,
        onFailed: (ipgName: String) -> Unit = {},
        onProgress: (games: List<GameItem>, unpacking: String?) -> Unit = { _, _ -> }
    ): List<GameItem> {
        val games = ArrayList<GameItem>()
        val seenPaths = HashSet<String>()
        val seenTitles = HashSet<String>()
        fun add(item: GameItem?) {
            if (item == null) return
            val title = item.title.trim().lowercase(Locale.US)
            if (!seenPaths.add(canonical(item.bundleDir)) || !seenTitles.add(title)) return
            games += item
        }
        fun sorted() = games.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

        val pending = ArrayList<Pair<File, File>>() // .ipg and its folder
        val seenRoots = HashSet<String>()
        for (root in roots) {
            if (!root.isDirectory || !seenRoots.add(canonical(root))) continue
            val files = root.listFiles()?.sortedBy { it.name } ?: continue
            // The folder name of each .ipg, and if that folder has the whole .ipg
            val unpacked = HashMap<String, Boolean>()
            for (ipg in files) {
                if (!ipg.isFile || !ipg.name.lowercase(Locale.US).endsWith(".ipg")) continue
                val target = File(root, ipg.name.substringBeforeLast('.'))
                val ready = isUnpacked(ipg, target)
                unpacked[target.name] = ready
                if (!ready) pending += ipg to target
            }
            for (dir in files) {
                if (dir.isDirectory && unpacked[dir.name] != false) add(parseGameDir(dir))
            }
        }
        onProgress(sorted(), null)

        for ((ipg, target) in pending) {
            onProgress(sorted(), target.name)
            val item = if (unpack(ipg, target)) parseGameDir(target) else null
            if (item == null) onFailed(ipg.name) else add(item)
        }
        return sorted()
    }

    /** A game folder: Executables/ with a .bin in [dir], or in a folder inside it (some .ipg files have Game/Game/). */
    fun parseGameDir(dir: File): GameItem? {
        if (!dir.isDirectory) return null
        val exe = File(dir, EXECUTABLES)
        if (!exe.isDirectory) {
            val inner = dir.listFiles()?.sortedBy { it.name }?.firstOrNull { File(it, EXECUTABLES).isDirectory }
            return inner?.let { parseGameDir(it) }
        }
        // An empty .bin is a copy that was cut off
        val hasBin = exe.listFiles()?.any { it.isFile && it.length() > 0 && it.name.lowercase(Locale.US).endsWith(".bin") } == true
        if (!hasBin) return null
        return GameItem(dir.name, dir, findIcon(dir) ?: dir.parentFile?.let { findIcon(it) })
    }

    /** The bundle folder to give the core: [dir], or the folder inside it that has Executables/. */
    fun bundleDir(dir: File): File =
        if (File(dir, EXECUTABLES).isDirectory) dir
        else dir.listFiles()?.sortedBy { it.name }?.firstOrNull { File(it, EXECUTABLES).isDirectory } ?: dir

    /** Where the game in [bundle] keeps its saves, as on an iPod: `GameData_RW/<GameID>` next to the games in [roots]. */
    fun saveDir(bundle: File, roots: List<File>): File {
        val path = canonical(bundle)
        val root = roots.firstOrNull { path.startsWith(canonical(it) + File.separator) } ?: bundle.parentFile ?: bundle
        return File(File(root, SAVES), gameId(bundle))
    }

    /** The GUID in the game's Manifest.plist, which stays the same however its folder is named. */
    private fun gameId(bundle: File): String {
        val guid = try {
            GUID.find(File(bundle, "Manifest.plist").readText())?.groupValues?.get(1)
        } catch (e: IOException) {
            null
        }
        // "." or ".." (or nothing) would put the saves in GameData_RW itself or above it: the folder name then
        return safeId(guid)?.takeIf { it.any { c -> c != '.' } } ?: safeId(bundle.name)?.takeIf { it.any { c -> c != '.' } } ?: "_"
    }

    private fun safeId(name: String?): String? = name?.trim()?.replace(UNSAFE_ID_CHARS, "_")

    /**
     * The art of a game, in this order. An empty file (cut off) is no art. The iPod's own .raw.lcd5 images are
     * not used: Android cannot decode them.
     */
    fun findIcon(dir: File): File? =
        listOf("iTunesArtwork", "icon.png", "cover.jpg").map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0 }

    /**
     * True when [target] has the whole .ipg [ipg]: its marker is for this .ipg, or (no marker yet) each file of
     * the .ipg is there with its size. The check writes the marker, so it reads the .ipg only once.
     */
    fun isUnpacked(ipg: File, target: File): Boolean {
        if (!target.isDirectory) return false
        val marker = File(target, MARKER)
        if (marker.isFile) {
            // The card can go during the scan: an unreadable marker means "check the .ipg" (an exception here would
            // end the scan thread, and with it the app)
            try {
                return marker.readText() == stamp(ipg)
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the marker of ${target.name}", e)
            }
        }
        val complete = try {
            ZipFile(ipg).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.all { e ->
                    val f = File(target, e.name)
                    f.isFile && (e.size < 0 || f.length() == e.size)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read ${ipg.name}", e)
            false
        }
        if (complete) writeMarker(ipg, target)
        return complete
    }

    /**
     * Unpacks the .ipg (a zip) [ipg] into [target], over the files that are there, and writes the marker at the
     * end. An entry that would land outside [target] ("../", zip slip) stops the unpacking. False when it failed.
     */
    fun unpack(ipg: File, target: File): Boolean {
        val base = canonical(target) + File.separator
        return try {
            target.mkdirs()
            File(target, MARKER).delete()
            ZipFile(ipg).use { zip ->
                val buffer = ByteArray(32 * 1024)
                for (entry in zip.entries()) {
                    val out = File(target, entry.name)
                    if (!canonical(out).startsWith(base)) throw IOException("Entry outside the game folder: ${entry.name}")
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { o ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                o.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
            writeMarker(ipg, target)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not unpack ${ipg.name}", e)
            false
        }
    }

    /** The .ipg that the marker is for: a new .ipg of the same name is unpacked again. */
    private fun stamp(ipg: File): String = "${ipg.length()}:${ipg.lastModified()}"

    private fun writeMarker(ipg: File, target: File) {
        try {
            File(target, MARKER).writeText(stamp(ipg))
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the marker of ${target.name}", e)
        }
    }

    private fun canonical(f: File): String = try {
        f.canonicalPath
    } catch (_: IOException) {
        f.absolutePath
    }
}
