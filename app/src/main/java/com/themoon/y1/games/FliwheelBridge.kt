package com.themoon.y1.games

import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import com.themoon.y1.StoragePaths
import com.themoon.y1.managers.VirtualVolumeManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One game session of the fliwheel core (libfliwheel_android.so, built from fliwheel/android).
 *
 * The core runs the game on its own thread from [init] to [destroy]. This class takes the frames
 * ([renderFrame]), gives it the buttons, and plays the sounds the core asks for: .wav files as effects
 * (SoundPool), .m4a/.mp3/.aac as the looping music of the game (MediaPlayer). Both follow the app volume
 * ([VirtualVolumeManager]).
 *
 * The JNI names are bound to this class name (com.themoon.y1.games.FliwheelBridge): keep it, or build the core again.
 */
class FliwheelBridge {
    @Volatile
    private var handle = 0L

    @Volatile
    private var isRunning = false
    private var audioThread: Thread? = null
    private var soundPool: SoundPool? = null
    private val sounds = HashMap<String, Int>()
    private val loadedSounds = HashSet<Int>()
    private var music: MediaPlayer? = null
    @Volatile
    private var isPaused = false
    /** The audio thread waits on it while the game is paused (no polling). */
    private val pauseLock = Object()
    private var hasSaves = false

    /** Starts the game in [bundleDir], with its saves in [saveRoots]' GameData_RW. False: see [lastError] and y1_game_log.txt. */
    @Synchronized
    fun init(bundleDir: File, saveRoots: List<File>): Boolean {
        lastError = null
        if (handle != 0L) destroy()
        if (!ensureLoaded()) return false

        val bundle = GameLibrary.bundleDir(bundleDir)
        log("init: ${bundle.absolutePath}")
        handle = try {
            startCore(bundle, GameLibrary.saveDir(bundle, saveRoots))
        } catch (t: Throwable) {
            lastError = "nativeInit: ${t.message}"
            log(lastError + "\n" + Log.getStackTraceString(t))
            return false
        }
        if (handle == 0L) {
            lastError = "The core could not load the game (check that the game is decrypted)"
            log(lastError!!)
            return false
        }
        running = this

        @Suppress("DEPRECATION") // SoundPool.Builder is API 21
        soundPool = SoundPool(4, AudioManager.STREAM_MUSIC, 0).also { pool ->
            pool.setOnLoadCompleteListener { p, id, status ->
                if (status != 0) return@setOnLoadCompleteListener
                synchronized(loadedSounds) { loadedSounds += id }
                // The first time a sound plays, it plays when it has loaded
                if (!isPaused) playSound(p, id)
            }
        }
        isRunning = true
        audioThread = Thread({ pollAudio() }, "FliwheelAudioPoll").also { it.start() }
        return true
    }

    /** Copies the latest frame (320×240 ARGB) into [out]. False when there is no new frame. */
    @Synchronized
    fun renderFrame(out: IntArray): Boolean =
        handle != 0L && out.size >= WIDTH * HEIGHT && nativeRenderFrame(handle, out)

    @Synchronized
    fun sendKey(keyCode: Int, down: Boolean) {
        if (handle != 0L) nativeSendKey(handle, keyCode, down)
    }

    @Synchronized
    fun sendWheel(delta: Float) {
        if (handle != 0L) nativeSendWheel(handle, delta)
    }

    /** Where a finger rests on the wheel ([WheelSensor]): the core's touch angle, null when it lifts. */
    @Synchronized
    fun sendWheelTouch(angle: Int?) {
        if (handle == 0L || !hasWheelTouch) return
        try {
            nativeSendWheelTouch(handle, angle ?: -1)
        } catch (e: UnsatisfiedLinkError) {
            // An older core (the ROM's libfliwheel_android.so): the wheel still turns, without the touch angle
            hasWheelTouch = false
            log("nativeSendWheelTouch missing: ${e.message}")
        }
    }

