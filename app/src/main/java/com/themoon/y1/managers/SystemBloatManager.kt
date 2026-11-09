package com.themoon.y1.managers

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.themoon.y1.AppPrefs
import com.themoon.y1.DeviceModel

/**
 * Turns off Android parts that the Y2 does not use: the status bar, the lockscreen, the keyboard,
 * the window animations and some MediaTek daemons. It runs in its own thread at each app start, so the library
 * does not wait for it.
 *
 * The status bar goes off with its service only (`SystemUIService`), not with the whole SystemUI package: the package also has
 * the only wallpaper service (`ImageWallpaper`). Without a wallpaper window, Android 4.4 enables input only at its 30 s
 * boot timeout, so the click wheel does nothing for about 30 s after each boot.
 *
 * Packages and settings: Android keeps them (`package-restrictions.xml`, `settings.db`), so they are set once.
 * The step is recorded only when a check of the real state passes; if root fails, the next start tries again.
 * After that the app does not touch them, so a manual `pm enable` (for example to find a fault) stays.
 *
 * Daemons: init starts them again at each boot, so they are stopped at each start with `ctl.stop` (no Java VM).
 * Their files stay executable: without the execute bit, init restarts them every 5 s.
 *
 * Not touched: `com.android.phone`, `rild` and `gsm0710muxd`. In airplane mode the MTK RIL powers the modem off,
 * and [CellRadioManager] keeps airplane mode on for the cell radio.
 */
object SystemBloatManager {
    private const val TAG = "Y1SystemBloat"

    /** Raise it when [ENABLED], [DISABLED] or [SETTINGS] change, so that devices apply the new list once. */
    internal const val VERSION = 2
    private const val KEY_APPLIED_VERSION = "system_bloat_applied_version"

    /** Version 1 disabled the whole SystemUI package (see the class comment). */
    internal val ENABLED = listOf("com.android.systemui")

    /** Packages, or components as `package/class`. */
    internal val DISABLED = listOf(
        "com.android.systemui/com.android.systemui.SystemUIService",
        "com.android.keyguard",
        "com.android.inputmethod.latin",
        "com.android.providers.userdictionary",
    )

    internal data class Setting(val table: String, val name: String, val value: String)

    /** `animator_duration_scale` stays: at 0 every `View.animate()` in the app (Cover Flow, menu focus) jumps to its end. */
    internal val SETTINGS = listOf(
        Setting("global", Settings.Global.WINDOW_ANIMATION_SCALE, "0"),
        Setting("global", Settings.Global.TRANSITION_ANIMATION_SCALE, "0"),
        Setting("secure", "lockscreen.disabled", "1"),
    )

    /** init service names (not file names) of daemons that the Y2 cannot use: logs, analog TV, OTA, A-GPS, MTK device management. */
    internal val SERVICES = listOf("mobile_log_d", "netdiag", "matv", "agpsd", "fotabinder", "DMAgent", "PPLAgent")

    @Volatile
    private var started = false

    /** Root shell command for the packages, components and settings that are not yet in their state. Enables come first. */
    internal fun applyCommand(enable: List<String>, disable: List<String>, settings: List<Setting>): String =
        (enable.map { "pm enable $it" } + disable.map { "pm disable $it" } +
            settings.map { "settings put ${it.table} ${it.name} ${it.value}" })
            .joinToString("; ")

    /** Root shell command that stops the services, or null if none runs. [state]: value of `init.svc.<name>`. */
    internal fun stopCommand(state: (String) -> String?): String? {
        val running = SERVICES.filter { state(it) == "running" || state(it) == "restarting" }
        return if (running.isEmpty()) null else running.joinToString("; ") { "setprop ctl.stop $it" }
    }

    internal fun settingMatches(actual: String?, wanted: String): Boolean =
        actual?.toFloatOrNull()?.let { it == wanted.toFloat() } ?: false

    /** Call at app start. */
    fun start(context: Context) {
        if (!DeviceModel.isY2(Build.MODEL) || started) return
        started = true
        val appContext = context.applicationContext
        Thread({
            stopServices()
            applyOnce(appContext)
        }, "SystemBloat").start()
    }

    private fun stopServices() {
        val command = stopCommand(::systemProperty) ?: return
        if (FmRadioManager.runSuQuiet(command)) {
            Log.i(TAG, "Stopped: $command")
        } else {
            Log.w(TAG, "Could not stop the daemons (root?)")
        }
    }

    private fun applyOnce(appContext: Context) {
        val prefs = AppPrefs.get(appContext)
        if (prefs.getInt(KEY_APPLIED_VERSION, 0) >= VERSION) return

        val enable = ENABLED.filter { isDisabled(appContext, it) }
        val disable = DISABLED.filter { !isDisabled(appContext, it) }
        val settings = SETTINGS.filter { !hasValue(appContext, it) }
        if (enable.isNotEmpty() || disable.isNotEmpty() || settings.isNotEmpty()) {
            FmRadioManager.runSuQuiet(applyCommand(enable, disable, settings), timeoutMs = 30_000)
        }

        val failed = ENABLED.filter { isDisabled(appContext, it) } + DISABLED.filter { !isDisabled(appContext, it) } +
            SETTINGS.filter { !hasValue(appContext, it) }.map { it.name }
        if (failed.isEmpty()) {
            prefs.edit().putInt(KEY_APPLIED_VERSION, VERSION).apply()
            Log.i(TAG, "Applied version $VERSION (changed: $enable $disable ${settings.map { it.name }})")
        } else {
            Log.w(TAG, "Not applied, tries again at the next start: $failed")
        }
    }

    /** True when the package or component (`package/class`) is disabled, or its package is not on this ROM. */
    private fun isDisabled(context: Context, name: String): Boolean = try {
        val component = ComponentName.unflattenFromString(name)
        val pm = context.packageManager
        when (if (component != null) pm.getComponentEnabledSetting(component) else pm.getApplicationEnabledSetting(name)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> true
            else -> false
        }
    } catch (_: IllegalArgumentException) {
        true
    }

    private fun hasValue(context: Context, setting: Setting): Boolean {
        val resolver = context.contentResolver
        val actual = try {
            if (setting.table == "global") Settings.Global.getString(resolver, setting.name)
            else Settings.Secure.getString(resolver, setting.name)
        } catch (_: Throwable) {
            null
        }
        return settingMatches(actual, setting.value)
    }

    private fun systemProperty(service: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "init.svc.$service") as String?
    } catch (_: Exception) {
        null
    }
}
