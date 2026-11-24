package com.themoon.y1.controllers.podcasts

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import com.themoon.y1.MainActivity
import com.themoon.y1.StoragePaths
import com.themoon.y1.adapters.SongListAdapter
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.controllers.settings.confirmScreen
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.podcasts.PodcastDownloadManager
import com.themoon.y1.podcasts.PodcastRepository
import com.themoon.y1.views.Notice
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.util.ArrayList

/**
 * Screen controller for Podcast feeds, episode browsing, download actions,
 * streaming, and subscription management. The channels, the episodes of a channel and Manage Subscriptions are
 * three back-stack entries (as the other lists): Back goes up one list and selects the row that opened it.
 */
class PodcastController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    containerBrowserItems: LinearLayout,
    private val listVirtualSongs: ListView,
    private val repository: PodcastRepository,
    private val downloadManager: PodcastDownloadManager,
    private val onRequestSearch: () -> Unit,
    private val onNavigateToPlayer: () -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String,
    private val showLoadingPopup: (message: String?) -> Unit = {},
    private val onHideLoadingPopup: () -> Unit = {}
) : ScreenController {

    override val listScreen: ListScreen get() = screen

    private val screen = ListScreen(containerBrowserItems, listVirtualSongs, clickFeedback)

    /** The list that shows. */
    private var shown: ScreenState? = null

    /** The episodes of the last channel (its RSS URL): Back from an episode shows them again at once. */
    private var episodes: Pair<String, List<SongItem>>? = null

    /**
     * The number of the last episode list load; leaving the list adds one. Only that load may close the loading
     * screen or show its list: leaving a slow show and opening it again makes two loads of the same show.
     */
    private var episodesRequest = 0

    /** The loading screen shows for an episode list. */
    private var episodesLoading = false

    override fun onEnter(state: ScreenState) {
        val list = when (state) {
            is ScreenState.Browser -> when (val mode = state.mode) {
                is BrowserMode.PodcastEpisodes -> ScreenState.PodcastEpisodes(mode.channelTitle, mode.rssUrl)
                is BrowserMode.PodcastManage -> ScreenState.PodcastManage
                else -> ScreenState.Podcasts
            }
            is ScreenState.PodcastEpisodes, is ScreenState.PodcastManage -> state
            else -> ScreenState.Podcasts
        }
        shown = list
        when (list) {
            is ScreenState.PodcastEpisodes -> buildEpisodesUI(list.channelTitle, list.rssUrl)
            is ScreenState.PodcastManage -> buildManageUI()
            else -> buildChannelsUI()
        }
    }

    override fun onExit() {
        episodesRequest++
        stopEpisodesLoading()
        shown = null
        screen.clear()
    }

    override fun onThemeChanged() {
        shown?.let { onEnter(it) }
    }

    private fun open(state: ScreenState) {
        (activity as? MainActivity)?.changeScreen(state)
    }

    private fun channelsSpec(): ListSpec {
        val rows = ArrayList<RowSpec>()
        rows += RowSpec(localize("Search New Podcasts"), color = 0xFF00FFFF.toInt(), onClick = onRequestSearch)
        rows += RowSpec(localize("Manage Subscriptions"), color = 0xFFFF5555.toInt(), onClick = { open(ScreenState.PodcastManage) })
        val channels = repository.getSubscriptions()
        if (channels.isEmpty()) {
            rows += RowSpec("⚠️ " + localize("No subscriptions found.") + "\n\n" +
                    localize("Create a 'subscriptions.txt' file in the Podcasts folder.") + "\n" +
                    localize("Format: Channel Name|RSS_URL"), RowLook.INFO)
        }
        for (ch in channels) {
            rows += RowSpec(ch.title, onClick = {
                episodes = null
                open(ScreenState.PodcastEpisodes(ch.title, ch.rssUrl))
            })
        }
        return ListSpec(localize("Podcasts"), rows)
    }

    fun buildChannelsUI() {
        screen.show(channelsSpec())
    }

    /**
     * The episodes of a channel: from the network (the list is empty until they come), or the episodes kept.
     * When the feed does not load (no network), the downloaded episodes of the channel.
     */
    fun buildEpisodesUI(channelName: String, rssUrl: String) {
        val title = "${localize("Podcast")}: $channelName"
        episodes?.takeIf { it.first == rssUrl }?.let {
            showEpisodes(title, it.second)
            return
        }
        screen.show(ListSpec(title))
        val state = ScreenState.PodcastEpisodes(channelName, rssUrl)
        // The feed of a big show takes seconds: the iPod spinner until the list comes (Back still leaves)
        val request = ++episodesRequest
        episodesLoading = true
        showLoadingPopup(localize("Connecting to server..."))
        Thread {
            try {
                val list = repository.fetchEpisodes(rssUrl, channelName)
                activity.runOnUiThread {
                    // A load that is no longer the last must not replace the list Back from an episode restores
                    if (request != episodesRequest) return@runOnUiThread
                    episodes = rssUrl to list
                    stopEpisodesLoading()
                    if (shown == state) showEpisodes(title, list)
                }
            } catch (e: Exception) {
                val downloaded = try {
                    repository.downloadedEpisodes(channelName)
                } catch (_: Exception) {
                    emptyList()
                }
                activity.runOnUiThread {
                    if (request != episodesRequest) return@runOnUiThread
                    stopEpisodesLoading()
                    if (downloaded.isNotEmpty()) {
                        Notice.show(activity, localize("Offline Mode: Showing downloaded episodes only."), long = true)
                        episodes = rssUrl to downloaded
                        if (shown == state) showEpisodes(title, downloaded)
                    } else {
                        Notice.error(
                            activity,
                            localize("Failed to fetch episodes: ") + (e.message ?: "Unknown error")
                        )
                        if (shown == state) screen.show(ListSpec(title, empty = localize("No Episodes")))
                    }
                }
            }
        }.start()
    }

    private fun stopEpisodesLoading() {
        if (!episodesLoading) return
        episodesLoading = false
        onHideLoadingPopup()
    }

    /** A click on an episode opens its action screen, or plays it when it is downloaded (see [SongListAdapter]). */
    private fun showEpisodes(title: String, list: List<SongItem>) {
        screen.show(ListSpec(title, adapter = SongListAdapter(list, isPodcast = true), empty = localize("No Episodes")))
    }

    private fun manageSpec(): ListSpec {
        val rows = ArrayList<RowSpec>()
        rows += RowSpec(localize("Back to Podcasts"), lead = true, onClick = { NavigationManager.getInstance().popBackStack() })
        val channels = repository.getSubscriptions()
        if (channels.isEmpty()) rows += RowSpec("⚠️ " + localize("No subscriptions found."), RowLook.INFO)
        for (ch in channels) {
            rows += RowSpec(ch.title, RowLook.ICON, icon = TablerIcons.TRASH, color = 0xFFFF5555.toInt(),
                onClick = { showDeleteConfirmDialog(ch.title, ch.rssUrl) })
        }
        return ListSpec("${localize("Podcasts")}: ${localize("Manage Subscriptions")}", rows)
    }

    /** Manage Subscriptions: the first channel is selected (not the Back row). */
    fun buildManageUI() = screen.show(manageSpec())

    private fun showDeleteConfirmDialog(channelName: String, rssUrl: String) {
        ActionScreens.open(confirmScreen(
            localize("Delete Channel"),
            "${localize("Do you want to delete '")}$channelName'?",
            localize("Delete")
        ) {
            repository.removeSubscription(channelName, rssUrl)
            if (shown == ScreenState.PodcastManage) screen.update(manageSpec())
        })
    }

    /** The episode screen: Play (downloaded) or Stream, and Download. Back cancels. */
    fun showActionDialog(title: String, audioUrl: String, channelName: String) {
        val safeChannel = sanitize(channelName)
        val safeTitle = "${sanitize(title)}.mp3"
        val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)
        val prefs = activity.getSharedPreferences("AppPrefs", 0)
        val info = ArrayList<String>()
        val actions = ArrayList<ActionScreens.Action>()

        if (localFile.exists() && localFile.length() > 0 && !downloadManager.isDownloading(audioUrl)) {
            actions += ActionScreens.Action(localize("Play (Downloaded)")) {
                val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                var savedPos = prefs.getInt("book_pos_${localFile.absolutePath}", 0)
                if (savedPos == 0) savedPos = prefs.getInt("book_pos_$streamKey", 0)
                val playList = arrayListOf(localFile)
                if (savedPos > 0) {
                    AudioPlayerManager.getInstance().playTrackListWithOffset(playList, 0, savedPos)
                } else {
                    AudioPlayerManager.getInstance().playTrackList(playList, 0)
                }
                onNavigateToPlayer()
            }
        } else {
            actions += ActionScreens.Action(localize("Stream (Play)")) {
                // No notice: Now Playing shows the spinner over the art until the stream plays
                val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                var savedPos = prefs.getInt("book_pos_$streamKey", 0)
                if (savedPos == 0) savedPos = prefs.getInt("book_pos_${localFile.absolutePath}", 0)
                AudioPlayerManager.getInstance().playPodcastStream(audioUrl, title, channelName, savedPos)
                onNavigateToPlayer()
            }
            if (downloadManager.isDownloading(audioUrl)) {
                info += localize("Downloading...")
            } else {
                actions += ActionScreens.Action(localize("Download")) {
                    downloadManager.startDownload(
                        title,
                        audioUrl,
                        channelName,
                        object : PodcastDownloadManager.DownloadListener {
                            override fun onProgress(audioUrl: String, progressPercent: Int) {}
                            override fun onComplete(audioUrl: String, destFile: File) {}
                            override fun onError(audioUrl: String, errorMsg: String) {}
                        }
                    )
                }
            }
        }
        ActionScreens.open(ActionScreens.Spec(title, info, actions))
    }

    fun searchApplePodcasts(keyword: String) {
        showLoadingPopup(null)

        Thread {
            try {
                val results = com.themoon.y1.podcasts.PodcastSearchClient.getInstance().search(keyword, 200)

                activity.runOnUiThread {
                    onHideLoadingPopup()
                    if (results.isEmpty()) {
                        Notice.show(activity, "${localize("No podcasts found for: ")}$keyword")
                    } else {
                        showSearchResultsDialog(results)
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    onHideLoadingPopup()
                    Notice.error(activity, "Search Failed: ${e.message}")
                }
            }
        }.start()
    }

    /** The search results: one row for each podcast; the centre button subscribes. Back cancels. */
    private fun showSearchResultsDialog(results: List<com.themoon.y1.podcasts.PodcastSearchClient.PodcastSearchResult>) {
        ActionScreens.open(ActionScreens.Spec(
            "${localize("Search Results")} (${results.size})",
            actions = results.map { item ->
                ActionScreens.Action(item.name) {
                    try {
                        val podcastDir = StoragePaths.getPodcastsDir()
                        if (!podcastDir.exists()) podcastDir.mkdirs()
                        val subFile = File(podcastDir, "subscriptions.txt")
                        val safeName = item.name.replace("|", "-")
                        subFile.appendText("\n$safeName|${item.feedUrl}")

                        Notice.show(activity, "✅ ${localize("Subscribed: ")}$safeName")
                        if (shown == ScreenState.Podcasts) screen.update(channelsSpec())
                    } catch (e: Exception) {
                        Notice.error(activity, "Error saving: ${e.message}")
                    }
                }
            }
        ))
    }

    private fun sanitize(input: String): String {
        return input.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}