    /** True once the game has quit by itself (its own Quit, after saving): close the session. */
    @Synchronized
    fun isFinished(): Boolean {
        if (handle == 0L || !hasFinishedCheck) return false
        return try {
            nativeIsFinished(handle)
        } catch (e: UnsatisfiedLinkError) {
            // An older core (the ROM's libfliwheel_android.so): the game stays on its last screen
            hasFinishedCheck = false
            log("nativeIsFinished missing: ${e.message}")
            false
        }
    }

    private fun startCore(bundle: File, saves: File): Long = try {
        nativeInitWithSaves(bundle.absolutePath, saves.absolutePath).also { hasSaves = true }
    } catch (e: UnsatisfiedLinkError) {
        // An older core (the ROM's libfliwheel_android.so): it starts, without saves
        log("nativeInitWithSaves missing: ${e.message}")
        nativeInit(bundle.absolutePath)
    }

    /** Writes what the game has saved so far to the card. */
    @Synchronized
    fun flushSaves() {
        if (handle != 0L && hasSaves) nativeFlushSaves(handle)
    }

    /** Ends the game after it had the chance to save, which can take seconds: not on the main thread. */
    fun saveAndDestroy() {
        synchronized(this) {
            if (handle != 0L && hasSaves) nativeRequestQuit(handle)
        }
        destroy()
    }

    /** Stops the game's time and its music (the Resume / Quit Game menu, screen off). */
    @Synchronized
    fun pause() {
        if (handle == 0L || isPaused) return
        isPaused = true
        setNativePaused(true)
        try {
            music?.takeIf { it.isPlaying }?.pause()
        } catch (_: Exception) {}
        soundPool?.autoPause()
    }

    @Synchronized
    fun resume() {
        if (handle == 0L || !isPaused) return
        isPaused = false
        synchronized(pauseLock) { pauseLock.notifyAll() }
        setNativePaused(false)
        try {
            music?.start()
        } catch (_: Exception) {}
        soundPool?.autoResume()
    }

    /** An older core (a ROM with the first libfliwheel_android.so) has no pause: then only the frames and the sound stop. */
    private fun setNativePaused(paused: Boolean) {
        try {
            nativeSetPaused(handle, paused)
        } catch (e: UnsatisfiedLinkError) {
            log("nativeSetPaused missing: ${e.message}")
        }
    }

    /** Ends the game. The audio thread stops first: no native call may run while the core frees the session. */
    fun destroy() {
        if (running === this) running = null
        isRunning = false
        audioThread?.let { t ->
            t.interrupt()
            try {
                t.join()
            } catch (_: InterruptedException) {}
        }
        audioThread = null
        synchronized(this) {
            if (handle != 0L) {
                nativeDestroy(handle)
                handle = 0L
            }
            releaseMusic()
            soundPool?.release()
            soundPool = null
            sounds.clear()
            synchronized(loadedSounds) { loadedSounds.clear() }
            isPaused = false
        }
        log("session ended")
    }

