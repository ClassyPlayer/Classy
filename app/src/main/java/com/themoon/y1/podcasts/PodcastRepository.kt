package com.themoon.y1.podcasts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.themoon.y1.StoragePaths
import com.themoon.y1.io.SafeFiles
import com.themoon.y1.models.PodcastChannel
import com.themoon.y1.models.PodcastEpisode
import com.themoon.y1.models.SongItem
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Handles subscription storage (subscriptions.txt), channel cover art caching,
 * and RSS feed parsing for podcasts.
 */
class PodcastRepository private constructor() {

    private val client: OkHttpClient = buildConscryptHttpClient()

    fun getSubscriptions(): List<PodcastChannel> {
        val podcastDir = StoragePaths.getPodcastsDir()
        if (!podcastDir.exists()) {
            podcastDir.mkdirs()
        }

        val subFile = File(podcastDir, "subscriptions.txt")
        if (!subFile.exists()) {
            try {
                BufferedWriter(OutputStreamWriter(FileOutputStream(subFile), Charsets.UTF_8)).use { bw ->
                    bw.write("# Y1 Podcast Subscriptions\n")
                    bw.write("# Format: Channel Name|RSS_URL\n\n")
                }
            } catch (ignored: Exception) {}
        }

        val channels = ArrayList<PodcastChannel>()
        if (subFile.exists()) {
            try {
                BufferedReader(InputStreamReader(FileInputStream(subFile), Charsets.UTF_8)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val trimmed = line?.trim() ?: continue
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                        val parts = trimmed.split("|")
                        if (parts.size >= 2) {
                            val name = parts[0].trim()
                            val url = parts[1].trim()
                            val safeChannel = name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                            channels.add(
                                PodcastChannel(
                                    title = name,
                                    rssUrl = url,
                                    localDirectory = StoragePaths.getPodcastChannelDir(safeChannel)
                                )
                            )
                        } else {
                            val url = parts[0].trim()
                            channels.add(PodcastChannel(title = "Unknown Channel", rssUrl = url))
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }
        return channels
    }

    fun addSubscription(channelName: String, rssUrl: String) {
        val podcastDir = StoragePaths.getPodcastsDir()
        if (!podcastDir.exists()) podcastDir.mkdirs()

        val subFile = File(podcastDir, "subscriptions.txt")
        try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(subFile, true), Charsets.UTF_8)).use { bw ->
                bw.write("$channelName|$rssUrl\n")
            }
        } catch (ignored: Exception) {}
    }

    fun removeSubscription(channelName: String, targetRssUrl: String): Boolean {
        val subFile = File(StoragePaths.getPodcastsDir(), "subscriptions.txt")
        if (!subFile.exists()) return false

        var removed = false
        try {
            val lines = ArrayList<String>()
            BufferedReader(InputStreamReader(FileInputStream(subFile), Charsets.UTF_8)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: continue
                    if (trimmed.contains(targetRssUrl) || (channelName.isNotEmpty() && trimmed.startsWith("$channelName|"))) {
                        removed = true
                        continue
                    }
                    lines.add(line!!)
                }
            }

            if (removed) {
                BufferedWriter(OutputStreamWriter(FileOutputStream(subFile), Charsets.UTF_8)).use { bw ->
                    for (l in lines) {
                        bw.write(l)
                        bw.newLine()
                    }
                }
            }
        } catch (ignored: Exception) {}

