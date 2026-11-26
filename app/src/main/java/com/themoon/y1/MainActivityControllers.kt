package com.themoon.y1

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import com.themoon.y1.controllers.browser.BrowserController
import com.themoon.y1.controllers.connectivity.BluetoothController
import com.themoon.y1.controllers.connectivity.WebServerController
import com.themoon.y1.controllers.connectivity.WifiController
import com.themoon.y1.controllers.connectivity.WifiKeyboardController
import com.themoon.y1.controllers.coverflow.CoverFlowController
import com.themoon.y1.controllers.coverflow.AudiobookSeriesIndex
import com.themoon.y1.controllers.home.HomeWidgetsController
import com.themoon.y1.controllers.menu.MainMenuController
import com.themoon.y1.controllers.games.GamePlayerController
import com.themoon.y1.controllers.games.GamesController
import com.themoon.y1.controllers.player.PlayerController
import com.themoon.y1.controllers.playlists.PlaylistController
import com.themoon.y1.controllers.podcasts.PodcastController
import com.themoon.y1.controllers.quiz.MusicQuizController
import com.themoon.y1.controllers.radio.FmRadioController
import com.themoon.y1.controllers.settings.BrightnessController
import com.themoon.y1.controllers.settings.EqualizerController
import com.themoon.y1.views.ClassyMetrics
import com.themoon.y1.controllers.settings.SettingsController
import com.themoon.y1.controllers.storage.StorageController
import com.themoon.y1.controllers.sync.JellyfinUiController
import com.themoon.y1.controllers.sync.WebDavUiController
import com.themoon.y1.controllers.videos.VideoBrowserController
import com.themoon.y1.controllers.videos.VideoPlayerController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.managers.OtaUpdateManager
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.KeyboardTarget
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.VideoMode
import com.themoon.y1.podcasts.PodcastDownloadManager
import com.themoon.y1.podcasts.PodcastRepository
import com.themoon.y1.sync.webdav.LedgerNotSaved
import com.themoon.y1.sync.webdav.WebDavSync
import com.themoon.y1.sync.webdav.WebDavSyncProvider
import com.themoon.y1.views.EdgeFadeListView
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.Notice

