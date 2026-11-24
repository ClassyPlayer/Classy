package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import java.io.File
import java.util.Locale

/**
 * Folder-based audiobook Cover Flow index. A directory containing child book directories is a series;
 * a directory containing audio but no child book directories is one standalone book. Loose files at
 * an Audiobooks root remain individual entries. Playback files are always sourced from the scanned library.
 *
 * The index is built from the paths of the scanned library in one pass: it never lists folders on the SD card
 * (slow on the player) and sorts with each sort key made one time.
 */
object AudiobookSeriesIndex {
    data class Entry(
        val title: String,
        val representative: SongItem,
        val folder: File?,
        val audioFiles: List<File>,
        val isSeries: Boolean,
        val seriesFolder: File?
    ) {
        fun coverItem(): SongItem = SongItem(
            representative.file, title, representative.artist, title,
            representative.year, representative.genre, representative.albumArtist
        )
    }

    private val DIGITS = Regex("\\d+")
    private val LEADING_NUMBER = Regex("(\\d{1,4})")
    private val SEP = File.separatorChar

    private fun dirPath(dir: File): String = dir.absoluteFile.path.trimEnd(SEP)

    /** The path of [file] below the directory [dirPath] (no leading separator), or null when it is not inside it. */
    private fun relative(dirPath: String, file: File): String? {
        val p = file.absoluteFile.path
        return if (p.length > dirPath.length + 1 && p.startsWith(dirPath) && p[dirPath.length] == SEP) {
            p.substring(dirPath.length + 1)
        } else null
    }

    private fun bookOrder(): Comparator<String> =
        compareBy<String> { leadingNumber(it) ?: Int.MAX_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it }