        return removed
    }

    fun fetchEpisodes(rssUrl: String, channelName: String): List<SongItem> {
        val episodes = ArrayList<SongItem>()
        val request = Request.Builder()
            .url(rssUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/114.0.0.0 Safari/537.36")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw java.io.IOException("HTTP Error: ${response.code()}")
        }

        val isStream = response.body()?.byteStream() ?: return episodes
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(isStream, "UTF-8")

        var eventType = parser.eventType
        var insideItem = false
        var title = ""
        var pubDate = ""
        var audioUrl = ""
        var imageUrl = ""
        var channelImageUrl = ""

        while (eventType != XmlPullParser.END_DOCUMENT && episodes.size < 300) {
            val name = parser.name ?: ""

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (name.equals("item", ignoreCase = true)) {
                        insideItem = true
                    } else if (insideItem && name.equals("title", ignoreCase = true)) {
                        title = parser.nextText() ?: ""
                    } else if (insideItem && name.equals("pubDate", ignoreCase = true)) {
                        pubDate = parser.nextText() ?: ""
                        if (pubDate.length > 16) {
                            pubDate = pubDate.substring(0, 16)
                        }
                    } else if (insideItem && name.equals("enclosure", ignoreCase = true)) {
                        audioUrl = parser.getAttributeValue(null, "url") ?: ""
                    } else if (name.equals("itunes:image", ignoreCase = true)) {
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        if (insideItem) {
                            imageUrl = href
                        } else if (channelImageUrl.isEmpty() && href.isNotEmpty()) {
                            channelImageUrl = href
                        }
                    } else if (!insideItem && name.equals("url", ignoreCase = true)) {
                        try {
                            val urlText = parser.nextText() ?: ""
                            if (channelImageUrl.isEmpty() && urlText.startsWith("http")) {
                                channelImageUrl = urlText
                            }
                        } catch (ignored: Exception) {}
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name.equals("item", ignoreCase = true)) {
                        if (audioUrl.isNotEmpty()) {
                            episodes.add(
                                SongItem(
                                    File("/PODCAST"),
                                    title,
                                    channelName,
                                    imageUrl,
                                    pubDate,
                                    audioUrl
                                )
                            )
                        }
                        insideItem = false
                        title = ""
                        pubDate = ""
                        audioUrl = ""
                        imageUrl = ""
                    }
                }
            }
            eventType = parser.next()
        }
        isStream.close()

        keepChannelCover(channelName, channelImageUrl.takeIf { it.isNotEmpty() })
        if (episodes.isNotEmpty()) keepEpisodeList(channelName, episodes)

        return episodes
    }

    /** Keeps the episode list of the feed for [downloadedEpisodes] (order and dates without the network). */
    private fun keepEpisodeList(channelName: String, episodes: List<SongItem>) {
        try {
            val file = File(StoragePaths.getPodcastChannelDir(PodcastOfflineEpisodes.safeName(channelName)),
                PodcastOfflineEpisodes.LIST_FILE)
            SafeFiles.writeStringToFile(file, PodcastOfflineEpisodes.toJson(episodes))
        } catch (ignored: Exception) {}
    }

    /**
     * The downloaded episodes of [channelName], for when the feed does not load (no network):
     * see [PodcastOfflineEpisodes.list].
     */
    fun downloadedEpisodes(channelName: String): List<SongItem> {
        val dir = StoragePaths.getPodcastChannelDir(PodcastOfflineEpisodes.safeName(channelName))
        val files = dir.listFiles()?.toList() ?: return emptyList()
        val kept = SafeFiles.readStringFromFile(File(dir, PodcastOfflineEpisodes.LIST_FILE))
            ?.let { PodcastOfflineEpisodes.fromJson(it, channelName) } ?: emptyList()
        return PodcastOfflineEpisodes.list(channelName, files, kept)
    }

    /**
     * Keeps the show cover as [PodcastCover] says: downloaded from [imageUrl] once, or a cover that an older version
     * kept as it came made small. Off the feed thread, so the episode list does not wait for it.
     */
    private fun keepChannelCover(channelName: String, imageUrl: String?) {
        Thread {
            try {
                val coverFile = File(StoragePaths.getPodcastChannelDir(PodcastOfflineEpisodes.safeName(channelName)),
                    PodcastCover.FILE_NAME)
                if (coverFile.length() > 0) {
                    val bounds = boundsOf(coverFile)
                    if (PodcastCover.isKept(bounds.outMimeType, bounds.outWidth, bounds.outHeight)) return@Thread
                    val made = coverFile.takeIf { it.length() <= PodcastCover.MAX_BYTES }
                        ?.let { SafeFiles.readBytesFromFile(it) }?.let { makeCover(it) }
                    if (made != null) {
                        SafeFiles.writeBytesToFile(coverFile, made)
                        return@Thread
                    }
                }
                // A cover that cannot be made small stays until the download replaces it (the write is atomic):
                // readers decode it only within PodcastCover.canDecode
                val downloaded = imageUrl?.let { downloadCover(it) } ?: return@Thread
                makeCover(downloaded)?.let { SafeFiles.writeBytesToFile(coverFile, it) }
            } catch (ignored: Throwable) {}
        }.start()
    }

    private fun downloadCover(imageUrl: String): ByteArray? {
        val url = HttpUrl.parse(imageUrl.trim()) ?: return null
        val call = client.newCall(Request.Builder().url(url).build())
        call.timeout().timeout(PodcastHttp.SMALL_FILE_TIMEOUT_S, TimeUnit.SECONDS)
        return call.execute().use { response ->
            response.body()?.takeIf { response.isSuccessful }?.byteStream()?.let { PodcastCover.readAtMost(it) }
        }
    }

    private fun boundsOf(file: File): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
        }

    /** The kept cover made from [image] (any format Android decodes), or null if it is not an image that can be. */
    private fun makeCover(image: ByteArray): ByteArray? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, opts)
        val width = opts.outWidth
        val height = opts.outHeight
        if (!PodcastCover.canDecode(opts.outMimeType, width, height, image)) return null

        opts.inJustDecodeBounds = false
        opts.inSampleSize = PodcastCover.sampleSize(width, height)
        val sampled = BitmapFactory.decodeByteArray(image, 0, image.size, opts) ?: return null
        try {
            val (w, h) = PodcastCover.fit(sampled.width, sampled.height)
            val cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                // White under a transparent PNG: JPEG has no alpha
                Canvas(cover).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(sampled, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val out = ByteArrayOutputStream()
                if (!cover.compress(Bitmap.CompressFormat.JPEG, PodcastCover.JPEG_QUALITY, out)) return null
                return out.toByteArray()
            } finally {
                cover.recycle()
            }
        } finally {
            sampled.recycle()
        }
    }

    private fun buildConscryptHttpClient(): OkHttpClient {
        return try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
            sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    companion object {
        @Volatile
        private var instance: PodcastRepository? = null

        fun getInstance(): PodcastRepository {
            return instance ?: synchronized(this) {
                instance ?: PodcastRepository().also { instance = it }
            }
        }
    }
}
