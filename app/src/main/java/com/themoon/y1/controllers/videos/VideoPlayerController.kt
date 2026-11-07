package com.themoon.y1.controllers.videos

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.core.TimeFormat
import com.themoon.y1.managers.VideoPlayerManager
import com.themoon.y1.managers.VirtualVolumeManager
import com.themoon.y1.navigation.ScreenState
import java.io.File

/**
 * Controller for fullscreen video playback using LibVLC (VideoPlayerManager),
 * progress updates, time formatting, and Click Wheel controls: the wheel changes the volume, and the centre
 * button opens scrub mode ([VideoScrub]), where the wheel moves the playhead.
 * The title and the time bar hide while the video plays, and show at each button push ([VideoOverlayLogic]).
 */
class VideoPlayerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val onExitRequested: () -> Unit,
    private val onAdjustVolume: (up: Boolean) -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutVideoPlayer: FrameLayout = rootView.findViewById(R.id.layout_video_player_mode)
    private val surfaceVideo: SurfaceView = rootView.findViewById(R.id.surface_video)
    private val tvVideoTitle: TextView? = rootView.findViewById(R.id.tv_video_title)
    private val tvVideoTimeCurrent: TextView? = rootView.findViewById(R.id.tv_video_time_current)
    private val tvVideoTimeTotal: TextView? = rootView.findViewById(R.id.tv_video_time_total)
    private val videoProgressBar: ProgressBar? = rootView.findViewById(R.id.video_progress)
    private val layoutVideoControls: View? = rootView.findViewById(R.id.layout_video_controls)
    private val seekVideoScrub: SeekBar? = rootView.findViewById(R.id.seek_video_scrub)

    private val progressHandler = Handler(Looper.getMainLooper())
    private val scrub = VideoScrub()
    private var currentVideoFile: File? = null
    private val overlay = VideoOverlayLogic()
    private val overlayTask = Runnable { refreshOverlay() }

    private val updateProgressTask = object : Runnable {
        override fun run() {
            updateProgressUI()
            refreshOverlay()
            progressHandler.postDelayed(this, 1000)
        }
    }

    override fun onEnter(state: ScreenState) {
        layoutVideoPlayer.visibility = View.VISIBLE
        surfaceVideo.visibility = View.VISIBLE
        if (state is ScreenState.VideoPlayer) {
            play(state.videoFile)
        }
    }

    override fun onExit() {
        stop()
        closeScrub()
        surfaceVideo.visibility = View.GONE
        layoutVideoPlayer.visibility = View.GONE
    }

    fun play(file: File) {
        currentVideoFile = file
        val title = file.nameWithoutExtension
        tvVideoTitle?.text = title

        closeScrub()

        VideoPlayerManager.getInstance().playVideo(activity, file, surfaceVideo)
        showOverlay()
        progressHandler.removeCallbacks(updateProgressTask)
        progressHandler.post(updateProgressTask)
    }

    fun stop() {
        progressHandler.removeCallbacks(updateProgressTask)
        progressHandler.removeCallbacks(overlayTask)
        VideoPlayerManager.getInstance().stopAndRelease()
    }

    /** A new video or a button push: show the title and the time bar, and start the hide delay again. */
    private fun showOverlay() {
        overlay.show(SystemClock.uptimeMillis())
        refreshOverlay()
    }

    /** Show or hide the overlay for the play state now, and set the time of the next check. */
    private fun refreshOverlay() {
        val now = SystemClock.uptimeMillis()
        // In scrub mode the time bar is the scrub bar: it stays until scrub mode closes
        val visible = overlay.update(now, VideoPlayerManager.getInstance().isPlaying() && !scrub.isActive)
        listOf(tvVideoTitle, layoutVideoControls).forEach { v -> v?.let { setOverlayVisible(it, visible) } }
        progressHandler.removeCallbacks(overlayTask)
        val wait = overlay.msUntilHide(now)
        if (wait >= 0) progressHandler.postDelayed(overlayTask, wait)
    }

    private fun setOverlayVisible(v: View, visible: Boolean) {
        if (visible) {
            v.animate().cancel()
            v.alpha = 1f
            v.visibility = View.VISIBLE
        } else if (v.visibility == View.VISIBLE && v.alpha == 1f) {
            v.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction { v.visibility = View.INVISIBLE }.start()
        }
    }

    private fun updateProgressUI() {
        val vm = VideoPlayerManager.getInstance()
        // In scrub mode after a wheel step, the times and the bar show the pending position
        val pos = scrub.shownMs(vm.getCurrentPosition()).toInt()
        val dur = vm.getDuration().toInt()

        tvVideoTimeCurrent?.text = formatTime(pos)
        tvVideoTimeTotal?.text = "-" + formatTime(Math.max(0, dur - pos))
        if (videoProgressBar != null && dur > 0) {
            videoProgressBar.progress = ((pos.toLong() * 100) / dur).toInt()
        }
        if (scrub.isActive) {
            seekVideoScrub?.max = if (dur > 0) dur else 1000
            seekVideoScrub?.progress = pos
        }
    }

    /** Centre button: open scrub mode, or seek to the pending position and close it. */
    private fun onCentre() {
        if (scrub.isActive) {
            scrub.commit()?.let { target ->
                val vm = VideoPlayerManager.getInstance()
                vm.seekRelative(target - vm.getCurrentPosition())
            }
        } else {
            scrub.open()
        }
        showScrub()
    }

    /** Closes scrub mode with no seek. False if it was not open. */
    private fun closeScrub(): Boolean {
        if (!scrub.isActive) return false
        scrub.cancel()
        showScrub()
        return true
    }

    /** Scrub mode shows the scrub bar (diamond thumb) in place of the progress bar, as Now Playing does. */
    private fun showScrub() {
        videoProgressBar?.visibility = if (scrub.isActive) View.GONE else View.VISIBLE
        seekVideoScrub?.visibility = if (scrub.isActive) View.VISIBLE else View.GONE
        updateProgressUI()
        showOverlay()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        // Each button push (also a wheel step) shows the overlay again
        if (action == KeyEvent.ACTION_DOWN) showOverlay()

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
        ) {
            if (action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val vm = VideoPlayerManager.getInstance()
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY && !vm.isPlaying())
                    || (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE && vm.isPlaying())
                ) {
                    vm.togglePlayPause()
                }
                clickFeedback()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (action == KeyEvent.ACTION_DOWN) {
                VideoPlayerManager.getInstance().seekRelative(
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 10000L else -10000L
                )
            }
            return true
        }

        if (keyCode == 21 || keyCode == 22) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (scrub.isActive) {
                    val vm = VideoPlayerManager.getInstance()
                    scrub.step(keyCode == 22, vm.getCurrentPosition(), vm.getDuration())
                    updateProgressUI()
                } else {
                    onAdjustVolume(keyCode == 22)
                }
                clickFeedback()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP && !event.isCanceled) {
                clickFeedback()
                // Back in scrub mode closes it with no seek, as in Now Playing
                if (!closeScrub()) {
                    stop()
                    onExitRequested()
                }
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Play/Pause stays on the Play/Pause button; the centre button opens scrub mode, as in Now Playing
            if (action == KeyEvent.ACTION_UP && !event.isCanceled) {
                clickFeedback()
                onCentre()
            }
            return true
        }

        return false
    }

    override fun onBackPressed(): Boolean {
        if (closeScrub()) return true
        stop()
        onExitRequested()
        return true
    }

    private fun formatTime(ms: Int): String {
        return TimeFormat.format(ms)
    }

    companion object {
        private const val OVERLAY_FADE_MS = 300L
    }
}