    private fun pollAudio() {
        while (isRunning) {
            if (isPaused) {
                // A paused game asks for no sound: wait for resume (or destroy, which interrupts) instead of polling
                try {
                    synchronized(pauseLock) { while (isPaused && isRunning) pauseLock.wait() }
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            val path = try {
                val h = handle
                if (h == 0L) null else nativePollAudio(h)
            } catch (t: Throwable) {
                Log.e(TAG, "Audio poll failed", t)
                null
            }
            if (path.isNullOrEmpty()) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (isMusic(path)) playMusic(path) else synchronized(this) { if (isRunning) play(path) }
        }
    }

    private fun playSound(pool: SoundPool, id: Int) {
        val gain = VirtualVolumeManager.getInstance().getMasterGain()
        pool.play(id, gain, gain, 1, 0, 1f)
    }

    private fun releaseMusic() {
        val player = music ?: return
        music = null
        VirtualVolumeManager.getInstance().unregisterMediaPlayer(player)
        try {
            player.release()
        } catch (_: Exception) {}
    }

    private fun isMusic(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        return lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".aac")
    }

    /** A sound effect (.wav). Runs under the bridge lock. */
    private fun play(path: String) {
        if (!File(path).isFile || !path.lowercase(Locale.US).endsWith(".wav")) return
        try {
            val pool = soundPool ?: return
            val id = sounds[path]
            if (id == null) {
                sounds[path] = pool.load(path, 1) // plays when it has loaded
            } else if (synchronized(loadedSounds) { id in loadedSounds }) {
                playSound(pool, id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not play $path", e)
        }
    }

    /**
     * The game's looping music. It is prepared outside the bridge lock, which the keys, the wheel and the frames
     * also take: reading the track from the card must not hold them up. A track that cannot play is released.
     */
    private fun playMusic(path: String) {
        if (!File(path).isFile) return
        val player = MediaPlayer()
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            player.setDataSource(path)
            player.prepare()
            player.isLooping = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not play $path", e)
            try {
                player.release()
            } catch (_: Exception) {}
            return
        }
        synchronized(this) {
            if (!isRunning) {
                try {
                    player.release()
                } catch (_: Exception) {}
                return
            }
            releaseMusic()
            VirtualVolumeManager.getInstance().registerMediaPlayer(player)
            music = player
            if (isPaused) return
            try {
                player.start()
            } catch (e: Exception) {
                Log.w(TAG, "Could not play $path", e)
            }
        }
    }

    companion object {
        @Volatile private var running: FliwheelBridge? = null

        /** Ends a session that no screen shows (its start ended after its screen closed): the core runs one game at a time. */
        fun endLeftover() {
            running?.destroy()
        }

        private const val TAG = "FliwheelBridge"
        const val WIDTH = 320
        const val HEIGHT = 240

        /** Why the last [init] failed, for the error message. */
        @Volatile
        var lastError: String? = null
            private set

        private var isLoaded = false

        @Volatile
        private var hasWheelTouch = true
        @Volatile
        private var hasFinishedCheck = true

        /**
         * Loads the core. On an adb install Android unpacks it into the app's lib folder; in the ROM
         * (/system/app, where KitKat does not unpack libraries) build-rom.sh puts it into /system/lib.
         */
        @Synchronized
        private fun ensureLoaded(): Boolean {
            if (isLoaded) return true
            try {
                System.loadLibrary("fliwheel_android")
                isLoaded = true
            } catch (t: Throwable) {
                lastError = "Could not load libfliwheel_android.so: ${t.message}"
                log(lastError!!)
            }
            return isLoaded
        }

        /** Appends to y1_game_log.txt on the internal storage; the core writes its own lines there too. */
        fun log(message: String) {
            Log.i(TAG, message)
            try {
                FileWriter(StoragePaths.primaryFile("y1_game_log.txt"), true).use {
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    it.write("$time [FliwheelBridge] $message\n")
                }
            } catch (_: Exception) {}
        }

        @JvmStatic private external fun nativeInit(bundlePath: String): Long
        @JvmStatic private external fun nativeInitWithSaves(bundlePath: String, saveRoot: String): Long
        @JvmStatic private external fun nativeFlushSaves(handle: Long)
        @JvmStatic private external fun nativeRequestQuit(handle: Long)
        @JvmStatic private external fun nativeIsFinished(handle: Long): Boolean
        @JvmStatic private external fun nativeRenderFrame(handle: Long, outPixels: IntArray): Boolean
        @JvmStatic private external fun nativeSendKey(handle: Long, keyCode: Int, isDown: Boolean)
        @JvmStatic private external fun nativeSendWheel(handle: Long, delta: Float)
        @JvmStatic private external fun nativeSendWheelTouch(handle: Long, angle: Int)
        @JvmStatic private external fun nativePollAudio(handle: Long): String?
        @JvmStatic private external fun nativeSetPaused(handle: Long, paused: Boolean)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
