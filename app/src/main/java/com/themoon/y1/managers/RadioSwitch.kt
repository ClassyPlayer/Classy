package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/** Wi-Fi and Bluetooth on/off, for the Power screen and the Bluetooth screen. */
object RadioSwitch {
    fun wifiOn(c: Context): Boolean = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    /** What Android did with a request: [DONE] (the switch shows the new state), [BUSY] (a start is under way), [FAILED]. */
    enum class Request { DONE, BUSY, FAILED }

    /** [on]: the state the row shows after the press. Android reports "off" while Wi-Fi is still starting, so it is not read again. */
    fun setWifi(c: Context, on: Boolean): Request {
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return Request.FAILED
        return if (wm.setWifiEnabled(on)) Request.DONE else Request.FAILED
    }

    fun bluetoothOn(): Boolean = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

    /** True from the press that starts Bluetooth until its enable() call: a second press waits. */
    @Volatile
    private var bluetoothStarting = false

    private const val TAG = "RadioSwitch"

    /**
     * Asks Bluetooth to be [on] (the state the row shows after the press), as [setWifi] does for Wi-Fi. The Bluetooth
     * screen and the Power screen both call it, so there is one start sequence. [onFailed] runs on the UI thread when
     * the later enable() call is refused.
     */
    @SuppressLint("MissingPermission")
    fun setBluetooth(runOnUi: (Runnable) -> Unit, on: Boolean, onFailed: () -> Unit): Request {
        val ba = BluetoothAdapter.getDefaultAdapter() ?: return Request.FAILED
        val state = ba.state
        // While Bluetooth starts or stops isEnabled is false: a press that asks for the other state waits
        if (!on) {
            if (ba.isEnabled) return if (ba.disable()) Request.DONE else Request.FAILED
            return if (bluetoothStarting || state == BluetoothAdapter.STATE_TURNING_ON) Request.BUSY else Request.DONE
        }
        if (ba.isEnabled) return Request.DONE
        if (bluetoothStarting || state == BluetoothAdapter.STATE_TURNING_ON || state == BluetoothAdapter.STATE_TURNING_OFF) return Request.BUSY
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
        return Request.DONE
    }
}

/**
 * What a radio was asked to become. The Power row shows it until the radio is there (or [LIFE_MS] pass), so a
 * refresh when the other radio finishes, or a screen opened during the start, does not show the old state.
 */
class PendingRadio {
    private var on: Boolean? = null
    private var at = 0L

    fun set(target: Boolean, nowMs: Long) { on = target; at = nowMs }

    fun clear() { on = null }

    /** What the row shows: the target while it is pending, else the real state [real]. */
    fun shown(real: Boolean, nowMs: Long): Boolean = on?.takeIf { nowMs - at < LIFE_MS } ?: real

    /** The radio is on or off now: a target it has reached is done (another state: the request is still under way). */
    fun reached(real: Boolean) {
        if (on == real) on = null
    }

    companion object {
        const val LIFE_MS = 10_000L
    }
}