internal fun MainActivity.initControllers() {
    val rootContent = findViewById<ViewGroup>(android.R.id.content)
    val containerBrowserItems = findViewById<LinearLayout>(R.id.container_browser_items)

    val listContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    val listVirtualSongs = object : EdgeFadeListView(this) {
        override fun setVisibility(visibility: Int) {
            super.setVisibility(visibility)
            listContainer.visibility = visibility
        }
    }.apply {
        divider = ColorDrawable(0)
        dividerHeight = 0
        selector = ColorDrawable(0)
        itemsCanFocus = true
        isSoundEffectsEnabled = false
        visibility = View.GONE
    }
    listContainer.addView(listVirtualSongs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f))

    val scrollViewBrowser = containerBrowserItems.parent as View
    val browserParent = scrollViewBrowser.parent as ViewGroup
    val density = resources.displayMetrics.density
    browserParent.setPadding(0, (ClassyMetrics.STATUS_BAR_HEIGHT * density).toInt(), 0, 0)
    browserParent.addView(listContainer, scrollViewBrowser.layoutParams)

    mainMenuController = MainMenuController(
        activity = this,
        rootView = layoutMainMenu as ViewGroup,
        clickFeedback = { clickFeedback() },
        onAction = { action -> handleMainMenuAction(action) },
        onHold = { turnOffScreen() },
        localize = { t(it) },
        onShowPane = { homeWidgetsController?.refreshWidgets() }
    )

    homeWidgetsController = HomeWidgetsController(
        context = this,
        ivMainMenuCover = findViewById(R.id.iv_main_menu_cover),
        ivBrowserCover = findViewById(R.id.iv_browser_cover),
        frameBrowserCover = findViewById(R.id.frame_browser_cover),
        ivSettingsCover = findViewById(R.id.iv_settings_cover),
        fetchRandomIdleCoverAsync = { fetchRandomIdleCoverAsync() },
        isAudiobookMode = {
            isAudiobookLibraryMode || (isBrowserControllerInitialized() && (browserController.isAudiobookLibraryMode || browserController.currentBrowserMode is BrowserMode.Audiobooks))
        }
    )

    browserController = BrowserController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        clickFeedback = { clickFeedback() },
        onNavigateToScreen = { state ->
            changeScreen(state)
        },
        onNavigateBackToMenu = {
            NavigationManager.getInstance().popBackStack()
        },
        onCoverFlowRequested = {
            changeScreen(ScreenState.CoverFlow)
        },
        onPlaylistsRequested = {
            changeScreen(ScreenState.Playlists)
        },
        onShuffleSongsRequested = {
            shuffleAllSongs()
        },
        onSearchRequested = {
            changeScreen(ScreenState.WifiKeyboard(KeyboardTarget.LibrarySearch))
        },
        onStartMediaLibraryScan = {
            startMediaLibraryScan()
        },
        showLoadingPopup = {
            showLoadingPopup()
        },
        showAddToPlaylistDialog = { file ->
            showAddToPlaylistDialog(file)
        },
        onAudiobookSelected = { file, parent ->
            AudioPlayerManager.getInstance().setupFolderPlaylist(file, parent)
            changeScreen(MainActivity.STATE_PLAYER)
        },
        localize = { t(it) },
        onRefreshCoverPane = {
            homeWidgetsController?.refreshWidgets()
        }
    )

    playlistController = PlaylistController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        musicRootFolder = rootFolder,
        isAudioFile = { MediaLibraryManager.getInstance().isAudioFile(it) },
        clickFeedback = { clickFeedback() },
        onPlaylistSelected = { file ->
            changeScreen(ScreenState.M3uPlaylistSongs(file))
        },
        localize = { t(it) },
        noDuplicates = { Prefs.PLAYLIST_NO_DUPLICATES.get(prefs) }
    )

    coverFlowController = CoverFlowController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        uniqueAlbumList = uniqueAlbumList,
        clickFeedback = { clickFeedback() },
        onAlbumSelected = { album ->
            changeScreen(ScreenState.Browser(BrowserMode.Songs("COVER_FLOW_ALBUM", album.album, AlbumKey.artistOf(album))))
        },
        onRebuildUniqueAlbumList = { rebuildUniqueAlbumListInternal() },
        nowPlayingSong = {
            // While the FM radio is on, the radio plays, not the song of the queue.
            if (com.themoon.y1.managers.FmRadioManager.getInstance()?.isPowerUp == true) null
            else currentPlaylist.getOrNull(currentIndex)?.let { file -> MainActivity.customLibrary.firstOrNull { it.file == file } }
        },
        localize = { t(it) }
    )

    val audiobookCoverItems = ArrayList<com.themoon.y1.models.SongItem>()
    val audiobookEntriesByPath = HashMap<String, AudiobookSeriesIndex.Entry>()
    // The indexes that were built (the top level and the series that were opened): Back from a series, or a refresh,
    // reuses them while nothing they depend on changed (the key holds the route, the library and the saved places).
    val audiobookEntriesCache = HashMap<String, List<AudiobookSeriesIndex.Entry>>()
    fun prepareAudiobookCoverFlow(state: ScreenState?) {
        val route = state as? ScreenState.AudiobookCoverFlow ?: return
        val prefs = AppPrefs.get(this)
        val library = MainActivity.audiobookLibrary
        val cacheKey = listOf(
            route.seriesDirectory?.absolutePath, library.size, MainActivity.audiobookLibraryVersion,
            prefs.getString("audiobook_series_last_played", null), prefs.getString("last_played_audiobook_path", null),
            route.seriesDirectory?.let { prefs.getString("audiobook_series_last_book_${it.absolutePath}", null) }
        ).joinToString("|")
        val cached = audiobookEntriesCache[cacheKey]
        val entries = if (cached != null && cached.isNotEmpty()) {
            cached
        } else if (route.seriesDirectory == null) {
            AudiobookSeriesIndex.rootEntries(
                MainActivity.audiobookLibrary,
                StoragePaths.getAudiobooksDirs(),
                prefs.getString("audiobook_series_last_played", null),
                prefs.getString("last_played_audiobook_path", null)
            )
        } else {
            val key = "audiobook_series_last_book_${route.seriesDirectory.absolutePath}"
            AudiobookSeriesIndex.bookEntries(
                MainActivity.audiobookLibrary, route.seriesDirectory, prefs.getString(key, null)
            )
        }
        if (audiobookEntriesCache.size > 12) audiobookEntriesCache.clear()
        audiobookEntriesCache[cacheKey] = entries
        audiobookCoverItems.clear()
        audiobookEntriesByPath.clear()
        for (stored in entries) {
            val entry = if (stored.isOther) stored.copy(title = t("Other")) else stored
            val cover = entry.coverItem()
            audiobookCoverItems.add(cover)
            audiobookEntriesByPath[cover.file.absolutePath] = entry
        }
    }

    audiobookCoverFlowController = CoverFlowController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        listVirtualSongs = listVirtualSongs,
        uniqueAlbumList = audiobookCoverItems,
        clickFeedback = { clickFeedback() },
        onAlbumSelected = onAlbumSelected@{ cover ->
            val entry = audiobookEntriesByPath[cover.file.absolutePath] ?: return@onAlbumSelected
            val prefs = AppPrefs.get(this)
            if (entry.isSeries && entry.folder != null) {
                prefs.edit().putString("audiobook_series_last_played", entry.folder.absolutePath).apply()
                changeScreen(ScreenState.AudiobookCoverFlow(entry.folder))
            } else {
                val files = AudiobookSeriesIndex.orderedFiles(entry.audioFiles, entry.folder)
                if (files.isNotEmpty()) {
                    isAudiobookLibraryMode = true
                    val series = entry.seriesFolder
                    val ownPath = entry.folder?.let { prefs.getString("audiobook_last_in_${it.absolutePath}", null) }
                    val resumePath = if (!ownPath.isNullOrEmpty() && files.any { it.absolutePath == ownPath }) ownPath
                    else if (series != null) {
                        prefs.getString("audiobook_series_last_book_${series.absolutePath}", null)
                    } else prefs.getString("last_played_audiobook_path", null)
                    if (series != null) {
                        prefs.edit().putString("audiobook_series_last_played", series.absolutePath).apply()
                    }
                    // A saved place that is not one of this book's files (or none) starts at the first file in order
                    val selected = if (resumePath.isNullOrEmpty()) 0
                    else files.indexOfFirst { it.absolutePath == resumePath }.let { if (it < 0) 0 else it }
                    val offset = prefs.getInt("book_pos_${files[selected].absolutePath}", 0)
                    AudioPlayerManager.getInstance().playTrackListWithOffset(files, selected, offset)
                    changeScreen(ScreenState.Player)
                }
            }
        },
        onRebuildUniqueAlbumList = { prepareAudiobookCoverFlow(NavigationManager.getInstance().currentState) },
        nowPlayingSong = {
            val route = NavigationManager.getInstance().currentState as? ScreenState.AudiobookCoverFlow
            val prefs = AppPrefs.get(this)
            val path = if (route?.seriesDirectory != null) {
                prefs.getString("audiobook_series_last_book_${route.seriesDirectory.absolutePath}", null)
            } else prefs.getString("last_played_audiobook_path", null)
            audiobookEntriesByPath.values.firstOrNull { entry ->
                !path.isNullOrEmpty() && entry.audioFiles.any { it.absolutePath == path }
            }?.coverItem()
        },
        localize = { t(it) },
        prepareItems = { state -> prepareAudiobookCoverFlow(state) },
        isAdditionalOpenState = { it is ScreenState.AudiobookCoverFlow },
        emptyText = t("No Audiobooks Found"),
        titleProvider = {
            (NavigationManager.getInstance().currentState as? ScreenState.AudiobookCoverFlow)
                ?.seriesDirectory?.name ?: t("Audiobook Series")
        }
    )

    playerController = PlayerController(
        activity = this,
        rootView = layoutPlayerMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onAdjustVolume = { adjustVolume(it) },
        onTogglePlayback = { com.themoon.y1.managers.AudioSources.app.playPauseKey() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        onBrowseAlbum = { album ->
            // The menu gives only the title; take the album artist from the song that plays.
            val playingPath = currentPlaylist.getOrNull(currentIndex)?.absolutePath
            val playingSong = MainActivity.customLibrary.firstOrNull { it.file.absolutePath == playingPath }
            val albumArtist = playingSong?.let { AlbumKey.artistOf(it) } ?: ""
            changeScreen(ScreenState.Browser(BrowserMode.Songs("ALBUM", album, albumArtist)))
        },
        onBrowseArtist = { artist ->
            changeScreen(ScreenState.Browser(BrowserMode.Songs("ARTIST", artist)))
        },
        onToggleFavorite = { toggleFavorite() },
        onAddToOnTheGo = { playlistController.addSongToOnTheGo(it) },
        onAddToPlaylist = { showAddToPlaylistDialog(it) },
        onToggleVisualizer = { toggleVisualizer() },
        localize = { t(it) }
    )

    equalizerController = EqualizerController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        eqPresetNames = eqPresetNames,
        createCategoryHeader = { title ->
            val container = findViewById<LinearLayout>(R.id.container_settings_items)
            if (container != null) ListItemViewFactory.createCategoryHeader(this, container, title)
        },
        createListButton = { text -> createListButton(text) },
        clickFeedback = { clickFeedback() },
        rebootAudioEngine = { rebootAudioEngine() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        localize = { t(it) }
    )

    fmRadioController = FmRadioController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        updateGlobalStatusPlayIcon = { updatePlayerUI() },
        onExitRadio = { NavigationManager.getInstance().popBackStack() },
        showLoadingPopup = { showLoadingPopup() },
        dismissLoadingOverlay = { dismissLoadingOverlay() },
        updateLoadingProgress = { progress, message -> updateLoadingProgress(progress, message) },
        localize = { t(it) }
    )

    podcastController = PodcastController(
        activity = this,
        rootView = layoutBrowserMode as ViewGroup,
        containerBrowserItems = containerBrowserItems,
        listVirtualSongs = listVirtualSongs,
        repository = PodcastRepository.getInstance(),
        downloadManager = PodcastDownloadManager.getInstance(),
        onRequestSearch = {
            changeScreen(ScreenState.WifiKeyboard(KeyboardTarget.PodcastSearch))
        },
        onNavigateToPlayer = { changeScreen(MainActivity.STATE_PLAYER) },
        clickFeedback = { clickFeedback() },
        localize = { t(it) },
        showLoadingPopup = { message -> showLoadingPopup(message) },
        onHideLoadingPopup = { dismissLoadingOverlay() }
    )

    musicQuizController = MusicQuizController(
        activity = this,
        rootView = layoutMusicQuizMode as ViewGroup,
        libraryProvider = { MainActivity.customLibrary },
        onExitRequested = { NavigationManager.getInstance().popBackStack() },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    videoBrowserController = VideoBrowserController(
        activity = this,
        rootView = layoutVideosMode as ViewGroup,
        movieVideosProvider = { movieVideos },
        tvSeriesProvider = { tvSeriesList },
        allVideosProvider = { videoFiles },
        onVideoSelected = { file ->
            changeScreen(ScreenState.VideoPlayer(file))
        },
        onNavigateToMode = { mode ->
            changeScreen(ScreenState.Videos(mode))
        },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    videoPlayerController = VideoPlayerController(
        activity = this,
        rootView = layoutVideoPlayerMode as ViewGroup,
        onExitRequested = { NavigationManager.getInstance().popBackStack() },
        onAdjustVolume = { adjustVolume(it) },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    gamesController = GamesController(
        activity = this,
        rootView = layoutGamesMode as ViewGroup,
        onGameSelected = { dir -> changeScreen(ScreenState.GamePlayer(dir)) },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    com.themoon.y1.photos.PhotoLibrary.init(this)
    com.themoon.y1.photos.PhotoThumbs.init(this)
    photosController = com.themoon.y1.controllers.photos.PhotosController(
        activity = this,
        rootView = layoutPhotosMode as ViewGroup,
        changeScreen = { state -> changeScreen(state) },
        clickFeedback = { clickFeedback() },
        refreshTitle = { refreshStatusBarTitle() },
        localize = { t(it) }
    )

    photoViewerController = com.themoon.y1.controllers.photos.PhotoViewerController(
        activity = this,
        rootView = layoutPhotoViewerMode as ViewGroup,
        prefs = prefs,
        onLeave = { set, index -> photosController.rememberPhoto(set, index) },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    gamePlayerController = GamePlayerController(
        activity = this,
        rootView = layoutGamePlayerMode as ViewGroup,
        onExitRequested = { NavigationManager.getInstance().popBackStack() },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    bookReaderController = com.themoon.y1.controllers.books.BookReaderController(
        activity = this,
        rootView = layoutBookReaderMode as ViewGroup,
        prefs = prefs,
        onOpenMenu = { file -> changeScreen(ScreenState.Books(com.themoon.y1.navigation.BooksLevel.ReaderMenu(file))) },
        onLeave = {
            NavigationManager.getInstance().popBackStackTo {
                !com.themoon.y1.controllers.books.BookReaderController.isReaderScreen(it)
            }
        },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    booksController = com.themoon.y1.controllers.books.BooksController(
        activity = this,
        rootView = layoutBooksMode as ViewGroup,
        prefs = prefs,
        reader = bookReaderController,
        changeScreen = { state -> changeScreen(state) },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    bluetoothController = BluetoothController(
        activity = this,
        rootView = layoutBluetoothMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    wifiController = WifiController(
        activity = this,
        rootView = layoutWifiMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onConnectToSavedNetwork = { _, _ -> },
        onOpenWifiKeyboard = { ssid, isOpen ->
            changeScreen(ScreenState.WifiKeyboard(KeyboardTarget.WifiPassword(ssid, isOpen)))
        },
        localize = { t(it) }
    )

    wifiKeyboardController = WifiKeyboardController(
        activity = this,
        rootView = layoutWifiKeyboard as ViewGroup,
        clickFeedback = { clickFeedback() },
        onExitKeyboard = { _ ->
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitSearchPodcast = { keyword ->
            NavigationManager.getInstance().popBackStack()
            podcastController.searchApplePodcasts(keyword)
        },
        onSubmitSearchLibrary = { keyword ->
            // The result list takes the place of the keyboard: Back goes to the Music menu, on Search
            NavigationManager.getInstance().navigateTo(ScreenState.Browser(BrowserMode.Songs("SEARCH", keyword)), addToBackStack = false)
        },
        onSubmitLastFmUsername = { username ->
            prefs.edit().putString(Prefs.LASTFM_USERNAME.key, username).apply()
        },
        onSubmitLastFmPassword = { password ->
            prefs.edit().putString(Prefs.LASTFM_PASSWORD.key, password).apply()
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitJellyfinServerUrl = { url ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            provider.serverUrl = url
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitJellyfinUsername = { username ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            provider.username = username
        },
        onSubmitJellyfinPassword = { password ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            provider.password = password
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitJellyfinToken = { token ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            provider.accessToken = token
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitWebDavUrl = { url ->
            // Another server must be trusted again, and starts with no folders; a sync that started meanwhile keeps the old URL
            try {
                if (!WebDavSyncProvider.getInstance(this).changeUrl(url)) Notice.show(this, t("Syncing..."))
            } catch (e: LedgerNotSaved) {
                Notice.error(this, t(e.message ?: "")) // the old URL stays
            }
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitWebDavUser = { user ->
            WebDavSyncProvider.getInstance(this).user = user
        },
        onSubmitWebDavPassword = { password ->
            WebDavSyncProvider.getInstance(this).password = password
            NavigationManager.getInstance().popBackStack()
        },
        onSubmitPlaylistName = { m3uFile, name -> playlistController.renamePlaylist(m3uFile, name) },
        onConnectWifi = { ssid, password, isOpen ->
            val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val conf = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (isOpen) {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    } else {
                        preSharedKey = "\"$password\""
                    }
                }
                val netId = wm.addNetwork(conf)
                if (netId != -1) {
                    wm.enableNetwork(netId, true)
                    wm.saveConfiguration()
                }
            }
            NavigationManager.getInstance().popBackStack()
        },
        initialText = { target ->
            val provider = com.themoon.y1.sync.jellyfin.JellyfinSyncProvider.getInstance(this)
            when (target) {
                KeyboardTarget.JellyfinServerUrl -> provider.serverUrl
                KeyboardTarget.JellyfinUsername -> provider.username
                is KeyboardTarget.JellyfinPassword -> provider.password
                KeyboardTarget.JellyfinToken -> provider.accessToken
                KeyboardTarget.WebDavUrl -> WebDavSyncProvider.getInstance(this).url
                KeyboardTarget.WebDavUser -> WebDavSyncProvider.getInstance(this).user
                is KeyboardTarget.WebDavPassword -> "" // the keyboard does not mask: the saved password is not shown
                is KeyboardTarget.PlaylistName -> target.m3uFile.nameWithoutExtension
                else -> ""
            }
        },
        localize = { t(it) }
    )

    webServerController = WebServerController(
        activity = this,
        rootView = layoutWebServerMode as ViewGroup,
        onServerStateChanged = { running ->
            isServerRunning = running
            // The server holds the screen on: the app's own Backlight timer runs only now.
            if (running) powerBacklightManager?.resetTimer() else powerBacklightManager?.cancelTimer()
        },
        onScanMediaRequested = { startMediaLibraryScan() },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    storageController = StorageController(
        activity = this,
        rootView = layoutStorageMode as ViewGroup,
        songsProvider = { MainActivity.customLibrary },
        videosProvider = { videoFiles },
        clickFeedback = { clickFeedback() },
        localize = { t(it) }
    )

    brightnessController = BrightnessController(
        activity = this,
        rootView = layoutBrightnessMode as ViewGroup,
        clickFeedback = { clickFeedback() }
    )

    jellyfinUiController = JellyfinUiController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onOpenKeyboard = { target ->
            changeScreen(ScreenState.WifiKeyboard(target))
        },
        onRequestWifi = { _ -> changeScreen(MainActivity.STATE_WIFI) },
        isNetworkConnected = {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetworkInfo
            net != null && net.isConnected
        },
        onSyncFinished = { startMediaLibraryScan() },
        onScanVideos = { scanVideos() },
        isFilePlaying = { file ->
            AudioPlayerManager.getInstance().isFileCurrentlyPlaying(file)
        },
        customLibrary = MainActivity.customLibrary,
        trackNumberMap = MainActivity.trackNumberMap,
        localize = { t(it) },
        isCustomScanning = { isCustomScanning }
    )

    webDavUiController = WebDavUiController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        clickFeedback = { clickFeedback() },
        onOpenKeyboard = { target -> changeScreen(ScreenState.WifiKeyboard(target)) },
        onRequestWifi = { changeScreen(MainActivity.STATE_WIFI) },
        isNetworkConnected = {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetworkInfo
            net != null && net.isConnected
        },
        // A run can end after this window is gone (the activity was made again): then the live one rescans on the broadcast
        onSyncFinished = {
            if (isDestroyed) applicationContext.sendBroadcast(
                android.content.Intent(WebDavSync.ACTION_SYNCED).setPackage(packageName)
            ) else startMediaLibraryScan()
        },
        localize = { t(it) }
    )

    otaUpdateManager = OtaUpdateManager(
        activity = this,
        clickFeedback = { clickFeedback() },
        onRequireWifi = {
            isPendingUpdateCheckAfterWifi = true
            changeScreen(MainActivity.STATE_WIFI)
        },
        localize = { t(it) }
    )

    settingsController = SettingsController(
        activity = this,
        rootView = layoutSettingsMode as ViewGroup,
        eqPresetNames = eqPresetNames,
        clickFeedback = { clickFeedback() },
        onNavigateBack = { NavigationManager.getInstance().popBackStack() },
        onNavigateToScreen = { screenId ->
            if (screenId == MainActivity.STATE_WIFI_KEYBOARD) {
                // Settings opens the keyboard only from the Last.fm Account row
                changeScreen(ScreenState.WifiKeyboard(KeyboardTarget.LastFmUsername))
            } else {
                changeScreen(screenId)
            }
        },
        onOpenEqualizer = { changeScreen(ScreenState.Equalizer) },
        onOpenJellyfin = { changeScreen(ScreenState.Jellyfin()) },
        onOpenWebDav = { changeScreen(ScreenState.WebDav()) },
        onOpenUpdateCheck = { otaUpdateManager?.checkForUpdates() },
        onClearCacheConfirmed = {
            MediaLibraryManager.getInstance().musicLibrary.clear()
            MainActivity.customLibrary.clear()
            startMediaLibraryScan()
        },
        resetBacklightTimer = { powerBacklightManager?.applyBacklightSetting() },
        applySoundSetting = {
            updateScreenOffFeedback(true)
        },
        applyAlbumTiltSetting = {
            playerController.applyAlbumTiltSetting()
        },
        setShuffle = { on ->
            Prefs.SHUFFLE.setAsync(prefs, on)
            AudioPlayerManager.getInstance().setShuffleMode(on)
            updatePlayerUI()
        },
        setRepeatMode = { mode ->
            Prefs.REPEAT_MODE.setAsync(prefs, mode)
            AudioPlayerManager.getInstance().setRepeatMode(mode)
            updatePlayerUI()
        },
        localize = { t(it) },
        onShowPane = { homeWidgetsController?.refreshWidgets() }
    )

    NavigationManager.getInstance().apply {
        registerController(ScreenState.MainMenu::class.java, mainMenuController)
        registerController(ScreenState.Browser::class.java, browserController)
        registerController(ScreenState.CoverFlow::class.java, coverFlowController)
        registerController(ScreenState.AudiobookCoverFlow::class.java, audiobookCoverFlowController)
        registerController(ScreenState.Playlists::class.java, playlistController)
        registerController(ScreenState.M3uPlaylistSongs::class.java, playlistController)
        registerController(ScreenState.SmartPlaylistSongs::class.java, playlistController)
        registerController(ScreenState.Podcasts::class.java, podcastController)
        registerController(ScreenState.PodcastEpisodes::class.java, podcastController)
        registerController(ScreenState.PodcastManage::class.java, podcastController)
        registerController(ScreenState.Player::class.java, playerController)
        registerController(ScreenState.Settings::class.java, settingsController)
        registerController(ScreenState.Equalizer::class.java, equalizerController)
        registerController(ScreenState.FmRadio::class.java, fmRadioController)
        registerController(ScreenState.FmRadioMenu::class.java, fmRadioController)
        registerController(ScreenState.FmRadioSaved::class.java, fmRadioController)
        registerController(ScreenState.Jellyfin::class.java, jellyfinUiController)
        registerController(ScreenState.WebDav::class.java, webDavUiController)
        registerController(ScreenState.Bluetooth::class.java, bluetoothController)
        registerController(ScreenState.Wifi::class.java, wifiController)
        registerController(ScreenState.WifiKeyboard::class.java, wifiKeyboardController)
        registerController(ScreenState.Brightness::class.java, brightnessController)
        registerController(ScreenState.Storage::class.java, storageController)
        registerController(ScreenState.WebServer::class.java, webServerController)
        registerController(ScreenState.MusicQuiz::class.java, musicQuizController)
        registerController(ScreenState.Videos::class.java, videoBrowserController)
        registerController(ScreenState.VideoPlayer::class.java, videoPlayerController)
        registerController(ScreenState.Games::class.java, gamesController)
        registerController(ScreenState.GamePlayer::class.java, gamePlayerController)
        registerController(ScreenState.Books::class.java, booksController)
        registerController(ScreenState.BookReader::class.java, bookReaderController)
        registerController(ScreenState.Photos::class.java, photosController)
        registerController(ScreenState.PhotoViewer::class.java, photoViewerController)
    }
}

/** iPod "Shuffle Songs": plays the whole music library with Shuffle on, from a random song. */
internal fun MainActivity.shuffleAllSongs() {
    val tracks = MainActivity.customLibrary.map { it.file }
    if (tracks.isEmpty()) {
        Notice.show(this, t("No playable audio files found."))
        return
    }
    Prefs.SHUFFLE.setAsync(prefs, true)
    AudioPlayerManager.getInstance().playTrackList(tracks, tracks.indices.random())
    changeScreen(MainActivity.STATE_PLAYER)
}

/** Now Playing: back to it when it is under this screen (no second copy in the back stack), else open it. */
internal fun MainActivity.showNowPlaying() {
    val nav = NavigationManager.getInstance()
    if (nav.backStackHas { it == ScreenState.Player }) nav.popBackStackTo { it == ScreenState.Player }
    else handleMainMenuAction("OPEN_PLAYER")
}

internal fun MainActivity.handleMainMenuAction(action: String) {
    when (action) {
        "OPEN_BROWSER" -> {
            this.isAudiobookLibraryMode = false
            changeScreen(ScreenState.Browser(BrowserMode.Root))
        }
        "OPEN_VIDEOS" -> {
            changeScreen(ScreenState.Videos(VideoMode.Root))
        }
        "OPEN_PODCASTS" -> {
            changeScreen(ScreenState.Podcasts)
        }
        "OPEN_MUSIC_QUIZ" -> {
            changeScreen(MainActivity.STATE_QUIZ)
            musicQuizController.showStartHub()
        }
        "OPEN_GAMES" -> {
            changeScreen(ScreenState.Games)
        }
        "OPEN_PHOTOS" -> {
            changeScreen(ScreenState.Photos())
        }
        "OPEN_BOOKS" -> {
            changeScreen(ScreenState.Books())
        }
        "OPEN_SETTINGS" -> {
            changeScreen(MainActivity.STATE_SETTINGS)
        }
        "OPEN_RADIO" -> {
            changeScreen(ScreenState.FmRadio)
        }
        "OPEN_WEBSERVER" -> {
            changeScreen(MainActivity.STATE_WEBSERVER)
        }
        "OPEN_BLUETOOTH" -> {
            changeScreen(MainActivity.STATE_BLUETOOTH)
        }
        "OPEN_WIFI" -> {
            changeScreen(MainActivity.STATE_WIFI)
        }
        "OPEN_PLAY_ON" -> {
            com.themoon.y1.cast.PlayOnMenu.open(this)
        }
        "OPEN_AUDIOBOOKS" -> {
            this.isAudiobookLibraryMode = true
            changeScreen(ScreenState.Browser(BrowserMode.Audiobooks, audiobooks = true))
        }
        "OPEN_PLAYER" -> {
            if (currentPlaylist.isEmpty()) {
                Notice.show(this@handleMainMenuAction, t("No music is currently playing."))
            } else {
                changeScreen(MainActivity.STATE_PLAYER)
            }
        }
    }
}
