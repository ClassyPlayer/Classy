package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.views.Notice

/** Wi-Fi and Bluetooth on/off, for the Power screen, the Wi-Fi screen and the Bluetooth screen. */
object RadioSwitch {
    private const val TAG = "RadioSwitch"

    /** What Android did with a request: [DONE] (the radio starts or stops), [BUSY] (it starts or stops already), [FAILED]. */
    enum class Request { DONE, BUSY, FAILED }

    /** The value of a radio row: "Wait..." while the radio starts or stops ([on] = null), else On or Off. */
    fun valueText(on: Boolean?, localize: (String) -> String): String = if (on == null) localize("Wait...") else onOff(on, localize)

    /** Tells the user when a [request] is [Request.BUSY] or [Request.FAILED]. True for [Request.DONE]. */
    fun tell(context: Context, request: Request, localize: (String) -> String): Boolean = when (request) {
        Request.DONE -> true
        Request.BUSY -> {
            Notice.show(context, localize("Please wait a moment..."))
            false
        }
        Request.FAILED -> {
            Notice.error(context, localize("Failed"))
            false
        }
    }

    fun wifiManager(c: Context): WifiManager? = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** On or off; null while Wi-Fi starts or stops (the switch shows "Wait..."). */
    fun wifiState(c: Context): Boolean? = wifiShown(wifiManager(c)?.wifiState ?: WifiManager.WIFI_STATE_DISABLED)

    internal fun wifiShown(state: Int): Boolean? = when (state) {
        WifiManager.WIFI_STATE_ENABLED -> true
        WifiManager.WIFI_STATE_ENABLING, WifiManager.WIFI_STATE_DISABLING -> null
        else -> false
    }

    fun setWifi(c: Context, on: Boolean): Request {
        val wm = wifiManager(c) ?: return Request.FAILED
        if (wifiShown(wm.wifiState) == null) return Request.BUSY
        return if (wm.setWifiEnabled(on)) Request.DONE else Request.FAILED
    }

    /** True from the press that starts Bluetooth until its enable() call (the Y2 service starts first, ~2 s). */
    @Volatile
    private var bluetoothStarting = false

    /** On or off; null while Bluetooth starts or stops (the switch shows "Wait..."). */
    fun bluetoothState(): Boolean? = bluetoothShown(BluetoothAdapter.getDefaultAdapter()?.state ?: BluetoothAdapter.STATE_OFF, bluetoothStarting)

    internal fun bluetoothShown(state: Int, starting: Boolean): Boolean? = when {
        starting || state == BluetoothAdapter.STATE_TURNING_ON || state == BluetoothAdapter.STATE_TURNING_OFF -> null
        else -> state == BluetoothAdapter.STATE_ON
    }

    internal enum class BluetoothStep { NOTHING, START, STOP, BUSY }

    internal fun bluetoothStep(on: Boolean, state: Int, starting: Boolean): BluetoothStep {
        val shown = bluetoothShown(state, starting) ?: return BluetoothStep.BUSY
        return when {
            shown == on -> BluetoothStep.NOTHING
            on -> BluetoothStep.START
            else -> BluetoothStep.STOP
        }
    }

    /**
     * Asks Bluetooth to be [on]. The Bluetooth screen and the Power screen both call it, so there is one start
     * sequence. [onFailed] runs on the UI thread when the later enable() call is refused, or when it is not called.
     */
    @SuppressLint("MissingPermission")
    fun setBluetooth(runOnUi: (Runnable) -> Unit, on: Boolean, onFailed: () -> Unit): Request {
        val ba = BluetoothAdapter.getDefaultAdapter() ?: return Request.FAILED
        return when (bluetoothStep(on, ba.state, bluetoothStarting)) {
            BluetoothStep.NOTHING -> Request.DONE
            BluetoothStep.BUSY -> Request.BUSY
            BluetoothStep.STOP -> if (ba.disable()) Request.DONE else Request.FAILED
            BluetoothStep.START -> {
                bluetoothStarting = true
                // The Y2 Bluetooth service must run first, else enable() does nothing
                Thread {
                    try {
                        val code = Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop ctl.start mtkbt")).waitFor()
                        if (code != 0) Log.w(TAG, "starting the Bluetooth service (su) exited with $code: enable() may do nothing")
                        Thread.sleep(1500)
                    } catch (e: Exception) {
                        Log.w(TAG, "starting the Bluetooth service failed: enable() may do nothing", e)
                    }
                    runOnUi(Runnable {
                        bluetoothStarting = false
                        if (!ba.enable()) onFailed()
                    })
                }.start()
                Request.DONE
            }
        }
    }
}
