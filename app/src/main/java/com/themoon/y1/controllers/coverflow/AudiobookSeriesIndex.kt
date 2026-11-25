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
        val seriesFolder: File?,
        /** Shown under the title on the cover: the folder name (the series of a book), never the artist. */
        val subtitle: String = "",
        /** The book of the loose files of a series folder: its [title] is "Other", to translate where it shows. */
        val isOther: Boolean = false
    ) {
        fun coverItem(): SongItem = SongItem(
            representative.file, title, subtitle, title,
            representative.year, representative.genre, subtitle
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

    /** One book of a series folder: its name, the files below it (path below the book, song), and the file that is the book. */
    private class BookUnit(
        val name: String, val items: List<Pair<String, SongItem>>, val isFolder: Boolean, val isOther: Boolean = false
    )

    private fun isM4b(name: String): Boolean = name.endsWith(".m4b", ignoreCase = true)

    /**
     * The books in a folder, from its files [items] (path below the folder, song): each subfolder is a book, each
     * .m4b file directly in the folder is a book, and other files directly in the folder are one book "Other".
     * Null when the folder is one book: it has no subfolders and at most one .m4b file.
     */
    private fun booksOf(items: List<Pair<String, SongItem>>): List<BookUnit>? {
        val folders = LinkedHashMap<String, MutableList<Pair<String, SongItem>>>()
        val m4bFiles = ArrayList<Pair<String, SongItem>>()
        val others = ArrayList<Pair<String, SongItem>>()
        for (item in items) {
            val cut = item.first.indexOf(SEP)
            if (cut >= 0) folders.getOrPut(item.first.substring(0, cut)) { ArrayList() }
                .add(item.first.substring(cut + 1) to item.second)
            else if (isM4b(item.first)) m4bFiles.add(item) else others.add(item)
        }
        if (folders.isEmpty() && m4bFiles.size < 2) return null
        val books = ArrayList<BookUnit>()
        for ((name, list) in folders) books += BookUnit(name, list, true)
        for (file in m4bFiles) books += BookUnit(file.first.substringBeforeLast('.'), listOf(file), false)
        if (others.isNotEmpty()) books += BookUnit("Other", others, false, isOther = true)
        return books
    }

    /**
     * Goes down through folders that only hold one folder of books: Audiobooks/Jellyfin/<Author>/<Book> (the
     * layout of Jellyfin Sync) is the series <Author>, not one book "Jellyfin" of all the author's books. It stops
     * at a folder whose only subfolder holds files only (a book): Saga/Only Book stays the book "Only Book".
     */
    private fun descend(
        directory: File, items: List<Pair<String, SongItem>>
    ): Pair<File, List<Pair<String, SongItem>>> {
        var dir = directory
        var list = items
        while (true) {
            if (list.any { it.first.indexOf(SEP) < 0 }) break
            val sub = list.map { it.first.substringBefore(SEP) }.distinct().singleOrNull() ?: break
            val inner = list.map { it.first.substring(sub.length + 1) to it.second }
            if (inner.none { it.first.indexOf(SEP) >= 0 }) break
            dir = File(dir, sub)
            list = inner
        }
        return dir to list
    }

    /** The top folder below a root that holds [file] after [descend], and its files; null for a loose file. */
    private fun topOf(library: List<SongItem>, root: File, file: File): Pair<File, List<Pair<String, SongItem>>>? {
        val rel = relative(dirPath(root), file) ?: return null
        val cut = rel.indexOf(SEP)
        if (cut <= 0) return null
        val top = File(root, rel.substring(0, cut))
        val topPath = dirPath(top)
        val items = ArrayList<Pair<String, SongItem>>()
        for (song in library) {
            val r = relative(topPath, song.file) ?: continue
            items.add(r to song)
        }
        return descend(top, items)
    }

    private fun bookNameOrder(): Comparator<BookUnit> =
        compareBy<BookUnit> { leadingNumber(it.name) ?: Int.MAX_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

    private fun bookEntry(unit: BookUnit, parent: File, seriesFolder: File?): Entry {
        val sorted = sortedByPath(unit.items) { it.first }
        val folder = if (unit.isFolder) File(parent, unit.name) else if (unit.isOther) parent else null
        return Entry(unit.name, sorted.first().second, folder, sorted.map { it.second.file }, false, seriesFolder,
            subtitle = seriesFolder?.name ?: parent.name, isOther = unit.isOther)
    }

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
            // A loose file (an .m4b too) is one book
            for (song in direct) {
                result += Entry(song.title.ifBlank { song.file.nameWithoutExtension }, song, null,
                    listOf(song.file), false, null)
            }
            for ((name, topItems) in byTop) {
                val (directory, items) = descend(File(root, name), topItems)
                val books = booksOf(items)
                when {
                    // No books inside: the folder is one book
                    books == null -> {
                        val sorted = sortedByPath(items) { it.first }
                        result += Entry(directory.name, sorted.first().second, directory,
                            sorted.map { it.second.file }, false, null)
                    }
                    // One book only: the folder shows as that book, not as a series of one
                    books.size == 1 -> result += bookEntry(books[0], directory, null)
                    else -> {
                        val first = bookEntry(books.sortedWith(bookNameOrder()).first(), directory, directory)
                        result += Entry(directory.name, first.representative, directory,
                            items.map { it.second.file }, true, directory)
                    }
                }
            }
        }
        return result.sortedWith(compareBy<Entry> {
            val isRecentSeries = it.isSeries && (it.folder?.absolutePath == lastSeriesPath ||
                (!lastPlayedPath.isNullOrEmpty() && isUnder(File(lastPlayedPath), it.folder ?: File("/"))))
            if (isRecentSeries) 0 else 1
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** Finds the top-level series folder containing [file]: a folder with more than one book (see [booksOf]). */
    fun seriesFolderForFile(library: List<SongItem>, roots: List<File>, file: File): File? {
        for (root in roots) {
            val (top, items) = topOf(library, root, file) ?: continue
            if ((booksOf(items)?.size ?: 0) > 1) return top
        }
        return null
    }

    /**
     * The files of the book that [file] is in, in play order, grouped as the Cover Flow groups them (a book folder,
     * an .m4b, or the loose files of a series folder). Null when [file] is not a library file below a root.
     */
    fun bookFilesOf(library: List<SongItem>, roots: List<File>, file: File): List<File>? {
        val path = file.absoluteFile.path
        for (root in roots) {
            if (relative(dirPath(root), file) == null) continue
            // A loose file at a root is one book
            val (_, items) = topOf(library, root, file) ?: return listOf(file)
            if (items.none { it.second.file.absoluteFile.path == path }) return null
            val unit = booksOf(items)?.firstOrNull { u -> u.items.any { it.second.file.absoluteFile.path == path } }
            return sortedByPath(unit?.items ?: items) { it.first }.map { it.second.file }
        }
        return null
    }

    fun bookEntries(library: List<SongItem>, series: File, lastBookPath: String?): List<Entry> {
        val seriesPath = dirPath(series)
        val items = ArrayList<Pair<String, SongItem>>()
        for (song in library) {
            val rel = relative(seriesPath, song.file) ?: continue
            items.add(rel to song)
        }
        if (items.isEmpty()) return emptyList()
        val books = booksOf(items)
        // A series folder that holds one book only (the library changed): that book alone
        val result = if (books == null) {
            val sorted = sortedByPath(items) { it.first }
            listOf(Entry(series.name, sorted.first().second, series, sorted.map { it.second.file }, false, series))
        } else books.map { bookEntry(it, series, series) }
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
     * One ordering key for every file of a book: its folder first (so Disc 2 follows Disc 1), then the natural file
     * name (numbers by value: 2 before 10, 09 before 10, 001 before 002). Tags are not used: a wrong or missing
     * track tag must not change the order. Every pair compares by the
     * same key, so the order is transitive.
     */
    private class OrderKey(val dir: String, val name: String)

    private val ORDER: Comparator<OrderKey> =
        compareBy<OrderKey>({ it.dir }, { it.name })

    private fun orderKey(relativePath: String): OrderKey {
        val cut = relativePath.lastIndexOf(SEP)
        val dir = if (cut < 0) "" else naturalKey(relativePath.substring(0, cut))
        return OrderKey(dir, naturalKey(relativePath.substring(cut + 1)))
    }

    /** [items] in book order by the path that [path] gives (relative to the book folder), each key made one time. */
    private fun <T> sortedByPath(items: List<T>, path: (T) -> String): List<T> =
        items.map { orderKey(path(it)) to it }.sortedWith { a, b -> ORDER.compare(a.first, b.first) }.map { it.second }

    /** [files] of the book folder [base] in play order: the order of the index (folder, then natural file name). */
    fun orderedFiles(files: List<File>, base: File?): List<File> {
        val basePath = base?.let { dirPath(it) }
        return files.map { f ->
            orderKey((basePath?.let { relative(it, f) }) ?: f.name) to f
        }.sortedWith { a, b -> ORDER.compare(a.first, b.first) }.map { it.second }
    }

    private fun naturalKey(value: String): String = value.lowercase(Locale.US).replace(DIGITS) { m ->
        m.value.toLongOrNull()?.let { String.format(Locale.US, "%012d", it) } ?: m.value
    }
}
