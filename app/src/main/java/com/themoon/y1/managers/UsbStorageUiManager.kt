package com.themoon.y1.managers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.controllers.games.GamePlayerController
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.views.Notice
import com.themoon.y1.views.TablerIcons

/** Longest wait for the card on the Y2 after the share (MountService mounts it again after the card check). */
private const val CARD_BACK_MS = 20_000L
/** The longest wait for the save of a game that ends for a share (the core can take seconds). */
private const val GAME_SAVE_WAIT_MS = 10_000L

class UsbStorageUiManager(
    private val activity: Activity,
    private val getExternalSdMountMonitor: () -> ExternalSdMountMonitor?,
    private val isFmRadioActive: () -> Boolean,
    private val stopFmRadio: () -> Unit,
    private val localize: (String) -> String,
    private val clickFeedback: () -> Unit,
    private val createListButtonWithIcon: (iconUnicode: String, textLabel: String) -> View
) {
    var usbStorageOverlay: View? = null
        private set
    /** The line under the title of the share screen: "Ejecting…" while the share ends. */
    private var shareLine: TextView? = null
    /** The selected row under the USB Drive screen: it is selected again after Eject. */
    private var focusBeforeOverlay: View? = null
    var usbStorageBusy: Boolean = false
        private set
    /** The key of the USB prompt screen (ActionScreen.kt), or null. */
    private var usbPromptScreen: Int? = null
        private set
    /** One question for each cable plug (see [UsbPromptGate]). */
    val promptGate = UsbPromptGate()

    val isStorageOverlayActive: Boolean
        get() = usbStorageOverlay != null

    val isDialogShowing: Boolean
        get() = usbPromptScreen?.let { NavigationManager.getInstance().currentState == ActionScreens.route(it) } == true

    fun dismissUsbStorageDialog() {
        usbPromptScreen?.let { ActionScreens.close(it) }
        usbPromptScreen = null
    }

    /** A computer is connected: the sticky USB_STATE (a charger does not set it). Settings → Connectivity → Use as USB Drive shows then. */
    val isComputerConnected: Boolean
        get() = activity.registerReceiver(null, IntentFilter(USB_STATE))?.getBooleanExtra("connected", false) == true

    /** A cable plug (once for each plug): what Settings → Connectivity → USB Drive says ([UsbDrive]). */
    fun onComputerConnected(setting: Int) {
        when (UsbDrive.onPlug(setting, UsbMassStorageController.hasShareableVolume(activity))) {
            UsbDrive.OnPlug.ASK -> showUsbStorageDialog()
            UsbDrive.OnPlug.LEND -> {
                promptGate.onAnswered()
                startUsbStorage()
            }
            UsbDrive.OnPlug.NOTHING -> {}
        }
    }

    /** USB cable in: Not Now (first, selected) or Use as USB Drive, on a screen of its own. Back is Not Now. */
    fun showUsbStorageDialog() {
        dismissUsbStorageDialog()
        val player = AudioPlayerManager.getInstance()
        val line = if (UsbDrive.musicStops(player.isPlaying(), player.currentlyPlayingFilePath, StoragePaths.SECONDARY_PATH)) {
            localize("Music will stop. Use the card as a USB drive?")
        } else {
            localize("Use the microSD card as a USB drive?")
        }
        usbPromptScreen = ActionScreens.open(ActionScreens.Spec(
            localize("Computer Connected"),
            listOf(line),
            listOf(
                ActionScreens.Action(localize("Not Now")) {
                    usbPromptScreen = null
                    promptGate.onAnswered()
                },
                ActionScreens.Action(localize("Use as USB Drive")) {
                    usbPromptScreen = null
                    promptGate.onAnswered()
                    startUsbStorage()
                }
            ),
            onCancel = {
                usbPromptScreen = null
                promptGate.onAnswered()
            }
        ))
    }

    private fun pauseAllPlaybackForUsb() {
        try {
            val am = AudioPlayerManager.getInstance()
            if (am.isPlaying()) {
                am.playOrPauseMusic()
            }
        } catch (_: Exception) {}
        try {
            val vm = VideoPlayerManager.getInstance()
            if (vm.isPlaying()) {
                vm.togglePlayPause()
            }
        } catch (_: Exception) {}
        try {
            if (isFmRadioActive()) {
                stopFmRadio()
            }
        } catch (_: Exception) {}
    }

    fun startUsbStorage() {
        if (usbStorageBusy) return
        val main = activity as? MainActivity
        if (main?.isLibraryCacheLoading == true) {
            // The cache load checks each file on the card, and the restore of the play queue runs after it: during
            // the share they see only part of the files. Start when they are done (their callbacks run first).
            main.loadLibraryCache { if (isComputerConnected && !isStorageOverlayActive) startUsbStorage() }
            return
        }
        if (MediaLibraryManager.getInstance().isScanning) {
            // The scan keeps files open; vold would kill this app to unmount the volume.
            Notice.error(activity, localize("Wait for the library scan to finish, then try again."))
            return
        }
        if (main?.isGameScanRunning() == true) {
            // The game scan reads the game folders and unpacks .ipg files on the card
            Notice.error(activity, localize("Wait for the game scan to finish, then try again."))
            return
        }
        if (UsbMassStorageController.hasShareableVolume(activity) == false) {
            // Nothing to share (no card): do not switch the USB function, so the computer keeps MTP.
            Notice.error(activity, localize("No microSD card."))
            return
        }
        usbStorageBusy = true
        val musicPlayed = AudioPlayerManager.getInstance().isPlaying()
        pauseAllPlaybackForUsb()
        // A game reads the card (its files, its music): it ends first, and the share waits for its save below
        main?.endGameForUsbShare()
        // An open track file on the card makes MountService kill this app during the share.
        AudioPlayerManager.getInstance().releaseForUsbStorage(musicPlayed)
        getExternalSdMountMonitor()?.setSuspended(true)
        Thread({
            val gameDone = GamePlayerController.awaitGameWork(GAME_SAVE_WAIT_MS)
            val ok = gameDone && run {
                UsbMassStorageController.ensureMassStorageFunction(activity)
                UsbMassStorageController.setEnabled(true)
            }
            if (!ok && gameDone) {
                UsbMassStorageController.restoreUsbFunction(activity)
            }
            activity.runOnUiThread {
                usbStorageBusy = false
                if (ok) {
                    showUsbStorageOverlay()
                } else {
                    reloadAfterShare(resume = true)
                    Notice.error(activity, localize("Card busy. Try again."))
                }
            }
        }, "y1-ums-on").start()
    }

    /** Eject; [end]: why the share ends (the notice after it). */
    fun stopUsbStorage(end: UsbDrive.End = UsbDrive.End.EJECT) {
        if (usbStorageBusy) return
        usbStorageBusy = true
        shareLine?.text = localize("Ejecting…")
        Thread({
            val stillOn = UsbMassStorageController.setEnabled(false)
            if (!stillOn) {
                UsbMassStorageController.restoreUsbFunction(activity)
            }
            activity.runOnUiThread {
                if (stillOn) {
                    usbStorageBusy = false
                    shareLine?.text = localize("Eject on the computer first.")
                    Notice.error(activity, localize("Couldn't eject USB storage."))
                    return@runOnUiThread
                }
                // The computer has no card now: close the share screen at once
                onShareEnded(end, reload = false)
            }
            if (stillOn) return@Thread
            // The Y2 mounts the card again a few seconds later (after the card check): then load the track again.
            // Until then no new share starts (busy).
            val deadline = android.os.SystemClock.uptimeMillis() + CARD_BACK_MS
            while (ExternalSdMountMonitor.hasSecondarySlot() && !ExternalSdMountMonitor.isSecondaryReady() &&
                android.os.SystemClock.uptimeMillis() < deadline
            ) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
            }
            activity.runOnUiThread {
                usbStorageBusy = false
                reloadAfterShare(resume = UsbDrive.resumesMusic(end))
            }
        }, "y1-ums-off").start()
    }

    /** The card is back (or gone): close the share screen and say how the share ended. [reload]: load the track again now. */
    private fun onShareEnded(end: UsbDrive.End, reload: Boolean = true) {
        hideUsbStorageOverlay()
        if (reload) reloadAfterShare(resume = UsbDrive.resumesMusic(end))
        when (end) {
            UsbDrive.End.EJECT -> Notice.show(activity, localize("OK to disconnect."))
            UsbDrive.End.CABLE_OUT -> Notice.error(activity, localize("Card not ejected."))
            UsbDrive.End.CARD_REMOVED -> Notice.error(activity, localize("microSD card removed."))
        }
    }

    /**
     * The card is back on the Y2 (or the share failed): watch the card again and load the track again.
     * [resume]: play again if the music played before the share.
     */
    private fun reloadAfterShare(resume: Boolean) {
        getExternalSdMountMonitor()?.setSuspended(false)
        val main = activity as? MainActivity ?: return
        AudioPlayerManager.getInstance().reloadAfterUsbStorage(main, resume)
        // The computer may have added or removed games
        main.forgetGames()
    }

    fun onUsbCableRemovedWhileShared() {
        Handler(Looper.getMainLooper()).postDelayed({
            Thread({
                if (UsbMassStorageController.isEnabled()) {
                    activity.runOnUiThread { stopUsbStorage(UsbDrive.End.CABLE_OUT) }
                } else {
                    // The share is off already (MountService ends it when the computer goes): put back the USB function
                    UsbMassStorageController.restoreUsbFunction(activity)
                    activity.runOnUiThread { if (isStorageOverlayActive) onShareEnded(UsbDrive.End.CABLE_OUT) }
                }
            }, "y1-ums-unplug").start()
        }, 1500)
    }

    fun showUsbStorageOverlay() {
        if (usbStorageOverlay != null) return
        // Also the share that is still on after an app restart: Eject must not ask again.
        promptGate.onAnswered()
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        focusBeforeOverlay = activity.currentFocus
        val d = activity.resources.displayMetrics.density

        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(ThemeManager.getOverlayBackgroundColor() or 0xFF000000.toInt())
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = (24 * d).toInt()
                rightMargin = (24 * d).toInt()
            }
        }

        val iconSizePx = (96 * d).toInt()
        val iv = ImageView(activity).apply {
            setImageBitmap(TablerIcons.render(TablerIcons.USB, iconSizePx, ThemeManager.getTextColorPrimary()))
            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * d).toInt()
            }
        }
        content.addView(iv)

        val tvTitle = TextView(activity).apply {
            text = localize("USB Drive")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 22f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * d).toInt())
        }
        content.addView(tvTitle)

        val tvSubtitle = TextView(activity).apply {
            text = localize(if (usbStorageBusy) "Ejecting…" else "Eject on the computer first.")
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 14f
            typeface = ThemeManager.getCustomFont()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (24 * d).toInt())
        }
        content.addView(tvSubtitle)
        shareLine = tvSubtitle

        val btnEject = createListButtonWithIcon("", localize("Eject")).apply {
            layoutParams = LinearLayout.LayoutParams(
                (180 * d).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                clickFeedback()
                stopUsbStorage()
            }
        }
        content.addView(btnEject)

        overlay.addView(content)
        root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        usbStorageOverlay = overlay
        watchCardRemoval()

        try {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}

        btnEject.postDelayed({ btnEject.requestFocus() }, 60)
    }

    fun hideUsbStorageOverlay() {
        if (usbStorageOverlay != null) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root?.removeView(usbStorageOverlay)
            usbStorageOverlay = null
            shareLine = null
            cardRemovedReceiver?.let { try { activity.unregisterReceiver(it) } catch (_: Exception) {} }
            cardRemovedReceiver = null
            // Without this, Android selects another row of the screen under it
            focusBeforeOverlay?.takeIf { it.isAttachedToWindow }?.requestFocus()
            focusBeforeOverlay = null
        }
        try {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }

    /** While the card is lent: its removal closes the share screen ("microSD card removed."). */
    private var cardRemovedReceiver: BroadcastReceiver? = null

    private fun watchCardRemoval() {
        if (cardRemovedReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.data?.path == StoragePaths.SECONDARY_PATH && isStorageOverlayActive) {
                    stopUsbStorage(UsbDrive.End.CARD_REMOVED)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        try {
            activity.registerReceiver(receiver, filter)
            cardRemovedReceiver = receiver
        } catch (_: Exception) {}
    }

    companion object {
        const val USB_STATE = "android.hardware.usb.action.USB_STATE"
    }
}
