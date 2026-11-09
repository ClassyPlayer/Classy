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
 *
 * MediaTek's "SD card ready / change the default write disk?" dialog: MountService opens it after a card is inserted
 * and mounted. Its OK button only opens Android's storage settings, and the "default write disk" is read by stock MTK
 * apps only, not by this app. MountService opens it in a try/catch after the mount, so with the dialog disabled the
 * mount goes on. It is an activity of package `android`, so it goes off with DONT_KILL_APP ([DISABLED_DONT_KILL]):
 * `pm disable` would force-stop `android`, which cancels the system's own alarms (the time tick).
 *
 * The keyboard: Android keeps it as the input method after `pm disable`, and each window focus of the app then throws
 * "Unknown id" (a crash at each start until a reboot). So `ime disable` removes it first, while it is still enabled
 * ([keyboardImes]); on a device where version 3 disabled it already, the keyboard is enabled again for that step.
 */
object SystemBloatManager {
    private const val TAG = "Y1SystemBloat"

    /**
     * Raise it when [ENABLED], [DISABLED], [DISABLED_DONT_KILL] or [SETTINGS] change, so that devices apply the new
     * list once.
     */
    internal const val VERSION = 4
    private const val KEY_APPLIED_VERSION = "system_bloat_applied_version"

    /** The keyboard package (LatinIME). */
    internal const val KEYBOARD = "com.android.inputmethod.latin"

    /** Version 1 disabled the whole SystemUI package (see the class comment). */
    internal val ENABLED = listOf("com.android.systemui")

    /** Packages, or components as `package/class`. */
    internal val DISABLED = listOf(
        "com.android.systemui/com.android.systemui.SystemUIService",
        "com.android.keyguard",
        KEYBOARD,
        "com.android.providers.userdictionary",
    )

    /** Components (`package/class`) of packages that must not be force-stopped: disabled with DONT_KILL_APP. */
    internal val DISABLED_DONT_KILL = listOf(
        "android/com.mediatek.storage.StorageDefaultPathDialog",
    )

    /** `IPackageManager.TRANSACTION_setComponentEnabledSetting` in the framework.jar of the Y2 ROM (Android 4.4). */
    private const val TRANSACTION_SET_COMPONENT_ENABLED_SETTING = 61

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

    /**
     * Root shell command for the packages, components and settings that are not yet in their state. Enables come
     * first, then `ime disable` of the input methods [imes] (before their package is disabled), then the disables.
     */
    internal fun applyCommand(
        enable: List<String>,
        disable: List<String>,
        settings: List<Setting>,
        imes: List<String> = emptyList()
    ): String =
        (enable.map { "pm enable $it" } + imes.map { "ime disable $it" } + disable.map { "pm disable $it" } +
            settings.map { "settings put ${it.table} ${it.name} ${it.value}" })
            .joinToString("; ")

    /**
     * The input methods of the [KEYBOARD] package in the secure settings `default_input_method` ([default]) and
     * `enabled_input_methods` ([enabled]: `id;subtype;subtype:id...`).
     */
    internal fun keyboardImes(default: String?, enabled: String?): List<String> =
        (listOfNotNull(default) + enabled.orEmpty().split(':').map { it.substringBefore(';') })
            .filter { it.startsWith("$KEYBOARD/") }
            .distinct()

    /**
     * Root shell command that disables [component] (`package/class`) with DONT_KILL_APP, for when the app has no
     * CHANGE_COMPONENT_ENABLED_STATE (`pm disable` has no such flag). Arguments: the ComponentName (non-null, package,
     * class), state DISABLED, flags DONT_KILL_APP, user 0.
     */
    internal fun disableDontKillCommand(component: String): String {
        val (pkg, cls) = component.split('/', limit = 2)
        return "service call package $TRANSACTION_SET_COMPONENT_ENABLED_SETTING i32 1 s16 $pkg s16 $cls " +
            "i32 ${PackageManager.COMPONENT_ENABLED_STATE_DISABLED} i32 ${PackageManager.DONT_KILL_APP} i32 0"
    }

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

        // `ime disable` needs the keyboard enabled: when it is disabled already, enable it for this step
        val imes = keyboardImesOn(appContext)
        val keyboardAgain = imes.isNotEmpty() && isDisabled(appContext, KEYBOARD)
        val enable = ENABLED.filter { isDisabled(appContext, it) } + if (keyboardAgain) listOf(KEYBOARD) else emptyList()
        val disable = DISABLED.filter { !isDisabled(appContext, it) || (it == KEYBOARD && keyboardAgain) }
        val settings = SETTINGS.filter { !hasValue(appContext, it) }
        if (enable.isNotEmpty() || disable.isNotEmpty() || settings.isNotEmpty() || imes.isNotEmpty()) {
            FmRadioManager.runSuQuiet(applyCommand(enable, disable, settings, imes), timeoutMs = 30_000)
        }
        val disableDontKill = DISABLED_DONT_KILL.filter { !isDisabled(appContext, it) }
        disableDontKill.forEach { disableWithoutKill(appContext, it) }

        val failed = ENABLED.filter { isDisabled(appContext, it) } +
            (DISABLED + DISABLED_DONT_KILL).filter { !isDisabled(appContext, it) } +
            SETTINGS.filter { !hasValue(appContext, it) }.map { it.name } +
            keyboardImesOn(appContext)
        if (failed.isEmpty()) {
            prefs.edit().putInt(KEY_APPLIED_VERSION, VERSION).apply()
            Log.i(TAG, "Applied version $VERSION (changed: $enable $disable $disableDontKill ${settings.map { it.name }})")
        } else {
            Log.w(TAG, "Not applied, tries again at the next start: $failed")
        }
    }

    /** With the system permission (the app is in /system/app) through the package manager, else with root. */
    private fun disableWithoutKill(context: Context, component: String) {
        val name = ComponentName.unflattenFromString(component) ?: return
        try {
            context.packageManager.setComponentEnabledSetting(
                name,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: SecurityException) {
            FmRadioManager.runSuQuiet(disableDontKillCommand(component))
        } catch (e: IllegalArgumentException) {
            // Not on this ROM: isDisabled counts it as off
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

    /** [keyboardImes] of the secure settings of this device. */
    private fun keyboardImesOn(context: Context): List<String> {
        val resolver = context.contentResolver
        return try {
            keyboardImes(
                Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD),
                Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS)
            )
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun systemProperty(service: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "init.svc.$service") as String?
    } catch (_: Exception) {
        null
    }
}
