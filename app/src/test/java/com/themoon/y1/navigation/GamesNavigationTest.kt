package com.themoon.y1.navigation

import android.view.ViewGroup
import com.themoon.y1.LanguageFiles
import com.themoon.y1.controllers.ScreenController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/** Main menu → Games → a game; quitting the game goes back to the list, and Back to the main menu. */
class GamesNavigationTest {

    private class Fake(
        override val rootView: ViewGroup = NavigationManagerTest.createDummyViewGroup()
    ) : ScreenController {
        var exits = 0
        override fun onExit() {
            exits++
        }
    }

    private val nav = NavigationManager.getInstance()
    private val player = Fake()

    @Before
    fun setUp() {
        nav.reset()
        nav.registerController(ScreenState.MainMenu::class.java, Fake())
        nav.registerController(ScreenState.Games::class.java, Fake())
        nav.registerController(ScreenState.GamePlayer::class.java, player)
        nav.navigateTo(ScreenState.MainMenu)
    }

    @After
    fun tearDown() {
        nav.reset()
    }

    @Test
    fun quitGoesBackToTheListAndBackToTheMainMenu() {
        val game = File("/storage/sdcard1/Games/Tetris")
        nav.navigateTo(ScreenState.Games)
        nav.navigateTo(ScreenState.GamePlayer(game))
        assertEquals(ScreenState.GamePlayer(game), nav.currentState)

        nav.popBackStack()
        assertEquals(ScreenState.Games, nav.currentState)
        // Leaving the game screen ends the game (GamePlayerController.onExit)
        assertEquals(1, player.exits)

        nav.popBackStack()
        assertEquals(ScreenState.MainMenu, nav.currentState)
    }

    @Test
    fun gameStringsAreTranslated() {
        LanguageFiles.assertEachFileTranslates(listOf(
            "Games",
            "No games found.\nCopy iPod games (.ipg files or game folders) into the \"Games\" folder on your SD card, then select Rescan Games.",
            "Rescan Games", "Indexing games...", "Extracting %s...",
            "This game could not start. The file may be encrypted or damaged.",
            "Games need the latest firmware. Install it with System Update.", "Resume", "Quit Game"
        ))
    }
}
