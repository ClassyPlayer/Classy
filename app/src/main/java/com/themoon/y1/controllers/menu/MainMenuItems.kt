package com.themoon.y1.controllers.menu

import com.themoon.y1.Prefs

/** The main menu items. [MenuItem.action] goes to MainActivity.handleMainMenuAction. */
object MainMenuItems : MenuItems(
    listOf(
        MenuItem("btn_now", "Now Playing", "OPEN_PLAYER"),
        MenuItem("btn_music", "Music", "OPEN_BROWSER"),
        MenuItem("btn_videos", "Videos", "OPEN_VIDEOS"),
        MenuItem("btn_photos", "Photos", "OPEN_PHOTOS"),
        MenuItem("btn_musicquiz", "Music Quiz", "OPEN_MUSIC_QUIZ"),
        MenuItem("btn_games", "Games", "OPEN_GAMES"),
        MenuItem("btn_podcasts", "Podcasts", "OPEN_PODCASTS"),
        MenuItem("btn_audiobooks", "Audiobooks", "OPEN_AUDIOBOOKS"),
        MenuItem("btn_books", "Books", "OPEN_BOOKS"),
        MenuItem("btn_radio", "FM Radio", "OPEN_RADIO"),
        MenuItem("btn_bt", "Bluetooth", "OPEN_BLUETOOTH"),
        MenuItem("btn_wifi", "Wi-Fi", "OPEN_WIFI"),
        MenuItem("btn_play_on", "Play On", "OPEN_PLAY_ON"),
        MenuItem("btn_set", "Settings", "OPEN_SETTINGS"),
        MenuItem("btn_web", "Web Server", "OPEN_WEBSERVER")
    ),
    Prefs.MENU_ORDER, Prefs.MAIN_MENU_HIDDEN
) {
    const val PHOTOS = "btn_photos"
}