    fun rootEntries(
        library: List<SongItem>, roots: List<File>, lastSeriesPath: String?, lastPlayedPath: String?
    ): List<Entry> {
        val result = ArrayList<Entry>()
        for (root in roots) {
            val rootPath = dirPath(root)
            val direct = ArrayList<SongItem>()
            // Top-level folder name -> (path below that folder, song)
            val byTop = LinkedHashMap<String, MutableList<Pair<String, SongItem>>>()
            for (song in library) {
                val rel = relative(rootPath, song.file) ?: continue
                val cut = rel.indexOf(SEP)
                if (cut < 0) direct.add(song)
                else byTop.getOrPut(rel.substring(0, cut)) { ArrayList() }.add(rel.substring(cut + 1) to song)
            }
            for (song in direct) {
                result += Entry(song.title.ifBlank { song.file.nameWithoutExtension }, song, null,
                    listOf(song.file), false, null)
            }
            for ((name, items) in byTop) {
                val directory = File(root, name)
                val bookNames = LinkedHashSet<String>()
                for ((rel, _) in items) {
                    val cut = rel.indexOf(SEP)
                    if (cut > 0) bookNames.add(rel.substring(0, cut))
                }
                if (bookNames.isNotEmpty()) {
                    val firstBook = bookNames.sortedWith(bookOrder()).first()
                    val inFirst = items.filter { it.first.startsWith(firstBook + SEP) }
                    val first = sortedByPath(inFirst) { it.first.substring(firstBook.length + 1) }.first().second
                    result += Entry(directory.name, first, directory, items.map { it.second.file }, true, directory)
                } else {
                    val sorted = sortedByPath(items) { it.first }
                    result += Entry(directory.name, sorted.first().second, directory,
                        sorted.map { it.second.file }, false, null)
                }
            }
        }
        return result.sortedWith(compareBy<Entry> {
            val isRecentSeries = it.isSeries && (it.folder?.absolutePath == lastSeriesPath ||
                (!lastPlayedPath.isNullOrEmpty() && isUnder(File(lastPlayedPath), it.folder ?: File("/"))))
            if (isRecentSeries) 0 else 1
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** Finds the top-level series folder containing [file], if its folder has book subdirectories. */
    fun seriesFolderForFile(library: List<SongItem>, roots: List<File>, file: File): File? {
        for (root in roots) {
            val rel = relative(dirPath(root), file) ?: continue
            val cut = rel.indexOf(SEP)
            if (cut <= 0) continue
            val top = File(root, rel.substring(0, cut))
            val topPath = dirPath(top)
            val hasBookFolders = library.any { song ->
                val r = relative(topPath, song.file)
                r != null && r.indexOf(SEP) > 0
            }
            if (hasBookFolders) return top
        }
        return null
    }

    fun bookEntries(library: List<SongItem>, series: File, lastBookPath: String?): List<Entry> {
        val seriesPath = dirPath(series)
        // Book folder name -> (path below the book folder, song): a book is ordered by those paths
        val byChild = LinkedHashMap<String, MutableList<Pair<String, SongItem>>>()
        val direct = ArrayList<Pair<String, SongItem>>()
        for (song in library) {
            val rel = relative(seriesPath, song.file) ?: continue
            val cut = rel.indexOf(SEP)
            if (cut < 0) direct.add(song.file.name to song)
            else byChild.getOrPut(rel.substring(0, cut)) { ArrayList() }.add(rel.substring(cut + 1) to song)
        }
        val result = ArrayList<Entry>()
        for ((name, items) in byChild) {
            val sorted = sortedByPath(items) { it.first }
            result += Entry(name, sorted.first().second, File(series, name), sorted.map { it.second.file }, false, series)
        }
        if (direct.isNotEmpty()) {
            val sorted = sortedByPath(direct) { it.first }
            result += Entry("Other", sorted.first().second, series, sorted.map { it.second.file }, false, series)
        }
        return result.sortedWith(compareBy<Entry> {
            if (!lastBookPath.isNullOrEmpty() && it.audioFiles.any { file -> file.absolutePath == lastBookPath }) 0 else 1
        }.thenBy { leadingNumber(it.title) ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** Folders between an Audiobooks root and [file] (excluded), outermost first: the series and the book folder. */
    fun foldersAbove(roots: List<File>, file: File): List<File> {
        for (root in roots) {
            if (!isUnder(file, root)) continue
            val out = ArrayList<File>()
            var dir = file.absoluteFile.parentFile
            val rootPath = dirPath(root)
            while (dir != null && dir.absoluteFile.path.trimEnd(SEP) != rootPath) {
                out.add(0, dir)
                dir = dir.parentFile
            }
            return out
        }
        return emptyList()
    }

    private fun isUnder(file: File, directory: File): Boolean = relative(dirPath(directory), file) != null

    private fun leadingNumber(value: String): Int? = LEADING_NUMBER.find(value)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * One ordering key for every file of a book: its folder first (so Disc 2 follows Disc 1), then files with a
     * track number (by number) before files without one, then the natural file name. Every pair compares by the
     * same key, so the order is transitive.
     */
    private class OrderKey(val dir: String, val untagged: Int, val track: Int, val name: String)

    private val ORDER: Comparator<OrderKey> =
        compareBy<OrderKey>({ it.dir }, { it.untagged }, { it.track }, { it.name })

    private fun orderKey(relativePath: String, track: Int): OrderKey {
        val cut = relativePath.lastIndexOf(SEP)
        val dir = if (cut < 0) "" else naturalKey(relativePath.substring(0, cut))
        return OrderKey(dir, if (track > 0) 0 else 1, if (track > 0) track else 0, naturalKey(relativePath.substring(cut + 1)))
    }

    /** [items] in book order by the path that [path] gives (relative to the book folder), each key made one time. */
    private fun <T> sortedByPath(items: List<T>, path: (T) -> String): List<T> =
        items.map { orderKey(path(it), 0) to it }.sortedWith { a, b -> ORDER.compare(a.first, b.first) }.map { it.second }

    /**
     * [files] of the book folder [base] in play order: the order of the index, plus the track numbers that
     * [trackNumber] gives (0 = none), so the queue keeps the folder-aware sequence.
     */
    fun orderedFiles(files: List<File>, base: File?, trackNumber: (File) -> Int): List<File> {
        val basePath = base?.let { dirPath(it) }
        return files.map { f ->
            orderKey((basePath?.let { relative(it, f) }) ?: f.name, trackNumber(f)) to f
        }.sortedWith { a, b -> ORDER.compare(a.first, b.first) }.map { it.second }
    }

    private fun naturalKey(value: String): String = value.lowercase(Locale.US).replace(DIGITS) { m ->
        m.value.toLongOrNull()?.let { String.format(Locale.US, "%012d", it) } ?: m.value
    }
}
