package com.themoon.y1.managers

import android.content.Context
import android.util.Log
import java.util.regex.Pattern

/**
 * Y2-only USB Mass Storage control via MountService binder calls as root.
 * IMountService (KitKat) txn codes, verified on Y2:
 * 3 = isUsbMassStorageConnected(), 4 = setUsbMassStorageEnabled(boolean), 5 = isUsbMassStorageEnabled().
 */
object UsbMassStorageController {
    private const val TAG = "Y1Ums"
    private const val PREFS_NAME = "usb_storage"

    /** USB functions to put back after eject; not set when this app did not switch them. */
    private const val KEY_FUNCTIONS_BEFORE_UMS = "functions_before_ums"

    /** A storage volume as MountService reports it (hidden StorageVolume and StorageManager API). */
    data class Volume(val path: String, val allowMassStorage: Boolean, val emulated: Boolean, val state: String?)

    /**
     * True when MountService can share a volume: one that allows mass storage and is mounted (or already shared).
     * The Y2's internal storage is emulated and never allows it, so only an inserted microSD card can be shared.
     */
    fun canShare(volumes: List<Volume>): Boolean = volumes.any {
        it.allowMassStorage && !it.emulated && (it.state == "mounted" || it.state == "shared")
    }

    /**
     * Whether a volume can be shared now, or null when the hidden API is not available.
     * Without such a volume, setUsbMassStorageEnabled(true) does nothing and [setEnabled] waits for its full timeout.
     */
    fun hasShareableVolume(context: Context): Boolean? {
        return try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) ?: return null
            val volumes = sm.javaClass.getMethod("getVolumeList").invoke(sm) as? Array<*> ?: return null
            val getState = sm.javaClass.getMethod("getVolumeState", String::class.java)
            canShare(volumes.filterNotNull().map { v ->
                val c = v.javaClass
                val path = c.getMethod("getPath").invoke(v) as String
                Volume(
                    path,
                    c.getMethod("allowMassStorage").invoke(v) as Boolean,
                    c.getMethod("isEmulated").invoke(v) as Boolean,
                    getState.invoke(sm, path) as String?
                )
            })
        } catch (e: Exception) {
            Log.w(TAG, "volume list not available", e)
            null
        }
    }

    fun isConnected(): Boolean {
        return parseBool(call("service call mount 3"))
    }
    fun isEnabled(): Boolean {
        return parseBool(call("service call mount 5"))
    }

    /**
     * MountService can share storage only while the USB gadget runs the mass_storage function.
     * The Y2 ROM boots with persist.sys.usb.config=mtp,adb and UsbDeviceManager goes back to that
     * default on each unplug, so switch before each share. `svc usb setFunction` keeps adb if it is on.
     * Returns true when MountService reports UMS as connected (polls up to ~5 s).
     */
    fun ensureMassStorageFunction(context: Context): Boolean {
        if (isConnected()) return true
        val restore = functionsToRestore(ExternalSdMountMonitor.readProp("sys.usb.config"))
        if (restore != null) {
            store(context).edit().putString(KEY_FUNCTIONS_BEFORE_UMS, restore).commit()
            call("svc usb setFunction mass_storage")
        }
        for (i in 0 until 25) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                break
            }
            if (isConnected()) return true
        }
        return isConnected()
    }

    /**
     * Puts back the USB functions that were active before [ensureMassStorageFunction] switched them.
     * The value is in prefs, so this also works when the app was killed and started again during the share.
     */
    fun restoreUsbFunction(context: Context) {
        val prefs = store(context)
        val target = functionsAfterEject(
            prefs.getString(KEY_FUNCTIONS_BEFORE_UMS, null),
            ExternalSdMountMonitor.readProp("sys.usb.config"),
            ExternalSdMountMonitor.readProp("persist.sys.usb.config")
        ) ?: return
        prefs.edit().remove(KEY_FUNCTIONS_BEFORE_UMS).commit()
        // No argument = the default functions (persist.sys.usb.config).
        call(if (target.isEmpty()) "svc usb setFunction" else "svc usb setFunction $target")
    }

    /**
     * The functions to set after eject: the saved ones, or (nothing saved) the default functions ("") when
     * mass_storage is on now but is not the default. Null = no switch.
     */
    fun functionsAfterEject(saved: String?, usbConfig: String?, defaultConfig: String?): String? {
        if (saved != null) return saved
        val now = functionsToRestore(usbConfig)
        val byDefault = functionsToRestore(defaultConfig)
        return if (now == null && byDefault != null) "" else null
    }

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Functions to restore for a sys.usb.config value, without adb (svc adds it back when enabled).
     * Returns null when mass_storage is already on, so no switch is needed.
     */
    fun functionsToRestore(usbConfig: String?): String? {
        val functions = (usbConfig ?: "").split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it != "none" }
        if (functions.contains("mass_storage")) return null
        return functions.filter { it != "adb" }.joinToString(",")
    }

    /**
     * Returns the resulting enabled state. Polls up to ~15 s: when files are open on the volume,
     * vold retries the unmount for ~10 s and then kills the processes that hold them.
     */

    fun setEnabled(enable: Boolean): Boolean {
        call("service call mount 4 i32 " + (if (enable) 1 else 0))
        for (i in 0 until 75) {
            val now = isEnabled()
            if (now == enable) return now
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                break
            }
        }
        return isEnabled()
    }

    private fun call(cmd: String): String {
        val out = ExternalSdMountMonitor.runSuTimed(cmd)
        Log.i(TAG, "$cmd -> $out")
        return out
    }

    /** "Result: Parcel(00000000 00000001   '........')" -> second word == 1 */

    fun parseBool(out: String?): Boolean {
        if (out == null) return false
        val m = Pattern.compile("Parcel\\(\\s*[0-9a-fA-F]{8}\\s+([0-9a-fA-F]{8})").matcher(out)
        return m.find() && Integer.parseInt(m.group(1), 16) != 0
    }
}
