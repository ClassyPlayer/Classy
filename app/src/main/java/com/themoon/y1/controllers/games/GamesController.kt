package com.themoon.y1.controllers.games

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.games.GameItem
import com.themoon.y1.games.GameLibrary
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.TablerIcons
import java.io.File

/**
 * Main menu → Games: the iPod Click Wheel games in the Games folders ([GameLibrary]), and Rescan Games.
 * The first visit scans the folders on a worker thread: the ready games show at once, and the .ipg files that need
 * unpacking join the list one by one (a progress row at the end). The list stays in memory until Rescan Games or
 * the end of a USB share ([forget]).
 */
class GamesController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val onGameSelected: (bundleDir: File) -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val rowViews: LinearLayout = rootView.findViewById(R.id.container_game_items)
    private val screen = ListScreen(rowViews, clickFeedback = clickFeedback)

    private var games: List<GameItem>? = null
    private val icons = HashMap<File, Bitmap>()
    var isScanning = false
        private set
    private var progress: String? = null
    /** The .ipg files of the last scan that could not be read. */
    private var failed: List<String> = emptyList()

    override fun title(): String = localize("Games")

    override fun onEnter(state: ScreenState) {
        rootView.visibility = View.VISIBLE
        if (games == null && !isScanning) scan() else show()
    }

    override fun onExit() {
        rootView.visibility = View.GONE
        screen.clear()
    }

    override fun onThemeChanged() {
        show()
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta, event.eventTime)

    private fun isShown(): Boolean = rootView.visibility == View.VISIBLE

    /** The games on the card may have changed (a USB share): the next visit scans again, or now if the list shows. */
    fun forget() {
        if (isScanning) return
        games = null
        failed = emptyList()
        icons.clear()
        if (isShown()) scan()
    }

    private fun scan() {
        isScanning = true
        progress = localize("Indexing games...")
        show()
        val art = HashMap<File, Bitmap>()
        val bad = ArrayList<String>()
        Thread({
            val found = GameLibrary.scan(StoragePaths.getGamesDirs(), onFailed = { bad += it }) { sofar, unpacking ->
                loadIcons(sofar, art)
                val shown = sofar.toList()
                val icons = HashMap(art)
                activity.runOnUiThread {
                    this.icons.putAll(icons)
                    games = shown
                    progress = unpacking?.let { String.format(localize("Extracting %s..."), it) }
                    if (isShown()) show()
                }
            }
            loadIcons(found, art)
            activity.runOnUiThread {
                icons.putAll(art)
                games = found
                failed = bad.toList()
                isScanning = false
                progress = null
                if (isShown()) show()
            }
        }, "GameScanner").start()
    }

    private fun show() {
        rootView.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        val rows = ArrayList<RowSpec>()
        val list = games
        list?.forEach { rows += gameRow(it) }
        if (!isScanning) failed.forEach { rows += RowSpec(String.format(localize("Could not read %s"), it), RowLook.INFO) }
        if (isScanning) {
            rows += RowSpec(progress ?: localize("Indexing games..."), RowLook.INFO, key = KEY_PROGRESS)
        } else {
            if (list.isNullOrEmpty()) rows += RowSpec(
                localize("No games found.\nCopy iPod games (.ipg files or game folders) into the \"Games\" folder on your SD card, then select Rescan Games."),
                RowLook.INFO
            )
            val rescan = ListItemViewFactory.createTablerListButton(activity, TablerIcons.REFRESH, localize("Rescan Games"))
            rows += RowSpec(localize("Rescan Games"), RowLook.CUSTOM, view = rescan, key = KEY_RESCAN, onClick = { scan() })
        }
        val spec = ListSpec(localize("Games"), rows)
        // Keep the selected row while the list grows; with no row selected yet (on entry, or the progress row only),
        // select the first. Only a row counts: the ScrollView takes the focus when the screen before it hides.
        if (screen.spec?.id == spec.id && rowViews.hasFocus()) screen.update(spec) else screen.show(spec)
    }

    private fun gameRow(game: GameItem) = RowSpec(game.title, RowLook.ART, key = game.bundleDir.absolutePath,
        image = { iv ->
            val icon = icons[game.bundleDir]
            if (icon != null) iv.setImageBitmap(icon)
            else iv.setImageBitmap(TablerIcons.renderTile(TablerIcons.DEVICE_GAMEPAD, ICON_PX, ICON_PX * 2 / 3,
                ThemeManager.getTextColorPrimary(), 0x22888888, ICON_PX / 8f))
        },
        onClick = { onGameSelected(game.bundleDir) })

    /** Decodes the art of the [games] that are not in [art] yet (worker thread). */
    private fun loadIcons(games: List<GameItem>, art: HashMap<File, Bitmap>) {
        for (g in games) {
            if (g.bundleDir in art) continue
            g.iconFile?.let { f -> decodeIcon(f)?.let { art[g.bundleDir] = it } }
        }
    }

    /** The game art, scaled down to the row size as it decodes. */
    private fun decodeIcon(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_PX && bounds.outHeight / (sample * 2) >= ICON_PX) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val KEY_RESCAN = "\u0000rescan"
        const val KEY_PROGRESS = "\u0000progress"
        const val ICON_PX = 128
    }
}
