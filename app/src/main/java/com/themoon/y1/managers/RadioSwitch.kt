package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager

/** Wi-Fi and Bluetooth on/off for the Power screen. The Bluetooth start is the one of BluetoothController. */
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

    /** [onFailed] runs on the UI thread when the later enable() call is refused. */
    @SuppressLint("MissingPermission")
    fun toggleBluetooth(runOnUi: (Runnable) -> Unit, onFailed: () -> Unit): Request {
        val ba = BluetoothAdapter.getDefaultAdapter() ?: return Request.FAILED
        if (ba.isEnabled) return if (ba.disable()) Request.DONE else Request.FAILED
        // While Bluetooth starts or stops isEnabled is false: a press then waits, it does not start it again
        if (bluetoothStarting || ba.state == BluetoothAdapter.STATE_TURNING_ON || ba.state == BluetoothAdapter.STATE_TURNING_OFF) {
            return Request.BUSY
        }
        bluetoothStarting = true
        // The Y2 Bluetooth service must run first, else enable() does nothing
        Thread {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop ctl.start mtkbt")).waitFor()
                Thread.sleep(1500)
            } catch (_: Exception) {}
            runOnUi(Runnable {
                bluetoothStarting = false
                if (!ba.enable()) onFailed()
            })
        }.start()
        return Request.DONE
    }
}
