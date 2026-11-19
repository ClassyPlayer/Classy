package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager

/** Wi-Fi and Bluetooth on/off for the Power screen. The Bluetooth start is the one of BluetoothController. */
object RadioSwitch {
    fun wifiOn(c: Context): Boolean = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    fun toggleWifi(c: Context) {
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wm.isWifiEnabled = !wm.isWifiEnabled
    }

    fun bluetoothOn(): Boolean = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun toggleBluetooth(runOnUi: (Runnable) -> Unit) {
        val ba = BluetoothAdapter.getDefaultAdapter() ?: return
        if (ba.isEnabled) {
            ba.disable()
        } else {
            // The Y2 Bluetooth service must run first, else enable() does nothing
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop ctl.start mtkbt")).waitFor()
                    Thread.sleep(1500)
                } catch (_: Exception) {}
                runOnUi(Runnable { ba.enable() })
            }.start()
        }
    }
}
