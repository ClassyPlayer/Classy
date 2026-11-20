package com.themoon.y1.photos

import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * One photo in a Photos folder. [date] = when it was taken (EXIF DateTimeOriginal, ms) or 0; [added] = when a scan
 * found it first (ms; the file time for the photos of the first scan).
 */
class PhotoItem(
    val path: String,
    val size: Long,
    val modified: Long,
    val date: Long,
    val orientation: Int,
    val added: Long
) {
    val file: File get() = File(path)
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/')

    /** The date to sort by and to show: the EXIF date, else the file time. */
    val sortDate: Long get() = if (date > 0) date else modified

    /** This version of the file: a photo replaced under the same name gets new thumbnails and art. */
    val version: String get() = "$path|$size|$modified"

    /** The same file, unchanged (the cached EXIF data is still right). */
    fun isSameFile(size: Long, modified: Long): Boolean = this.size == size && this.modified == modified
}

/**
 * The photos of the Photos folders, the sets that the Photos lists show, and the index file that keeps the EXIF data
 * between scans. No Android types: the rules are unit-tested.
 *
 * A set is named by a key ([ALL], [RECENT], [month], [inFolder], [underFolder]): the Photos grid and the viewer get
 * the same photos in the same order from it.
 */
object PhotoIndex {
    /** All Photos: oldest first (the date taken, else the file time), then by name. */
    const val ALL = "all"
    /** Recently Added: the photos found in the last [RECENT_DAYS] days, newest first. */
    const val RECENT = "recent"
    const val RECENT_DAYS = 30

    private const val MONTH = "m:"
    private const val IN_FOLDER = "d:"
    private const val UNDER_FOLDER = "t:"

    /** A month of By Date: [month] 1..12. */
    fun month(year: Int, month: Int): String = MONTH + year + "-" + month

    /** The photos right in [dir] (a folder in Folders: its Photos row). */
    fun inFolder(dir: String): String = IN_FOLDER + dir.trimEnd('/')

    /** The photos in [dir] and its folders (Slideshow in Folders). */
    fun underFolder(dir: String): String = UNDER_FOLDER + dir.trimEnd('/')

    /** (year, month) of a [month] key, else null. */
    fun parseMonth(key: String): Pair<Int, Int>? {
        if (!key.startsWith(MONTH)) return null
        val parts = key.removePrefix(MONTH).split('-')
        val y = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return y to m
    }

    /** The folder of an [inFolder] or [underFolder] key, else null. */
    fun folderOf(key: String): String? = when {
        key.startsWith(IN_FOLDER) -> key.removePrefix(IN_FOLDER)
        key.startsWith(UNDER_FOLDER) -> key.removePrefix(UNDER_FOLDER)
        else -> null
    }

    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    /** True for a file that the Photos screens show: a picture type, not hidden, not a macOS "._" file. */
    fun isPhoto(name: String): Boolean {
        if (name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in EXTENSIONS
    }

    /** True for a JPEG, the only type with EXIF data here. */
    fun hasExif(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "jpg" || ext == "jpeg"
    }

    fun sorted(photos: List<PhotoItem>): List<PhotoItem> =
        photos.sortedWith(compareBy<PhotoItem> { it.sortDate }.thenBy { it.name.lowercase() }.thenBy { it.path })

    /** The photos of the set [key], in the order the grid shows them. [now] and [zone]: for Recently Added and By Date. */
    fun select(photos: List<PhotoItem>, key: String, now: Long, zone: TimeZone = TimeZone.getDefault()): List<PhotoItem> {
        if (key == ALL) return sorted(photos)
        if (key == RECENT) {
            val from = now - RECENT_DAYS * DAY_MS
            return photos.filter { it.added >= from }
                .sortedWith(compareByDescending<PhotoItem> { it.added }.thenBy { it.name.lowercase() }.thenBy { it.path })
        }
        parseMonth(key)?.let { (y, m) ->
            val cal = Calendar.getInstance(zone)
            return sorted(photos.filter { yearMonth(it.sortDate, cal) == y * 100 + m })
        }
        if (key.startsWith(IN_FOLDER)) {
            val dir = key.removePrefix(IN_FOLDER)
            return sorted(photos.filter { it.dir == dir })
        }
        if (key.startsWith(UNDER_FOLDER)) {
            val prefix = key.removePrefix(UNDER_FOLDER) + "/"
            return sorted(photos.filter { it.path.startsWith(prefix) })
        }
        return emptyList()
    }

    private fun yearMonth(ms: Long, cal: Calendar): Int {
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH) + 1
    }

