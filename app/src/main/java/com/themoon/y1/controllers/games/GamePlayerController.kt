package com.themoon.y1.controllers.games

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.player.LyricsScreenHold
import com.themoon.y1.games.FliwheelBridge
import com.themoon.y1.games.GameInput
import com.themoon.y1.games.GameKeys
import com.themoon.y1.games.WheelSensor
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.managers.AudioSources
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.Notice
import java.io.File

/**
 * Plays an iPod Click Wheel game full screen ([FliwheelBridge]): a render thread draws the 320×240 frames of the
 * core onto [SurfaceView] (4:3, centred), and every button goes to the game ([GameKeys]); the volume buttons stay
 * the app's (InputRouter takes them first).
 *
 * A centre hold, or the screen going off, pauses the game and opens the Resume / Quit Game menu. The screen stays
 * on while the game runs (a screen wake lock, as for lyrics).
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
    private var session = 0
    private var isMenuOpen = false
    /** The centre release of the hold that opened the menu: it must not click a row of the menu. */
    private var dropCentreRelease = false

    @Volatile
    private var renderThread: Thread? = null

    @Volatile
    private var hasSurface = false
    private val dst = Rect()

    private val holdTask = Runnable { apply(keys.centreHeld()) }
    private val screenHoldTask = object : Runnable {
        override fun run() {
            screenHold.update(bridge != null && !isMenuOpen)
            handler.postDelayed(this, SCREEN_HOLD_TICK_MS)
        }
    }

    init {
        surface.holder.addCallback(this)
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

    /** MainActivity went to the background (screen off, another app): pause the game behind its menu. */
    fun onActivityPause() {
        if (bridge != null && !isMenuOpen) openMenu(dropRelease = false)
    }

    private fun start(bundleDir: File) {
        stop()
        AudioSources.app.videoStarts()
        val id = ++session
        // Loading a game reads its files: off the main thread
        Thread({
            val b = FliwheelBridge()
            val ok = b.init(bundleDir)
            activity.runOnUiThread {
                if (id != session) {
                    if (ok) Thread({ b.destroy() }, "GameDestroy").start()
                    return@runOnUiThread
                }
                if (!ok) {
                    Notice.show(activity, localize("Failed to start game") + "\n" + localize("Details are in y1_game_log.txt"), long = true)
                    onExitRequested()
                    return@runOnUiThread
                }
                bridge = b
                startWheel()
                startRenderThread()
                handler.post(screenHoldTask)
            }
        }, "GameStart").start()
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
        b.destroy()
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
        if (!hasSurface || renderThread != null) return
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

    // --- Resume / Quit Game menu ---

    private fun openMenu(dropRelease: Boolean) {
        handler.removeCallbacks(holdTask)
        stopWheel()
        keys.reset()
        bridge?.pause()
        screenHold.update(false)
        isMenuOpen = true
        dropCentreRelease = dropRelease
        if (dropRelease) clickFeedback()
        menuLayout.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        menuLayout.visibility = View.VISIBLE
        menu.show(ListSpec(null, listOf(
            RowSpec(localize("Resume"), onClick = { resumeGame() }),
            RowSpec(localize("Quit Game"), onClick = { onExitRequested() })
        ), id = "game_menu"))
    }

    private fun hideMenu() {
        isMenuOpen = false
        dropCentreRelease = false
        menu.clear()
        menuLayout.visibility = View.GONE
    }

    private fun resumeGame() {
        hideMenu()
        bridge?.resume()
        if (bridge != null) startWheel()
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

    private companion object {
        const val FRAME_NS = 16_666_667L
        const val MENU_TAP_MS = 100L
        const val SCREEN_HOLD_TICK_MS = 2_000L
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
