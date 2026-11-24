package com.themoon.y1.controllers.menu

import com.themoon.y1.Prefs

/**
 * The Music menu items (BrowserController.openMusicMenuItem opens each id). Album Artists is hidden until the user
 * shows it: like the iPod, the Music menu has Artists and Compilations.
 */
object MusicMenuItems : MenuItems(
    listOf(
        MenuItem("coverflow", "Cover Flow"),
        MenuItem("playlists", "Playlists"),
        MenuItem("track_artists", "Artists"),
        MenuItem("artists", "Album Artists"),
        MenuItem("albums", "Albums"),
        MenuItem("compilations", "Compilations"),
        MenuItem("songs", "Songs"),
        MenuItem("shuffle_songs", "Shuffle Songs"),
        MenuItem("genres", "Genres"),
        MenuItem("composers", "Composers"),
        MenuItem("moods", "Moods"),
        MenuItem("years", "Years"),
        MenuItem("recently_added", "Recently Added"),
        MenuItem("favorites", "My Favorites"),
        MenuItem("folders", "Folders"),
        MenuItem("search", "Search")
    ),
    Prefs.MUSIC_MENU_ORDER, Prefs.MUSIC_MENU_HIDDEN, hiddenByDefault = setOf("artists")
)