    /** By Date: the years with photos and their photo counts, oldest first. */
    fun years(photos: List<PhotoItem>, zone: TimeZone = TimeZone.getDefault()): List<Pair<Int, Int>> {
        val cal = Calendar.getInstance(zone)
        return photos.groupingBy { yearMonth(it.sortDate, cal) / 100 }.eachCount().toSortedMap().map { it.key to it.value }
    }

    /** The months (1..12) of [year] with photos and their photo counts, January first. */
    fun months(photos: List<PhotoItem>, year: Int, zone: TimeZone = TimeZone.getDefault()): List<Pair<Int, Int>> {
        val cal = Calendar.getInstance(zone)
        return photos.map { yearMonth(it.sortDate, cal) }.filter { it / 100 == year }
            .groupingBy { it % 100 }.eachCount().toSortedMap().map { it.key to it.value }
    }

    /**
     * The folders right in [dir] that have photos (in them or deeper), A to Z, each with the number of those photos.
     * A folder without photos does not show.
     */
    fun subfolders(photos: List<PhotoItem>, dir: String): List<Pair<String, Int>> {
        val prefix = dir.trimEnd('/') + "/"
        val counts = HashMap<String, Int>()
        for (p in photos) {
            if (!p.path.startsWith(prefix)) continue
            val rest = p.path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) continue
            val sub = prefix + rest.substring(0, slash)
            counts[sub] = (counts[sub] ?: 0) + 1
        }
        return counts.entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.key.substringAfterLast('/').lowercase() }.thenBy { it.key })
            .map { it.key to it.value }
    }

    /** The number of photos in [dir] and its folders. */
    fun countUnder(photos: List<PhotoItem>, dir: String): Int {
        val prefix = dir.trimEnd('/') + "/"
        return photos.count { it.path.startsWith(prefix) }
    }

    /**
     * The records of the last index that a scan of [roots] could not see: outside the roots (a microSD card that is not
     * mounted now) or in a folder that failed to list ([failed]). They stay in the index, so the photos keep their
     * found times and thumbnails until a scan sees them again.
     */
    fun notSeen(records: Collection<PhotoItem>, roots: List<String>, failed: List<String> = emptyList()): List<PhotoItem> {
        val walked = roots.map { it.trimEnd('/') + "/" }
        val notListed = failed.map { it.trimEnd('/') + "/" }
        return records.filter { item ->
            walked.none { item.path.startsWith(it) } || notListed.any { item.path.startsWith(it) }
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    // --- Index file: one photo a line, tab-separated (a path has no tab or line break on FAT) ---

    private const val HEADER = "classy-photos 2"

    fun encode(photos: List<PhotoItem>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (p in photos) {
            if (p.path.contains('\t') || p.path.contains('\n')) continue
            sb.append(p.path).append('\t').append(p.size).append('\t').append(p.modified).append('\t')
                .append(p.date).append('\t').append(p.orientation).append('\t').append(p.added).append('\n')
        }
        return sb.toString()
    }

    /** The photos of an index file; an unknown or broken file gives none (the next scan reads the EXIF again). */
    fun decode(text: String): List<PhotoItem> {
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return emptyList()
        val out = ArrayList<PhotoItem>()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            if (f.size != 6) continue
            val size = f[1].toLongOrNull() ?: continue
            val modified = f[2].toLongOrNull() ?: continue
            val date = f[3].toLongOrNull() ?: continue
            val orientation = f[4].toIntOrNull() ?: continue
            val added = f[5].toLongOrNull() ?: continue
            out += PhotoItem(f[0], size, modified, date, orientation, added)
        }
        return out
    }

    /** "2024:07:14 18:03:22" (EXIF) as local time in ms, or 0. */
    fun parseExifDate(value: String?, zone: TimeZone = TimeZone.getDefault()): Long {
        val v = value?.trim() ?: return 0
        if (v.length < 19) return 0
        return try {
            val f = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            f.timeZone = zone
            f.isLenient = false
            f.parse(v.substring(0, 19))?.time ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
