package com.themoon.y1.podcasts

import com.themoon.y1.models.SongItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The episodes of a channel without the network: the downloaded files in the folder of the channel.
 * An episode of the feed is a [SongItem] with the channel in [SongItem.artist], the date in [SongItem.year],
 * the audio URL in [SongItem.genre] and the image URL in [SongItem.album]; its download is
 * `<channel folder>/<[fileName] of the title>`, as [PodcastDownloadManager] writes it.
 */
object PodcastOfflineEpisodes {

    /** The last episode list of the feed, in the channel folder. A hidden file: the library scan skips it. */
    const val LIST_FILE = ".episodes.json"

    private const val EXTENSION = ".mp3"
    private val UNSAFE = Regex("[\\\\/:*?\"<>|]")

    /** The name of a channel folder or of an episode file: characters that a file name cannot have become "_". */
    fun safeName(name: String): String = name.replace(UNSAFE, "_")

    /** The file name of the download of the episode [title]. */
    fun fileName(title: String): String = safeName(title) + EXTENSION

    /** The episode list for [LIST_FILE]: title, date, audio URL and image URL of each episode, in feed order. */
    fun toJson(episodes: List<SongItem>): String {
        val array = JSONArray()
        for (ep in episodes) {
            array.put(JSONObject()
                .put("title", ep.title)
                .put("date", ep.year)
                .put("url", ep.genre)
                .put("image", ep.album))
        }
        return array.toString()
    }

    /** The episodes of a [toJson] text, for [channelName]. A damaged text gives no episodes. */
    fun fromJson(text: String, channelName: String): List<SongItem> {
        val list = ArrayList<SongItem>()
        try {
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val title = o.optString("title")
                if (title.isEmpty()) continue
                list += SongItem(File("/PODCAST"), title, channelName, o.optString("image"), o.optString("date"),
                    o.optString("url"))
            }
        } catch (_: Exception) {
        }
        return list
    }

    /**
     * The downloaded episodes in [files] (the files of the channel folder). First the episodes of [kept] (the last
     * list of the feed) that have a download, in feed order with their date. Then the other downloads, for example
     * from before the list was kept, the newest file first, with the file name as title and no date; hidden files
     * (such as the macOS "._" files) are not in this part. An empty file and a file whose name [fileName] cannot
     * give (the episode click finds the download from the title) are not episodes. A download that is not complete
     * has a temporary name (see [PodcastDownloadManager]), so it is not an episode either.
     */
    fun list(
        channelName: String,
        files: List<File>,
        kept: List<SongItem>
    ): List<SongItem> {
        val downloads = LinkedHashMap<String, File>()
        for (f in files) {
            val name = f.name
            if (!name.endsWith(EXTENSION) || name.length == EXTENSION.length || safeName(name) != name) continue
            if (!f.isFile || f.length() <= 0L) continue
            downloads[name] = f
        }
        val list = ArrayList<SongItem>()
        for (ep in kept) {
            val file = downloads.remove(fileName(ep.title)) ?: continue
            list += SongItem(file, ep.title, channelName, ep.album, ep.year, ep.genre)
        }
        downloads.values
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            .forEach { f ->
                list += SongItem(f, f.name.removeSuffix(EXTENSION), channelName, null, null, null).apply { year = "" }
            }
        return list
    }
}
