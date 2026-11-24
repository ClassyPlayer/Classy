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
                    val first = sortedNatural(inFirst) { it.second.file.name }.first().second
                    result += Entry(directory.name, first, directory, items.map { it.second.file }, true, directory)
                } else {
                    val sorted = sortedNatural(items) { it.second.file.name }
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
        val byChild = LinkedHashMap<String, MutableList<SongItem>>()
        val direct = ArrayList<SongItem>()
        for (song in library) {
            val rel = relative(seriesPath, song.file) ?: continue
            val cut = rel.indexOf(SEP)
            if (cut < 0) direct.add(song)
            else byChild.getOrPut(rel.substring(0, cut)) { ArrayList() }.add(song)
        }
        val result = ArrayList<Entry>()
        for ((name, items) in byChild) {
            val sorted = sortedNatural(items) { it.file.name }
            result += Entry(name, sorted.first(), File(series, name), sorted.map { it.file }, false, series)
        }
        if (direct.isNotEmpty()) {
            val sorted = sortedNatural(direct) { it.file.name }
            result += Entry("Other", sorted.first(), series, sorted.map { it.file }, false, series)
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

    /** [items] sorted by the natural key of [name], each key made one time. */
    private fun <T> sortedNatural(items: List<T>, name: (T) -> String): List<T> =
        items.map { naturalKey(name(it)) to it }.sortedBy { it.first }.map { it.second }

    private fun naturalKey(value: String): String = value.lowercase(Locale.US).replace(DIGITS) { m ->
        m.value.toLongOrNull()?.let { String.format(Locale.US, "%012d", it) } ?: m.value
    }
}
