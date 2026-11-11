package com.themoon.y1.controllers.games

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.player.LyricsScreenHold
import com.themoon.y1.games.FliwheelBridge
import com.themoon.y1.games.GameInput
import com.themoon.y1.games.GameKeys
import com.themoon.y1.games.GameLibrary
import com.themoon.y1.games.WheelSensor
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.managers.AudioSources
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.Notice
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Plays an iPod Click Wheel game full screen ([FliwheelBridge]): a render thread draws the 320×240 frames of the
 * core onto [SurfaceView] (4:3, centred), and every button goes to the game ([GameKeys]); the volume buttons stay
 * the app's (InputRouter takes them first).
 *
 * A centre hold, or the screen going off, pauses the game and opens the pause screen: the game art and title over
 * Resume / Quit Game, full screen (as the USB Drive screen). The screen stays on while the game runs (a screen wake
 * lock, as for lyrics).
 *
 * While the game runs, [WheelSensor] reads where the finger is on the wheel (the menu scrolls with the wheel's steps).
 */
class GamePlayerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val onExitRequested: () -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController, SurfaceHolder.Callback {

    private val surface: SurfaceView = rootView.findViewById(R.id.surface_game)
    private val menuLayout: View = rootView.findViewById(R.id.layout_game_menu)
    private val menu = ListScreen(rootView.findViewById(R.id.container_game_menu), clickFeedback = clickFeedback)
    private val menuArt: ImageView = rootView.findViewById(R.id.iv_game_menu_art)
    private val menuTitle: TextView = rootView.findViewById(R.id.tv_game_menu_title)
    private val menuStatus: TextView = rootView.findViewById(R.id.tv_game_menu_status)
    private val handler = Handler(Looper.getMainLooper())
    private val keys = GameKeys()
    private val wheel = WheelSensor { inputs ->
        // A turn that arrives after the menu opened would move the paused game: only the finger's lift goes through
        handler.post { apply(if (isMenuOpen) inputs.filterIsInstance<GameInput.WheelTouch>() else inputs) }
    }

    /** Step keys of the wheel until then are repeats of the wheel sensor (or a step of its switch back): not a turn. */
    private var wheelQuietUntilMs = 0L
    private val screenHold = LyricsScreenHold(activity, "Y1:GameKeepScreenOn")

    private var bridge: FliwheelBridge? = null
    /** Each game start has its own number: a start that ends after its screen closed does nothing. */
    @Volatile
    private var session = 0
    private var isMenuOpen = false
    /** The title and art of the running game, for the pause screen (null art: the gamepad tile). */
    private var gameTitle = ""
    private var gameArt: Bitmap? = null
    /** The centre release of the hold that opened the menu: it must not click a row of the menu. */
    private var dropCentreRelease = false
    /** From the activity's onPause to its onResume: a game that finishes loading then starts paused. */
    private var isInBackground = false

    @Volatile
    private var renderThread: Thread? = null

    @Volatile
    private var hasSurface = false
    private val dst = Rect()

    private val holdTask = Runnable { apply(keys.centreHeld()) }
    /** The last key or wheel input in the game: a game left alone pauses, and the Backlight Timer applies again. */
    private var lastInputMs = 0L
    private val screenHoldTask = object : Runnable {
        override fun run() {
            if (bridge != null && !isMenuOpen && SystemClock.uptimeMillis() - lastInputMs >= IDLE_PAUSE_MS) {
                openMenu(dropRelease = false)
            }
            screenHold.update(bridge != null && !isMenuOpen)
            handler.postDelayed(this, SCREEN_HOLD_TICK_MS)
        }
    }

    init {
        surface.holder.addCallback(this)
        // The surface has the core's size: SurfaceFlinger scales it to the view, not a software canvas each frame
        surface.holder.setFixedSize(FliwheelBridge.WIDTH, FliwheelBridge.HEIGHT)
        rootView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fitSurface(r - l, b - t) }
    }

    /** The surface view as the 4:3 frame, as large as fits in the screen, centred. */
    private fun fitSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val f = fit(width, height)
        val lp = surface.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == f.width() && lp.height == f.height() && lp.gravity == Gravity.CENTER) return
        lp.width = f.width()
        lp.height = f.height()
        lp.gravity = Gravity.CENTER
        surface.layoutParams = lp
    }

    override fun title(): String? = null

    override fun onEnter(state: ScreenState) {
        rootView.visibility = View.VISIBLE
        surface.visibility = View.VISIBLE
        hideMenu()
        if (state is ScreenState.GamePlayer) start(state.bundleDir)
    }

    override fun onExit() {
        stop()
        hideMenu()
        surface.visibility = View.GONE
        rootView.visibility = View.GONE
    }

    /**
     * Before the card is shared over USB: the game reads it (its files, its music), so it ends, as with Quit Game.
     * The share waits for its save ([awaitGameWork]).
     */
    fun endForUsbShare() {
        if (rootView.visibility == View.VISIBLE) onExitRequested()
    }

    /** MainActivity went to the background (screen off, another app): pause the game behind its menu. */
    fun onActivityPause() {
        isInBackground = true
        if (bridge != null && !isMenuOpen) openMenu(dropRelease = false)
        // The core can take seconds to write the saves: not on the main thread
        bridge?.let { b -> work.execute { b.flushSaves() } }
    }

    fun onActivityResume() {
        isInBackground = false
    }

    private fun start(bundleDir: File) {
        stop()
        AudioSources.app.videoStarts()
        val id = ++session
        // Loading a game reads its files: off the main thread
        gameTitle = bundleDir.name
        gameArt = null
        // A black screen while the game loads would look like a crash
        showCover(localize("Please wait..."))
        // After the end of the last game (it may still be saving: this one must see its files)
        work.execute {
            // A start whose screen closed before it ended, and whose end is still in the queue: the core runs one game
            FliwheelBridge.endLeftover()
            if (id != session) return@execute
            val item = GameLibrary.parseGameDir(bundleDir)
            val art = item?.iconFile?.let { decodeArt(it, artPx()) }
            val b = FliwheelBridge()
            val ok = b.init(bundleDir, StoragePaths.getGamesDirs())
            if (ok && id != session) {
                b.destroy()
                return@execute
            }
            activity.runOnUiThread {
                if (id != session) {
                    if (ok) work.execute { b.destroy() }
                    return@runOnUiThread
                }
                item?.let { gameTitle = it.title }
                gameArt = art
                if (!ok) {
                    Notice.show(activity, localize("Failed to start game") + "\n" + localize("Details are in y1_game_log.txt"), long = true)
                    onExitRequested()
                    return@runOnUiThread
                }
                bridge = b
                lastInputMs = SystemClock.uptimeMillis()
                startRenderThread()
                // The screen went off (or another app came up) while the game loaded: it waits behind its menu
                if (isInBackground || (activity as? MainActivity)?.isOnScreen == false) {
                    openMenu(dropRelease = false)
                } else {
                    hideMenu()
                    startWheel()
                    // Every button goes to the game: say how to get out
                    Notice.show(activity, localize("Hold Center to pause or quit."))
                }
                if (!isMenuOpen) handler.post(screenHoldTask)
            }
        }
    }

    private fun stop() {
        session++
        handler.removeCallbacks(holdTask)
        handler.removeCallbacks(screenHoldTask)
        handler.removeCallbacksAndMessages(MENU_KEY_TOKEN)
        screenHold.update(false)
        stopWheel()
        keys.reset()
        val b = bridge ?: return
        bridge = null
        stopRenderThread()
        work.execute { b.saveAndDestroy() }
    }

    // --- Rendering ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        hasSurface = true
        startRenderThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(dst) { dst.set(fit(width, height)) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hasSurface = false
        stopRenderThread()
    }

    private fun startRenderThread() {
        val b = bridge ?: return
        // Paused: the menu covers the game, and no frame comes (the loop would only poll)
        if (!hasSurface || renderThread != null || isMenuOpen) return
        renderThread = Thread({ renderLoop(b) }, "GameRenderLoop").also { it.start() }
    }

    private fun stopRenderThread() {
        val t = renderThread ?: return
        renderThread = null
        try {
            t.join(500)
        } catch (_: InterruptedException) {}
    }

    private fun renderLoop(b: FliwheelBridge) {
        val pixels = IntArray(FliwheelBridge.WIDTH * FliwheelBridge.HEIGHT)
        val frame = Bitmap.createBitmap(FliwheelBridge.WIDTH, FliwheelBridge.HEIGHT, Bitmap.Config.ARGB_8888)
        val src = Rect(0, 0, FliwheelBridge.WIDTH, FliwheelBridge.HEIGHT)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val holder = surface.holder
        while (renderThread === Thread.currentThread()) {
            val started = System.nanoTime()
            if (!b.renderFrame(pixels)) {
                // A game that quit by itself (Tetris's Quit) leaves its last frame up: go back to the list
                if (b.isFinished()) {
                    activity.runOnUiThread { if (bridge === b) onExitRequested() }
                    break
                }
                sleep(5)
                continue
            }
            frame.setPixels(pixels, 0, FliwheelBridge.WIDTH, 0, 0, FliwheelBridge.WIDTH, FliwheelBridge.HEIGHT)
            val canvas = try {
                holder.lockCanvas()
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                // The buffer is 320×240 ([setFixedSize]): the frame is drawn 1:1, the clear is cheap at this size
                canvas.drawColor(Color.BLACK)
                synchronized(dst) { canvas.drawBitmap(frame, src, dst, paint) }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            val leftMs = (FRAME_NS - (System.nanoTime() - started)) / 1_000_000L
            if (leftMs > 1) sleep(leftMs)
        }
        frame.recycle()
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {}
    }

    // --- Keys ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isMenuOpen) return menuKey(event)
        if (bridge == null) {
            // Loading: only Back leaves
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                clickFeedback()
                onExitRequested()
            }
            return true
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        lastInputMs = SystemClock.uptimeMillis()
        if (keys.isCentrePress(event.keyCode, down, event.repeatCount)) {
            handler.removeCallbacks(holdTask)
            handler.postDelayed(holdTask, GameKeys.HOLD_MS)
        } else if (!down && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            handler.removeCallbacks(holdTask)
        }
        apply(keys.onKey(event.keyCode, down, event.repeatCount, event.eventTime))
        return true
    }

    private fun apply(inputs: List<GameInput>) {
        val b = bridge ?: return
        if (inputs.isNotEmpty()) lastInputMs = SystemClock.uptimeMillis()
        for (input in inputs) {
            when (input) {
                is GameInput.Key -> b.sendKey(input.code, input.down)
                is GameInput.Wheel -> b.sendWheel(input.delta)
                is GameInput.WheelTouch -> b.sendWheelTouch(input.angle)
                GameInput.MenuTap -> {
                    b.sendKey(KeyEvent.KEYCODE_MENU, true)
                    handler.postAtTime({ bridge?.sendKey(KeyEvent.KEYCODE_MENU, false) }, MENU_KEY_TOKEN,
                        SystemClock.uptimeMillis() + MENU_TAP_MS)
                }
                GameInput.ExitMenu -> openMenu(dropRelease = true)
            }
        }
    }

    // --- Wheel sensor ---

    /** The game reads where the finger is (after a screen-on too: the kernel's resume turns the sensor off). */
    private fun startWheel() {
        keys.isWheelSensing = wheel.start()
    }

    private fun stopWheel() {
        if (!wheel.isActive) return
        wheel.stop()
        keys.isWheelSensing = false
        wheelQuietUntilMs = SystemClock.uptimeMillis() + WHEEL_QUIET_MS
    }

    // --- Pause screen: Resume / Quit Game ---

    private fun openMenu(dropRelease: Boolean) {
        handler.removeCallbacks(holdTask)
        handler.removeCallbacks(screenHoldTask)
        stopWheel()
        keys.reset()
        bridge?.pause()
        screenHold.update(false)
        isMenuOpen = true
        // Nothing polls while the game is paused (also with the screen off while music plays)
        stopRenderThread()
        dropCentreRelease = dropRelease
        if (dropRelease) clickFeedback()
        showCover(localize("Paused"))
        menu.show(ListSpec(null, listOf(
            menuRow(TablerIcons.PLAYER_PLAY, localize("Resume")) { resumeGame() },
            menuRow(TablerIcons.LOGOUT, localize("Quit Game")) { onExitRequested() }
        ), id = "game_menu"))
    }

    /**
     * The game art and title over [status], full screen and opaque (none of the game shows around it): the pause
     * screen ([openMenu] adds its rows), and the screen while the game loads.
     */
    private fun showCover(status: String) {
        menuLayout.setBackgroundColor(ThemeManager.getOverlayBackgroundColor() or 0xFF000000.toInt())
        val px = artPx()
        menuArt.setImageBitmap(gameArt ?: TablerIcons.renderTile(TablerIcons.DEVICE_GAMEPAD, px, px * 2 / 3,
            ThemeManager.getTextColorPrimary(), 0x22888888, px / 8f))
        menuTitle.text = gameTitle
        menuTitle.setTextColor(ThemeManager.getTextColorPrimary())
        menuTitle.typeface = ThemeManager.getCustomFontBold()
        menuStatus.text = status
        menuStatus.setTextColor(ThemeManager.getTextColorSecondary())
        menuStatus.typeface = ThemeManager.getCustomFont()
        menuLayout.visibility = View.VISIBLE
    }

    private fun menuRow(icon: Array<String>, label: String, onClick: () -> Unit) = RowSpec(label, RowLook.CUSTOM,
        view = ListItemViewFactory.createTablerListButton(activity, icon, label), onClick = onClick)

    private fun artPx(): Int = (ART_DP * activity.resources.displayMetrics.density).toInt()

    /** The game art at [px]×[px] (scaled to fit), with round corners as the gamepad tile; null when it cannot be read. */
    private fun decodeArt(file: File, px: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.let { src ->
            val scale = minOf(px.toFloat() / src.width, px.toFloat() / src.height)
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), px / 8f, px / 8f, paint)
            if (scaled !== src) scaled.recycle()
            src.recycle()
            out
        }
    } catch (_: Throwable) {
        null
    }

    private fun hideMenu() {
        isMenuOpen = false
        dropCentreRelease = false
        menu.clear()
        menuArt.setImageDrawable(null)
        menuLayout.visibility = View.GONE
    }

    private fun resumeGame() {
        lastInputMs = SystemClock.uptimeMillis()
        hideMenu()
        bridge?.resume()
        if (bridge == null) return
        startRenderThread()
        startWheel()
        handler.removeCallbacks(screenHoldTask)
        handler.post(screenHoldTask)
    }

    private fun menuKey(event: KeyEvent): Boolean {
        val up = event.action == KeyEvent.ACTION_UP && !event.isCanceled
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.action == KeyEvent.ACTION_DOWN &&
                event.eventTime >= wheelQuietUntilMs) {
                menu.wheel(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1, event.eventTime)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (up) {
                if (dropCentreRelease) dropCentreRelease = false
                else rootView.findFocus()?.performClick()
            } else if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                dropCentreRelease = false
            }
            KeyEvent.KEYCODE_BACK -> if (up) {
                clickFeedback()
                resumeGame()
            }
        }
        return true
    }

    companion object {
        /**
         * The starts and the ends (saves) of the game sessions, one after the other: the core runs one game at a
         * time, and a game must see the saves of the last one. Made at the first game.
         */
        private val workThread = lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "GameWork") } }
        private val work: ExecutorService by workThread

        /** Waits until the game starts and ends in the queue are done (not on the main thread). False after [timeoutMs]. */
        fun awaitGameWork(timeoutMs: Long): Boolean {
            if (!workThread.isInitialized()) return true
            return try {
                work.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
                true
            } catch (_: Exception) {
                false
            }
        }

        const val FRAME_NS = 16_666_667L
        const val MENU_TAP_MS = 100L
        const val ART_DP = 88
        const val SCREEN_HOLD_TICK_MS = 2_000L
        /** A game with no key or wheel input this long pauses: the screen does not stay on until the battery is empty. */
        const val IDLE_PAUSE_MS = 3 * 60_000L
        const val WHEEL_QUIET_MS = 150L
        val MENU_KEY_TOKEN = Any()

        /** The 4:3 frame, as large as fits in [width]×[height], centred. */
        fun fit(width: Int, height: Int): Rect {
            val w = minOf(width, height * FliwheelBridge.WIDTH / FliwheelBridge.HEIGHT)
            val h = w * FliwheelBridge.HEIGHT / FliwheelBridge.WIDTH
            val x = (width - w) / 2
            val y = (height - h) / 2
            return Rect(x, y, x + w, y + h)
        }
    }
}
