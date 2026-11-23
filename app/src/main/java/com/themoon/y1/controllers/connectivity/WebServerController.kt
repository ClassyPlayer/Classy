package com.themoon.y1.controllers.connectivity

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.Y1WebServer
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.qr.QrBitmap
import com.themoon.y1.views.Notice

/**
 * Controller for the PC Upload HTTP Web Server screen:
 * Toggles HTTP server, shows local Wi-Fi IP address on port 8080 as text and as a QR code for a phone camera,
 * manages WakeLocks and WifiLocks, and triggers library scan on server shutdown.
 */
class WebServerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val onServerStateChanged: (isRunning: Boolean) -> Unit,
    private val onScanMediaRequested: () -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutWebServer: LinearLayout = rootView.findViewById(R.id.layout_webserver_mode)
    private val tvServerStatus: TextView? = rootView.findViewById(R.id.tv_server_status)
    private val tvServerIp: TextView? = rootView.findViewById(R.id.tv_server_ip)
    private val btnServerToggle: Button? = rootView.findViewById(R.id.btn_server_toggle)
    private val ivServerQr: ImageView? = rootView.findViewById(R.id.iv_server_qr)
    private val tvServerHint: TextView? = rootView.findViewById(R.id.tv_server_hint)

    var isServerRunning = false
        private set

    private var webServer: Y1WebServer? = null
    private var serverWakeLock: PowerManager.WakeLock? = null
    private var serverWifiLock: WifiManager.WifiLock? = null

    /** The address and colours of the QR code on the screen, so that [updateUI] draws it again only after a change. */
    private var qrKey: String? = null

    init {
        // The focused button takes the centre press itself, so it must do the toggle
        btnServerToggle?.setOnClickListener {
            clickFeedback()
            toggleServer()
        }
    }

    override fun onEnter(state: ScreenState) {
        layoutWebServer.visibility = View.VISIBLE
        updateUI()
        btnServerToggle?.requestFocus()
    }

    override fun onExit() {
        layoutWebServer.visibility = View.GONE
    }

    override fun onThemeChanged() {
        updateUI()
    }

    fun toggleServer() {
        if (isServerRunning) {
            webServer?.stopServer()
            webServer = null
            isServerRunning = false

            try {
                if (serverWakeLock?.isHeld == true) {
                    serverWakeLock?.release()
                }
                serverWakeLock = null

                if (serverWifiLock?.isHeld == true) {
                    serverWifiLock?.release()
                }
                serverWifiLock = null

                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (ignored: Exception) {}

            onScanMediaRequested()
            onServerStateChanged(false)
        } else {
            val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm == null || !wm.isWifiEnabled) {
                Notice.show(activity, localize("Turn on Wi-Fi first"))
                return
            }

            webServer = Y1WebServer(activity.applicationContext)
            try {
                webServer?.start()
                isServerRunning = true

                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
                serverWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y1:WebServerLock")
                serverWakeLock?.acquire()

                serverWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "Y1:WebServerWifiLock")
                serverWifiLock?.acquire()

                onServerStateChanged(true)
            } catch (e: Exception) {
                Notice.error(activity, "${localize("Server failed:")} ${e.message}")
                isServerRunning = false
            }
        }
        updateUI()
    }

    fun updateUI() {
        var qrShown = false
        if (isServerRunning) {
            tvServerStatus?.text = localize("SERVER RUNNING")
            tvServerStatus?.setTextColor(ThemeManager.getTextColorPrimary())
            val ip = webServer?.getLocalIpAddress() ?: "127.0.0.1"
            val url = "http://$ip:8080"
            tvServerIp?.text = url
            tvServerIp?.setTextColor(ThemeManager.getTextColorPrimary())
            btnServerToggle?.text = localize("STOP SERVER")
            qrShown = showQr(if (isLanAddress(ip)) url else null)
        } else {
            tvServerStatus?.text = localize("SERVER STOPPED")
            tvServerStatus?.setTextColor(ThemeManager.getTextColorSecondary())
            tvServerIp?.text = "http://---.---.---.---:8080"
            tvServerIp?.setTextColor(ThemeManager.getTextColorSecondary())
            btnServerToggle?.text = localize("START SERVER")
            showQr(null)
        }
        tvServerHint?.text = localize(
            if (qrShown) "Scan the code with a phone, or open the address in a browser."
            else "Open this address in a browser on your computer or phone."
        )
        tvServerHint?.setTextColor(ThemeManager.getTextColorSecondary())
        // The button is narrower next to the QR code, so that both fit on the screen
        btnServerToggle?.layoutParams?.let { params ->
            val width = dp(if (qrShown) BUTTON_WIDTH_WITH_QR_DP else BUTTON_WIDTH_DP)
            if (params.width != width) {
                params.width = width
                btnServerToggle.layoutParams = params
            }
        }
    }

    /** Shows the QR code of [url], or hides it for null. False when no code shows. */
    private fun showQr(url: String?): Boolean {
        val view = ivServerQr ?: return false
        val colorA = ThemeManager.getTextColorPrimary()
        val colorB = ThemeManager.getOverlayBackgroundColor()
        val key = "$url $colorA $colorB"
        if (url != null && key != qrKey) {
            val bitmap = QrBitmap.create(url, dp(QR_MAX_SIZE_DP), colorA, colorB)
            view.setImageBitmap(bitmap)
            qrKey = if (bitmap != null) key else null
        }
        if (url == null || qrKey == null) {
            view.visibility = View.GONE
            view.setImageDrawable(null)
            qrKey = null
            return false
        }
        view.visibility = View.VISIBLE
        return true
    }

    /** True for an IPv4 address that another device on the Wi-Fi can open (not 0.0.0.0 or 127.x.x.x). */
    private fun isLanAddress(ip: String): Boolean =
        ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && ip != "0.0.0.0" && !ip.startsWith("127.")

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Toggle on release (onKeyUp or the button click); a hold sent repeats that toggled again and again
            return true
        }

        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            clickFeedback()
            toggleServer()
            return true
        }
        return false
    }

    private companion object {
        const val BUTTON_WIDTH_DP = 350
        const val BUTTON_WIDTH_WITH_QR_DP = 240
        const val QR_MAX_SIZE_DP = 170
    }
}
