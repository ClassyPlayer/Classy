package com.themoon.y1.migrations

import android.os.Build
import android.util.Log
import com.themoon.y1.DeviceModel

/**
 * Migration 7: Strips down unused Android system bloat on Y2:
 * - Disables SystemUI (removes status bar, notification tickers, alert peeks, and transient pull-down shades).
 * - Disables Telephony stack (Phone, TelephonyProvider, Contacts) and cellular radio.
 * - Disables LatinIME keyboard, Keyguard lockscreen, and system animations.
 * - Strips permissions (chmod 000) on unused MediaTek telemetry and cellular daemons in /system/bin.
 * - Ensures ro.radio.noril=1 and ro.carrier=wifi-only in /system/build.prop.
 * - Persists strip configuration to /system/etc/install-recovery.sh.
 */
class Migration007StripSystemBloat(
    private val model: String? = Build.MODEL,
    private val rootExecutor: (List<String>) -> Boolean = ::defaultRootExecutor
) : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Strip unused Android bloat (SystemUI, Telephony, Keyguard, LatinIME, MTK telemetry)"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        if (!DeviceModel.isY2(model)) {
            return
        }

        val commands = listOf(
            // 1. Disable unused packages
            "pm disable com.android.systemui 2>/dev/null",
            "pm disable com.android.phone 2>/dev/null",
            "pm disable com.android.providers.telephony 2>/dev/null",
            "pm disable com.android.providers.contacts 2>/dev/null",
            "pm disable com.android.inputmethod.latin 2>/dev/null",
            "pm disable com.android.providers.userdictionary 2>/dev/null",
            "pm disable com.android.keyguard 2>/dev/null",

            // 2. Disable window animations and lockscreen
            "settings put global window_animation_scale 0 2>/dev/null",
            "settings put global transition_animation_scale 0 2>/dev/null",
            "settings put global animator_duration_scale 0 2>/dev/null",
            "settings put secure lockscreen.disabled 1 2>/dev/null",

            // 3. Disable cellular radio in settings
            "settings put global airplane_mode_radios cell 2>/dev/null",
            "settings put global airplane_mode_on 1 2>/dev/null",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true 2>/dev/null",

            // 4. Remount /system and disable unused MTK telemetry and cellular daemons
            "mount -o remount,rw /system 2>/dev/null",
            "chmod 000 /system/bin/matv /system/bin/mobile_log_d /system/bin/fotabinder /system/bin/mtk_agpsd " +
                "/system/bin/netdiag /system/bin/dm_agent_binder /system/bin/ppl_agent /system/bin/rild " +
                "/system/bin/gsm0710muxd /system/bin/mdlogger 2>/dev/null",

            // 5. Burn noril property into /system/build.prop if missing
            "if [ -f /system/build.prop ] && ! grep -q '^ro.radio.noril' /system/build.prop; then " +
                "echo 'ro.radio.noril=1' >> /system/build.prop; " +
                "echo 'ro.carrier=wifi-only' >> /system/build.prop; " +
                "chmod 644 /system/build.prop 2>/dev/null; " +
                "fi",

            // 6. Ensure /system/etc/install-recovery.sh contains disable commands on boot
            "if [ -f /system/etc/install-recovery.sh ] && ! grep -q 'com.android.systemui' /system/etc/install-recovery.sh; then " +
                "echo 'pm disable com.android.systemui 2>/dev/null' >> /system/etc/install-recovery.sh; " +
                "echo 'pm disable com.android.phone 2>/dev/null' >> /system/etc/install-recovery.sh; " +
                "echo 'pm disable com.android.keyguard 2>/dev/null' >> /system/etc/install-recovery.sh; " +
                "echo 'settings put global window_animation_scale 0 2>/dev/null' >> /system/etc/install-recovery.sh; " +
                "echo 'settings put global transition_animation_scale 0 2>/dev/null' >> /system/etc/install-recovery.sh; " +
                "echo 'settings put global animator_duration_scale 0 2>/dev/null' >> /system/etc/install-recovery.sh; " +
                "echo 'settings put secure lockscreen.disabled 1 2>/dev/null' >> /system/etc/install-recovery.sh; " +
                "fi"
        )

        val success = rootExecutor(commands)
        if (!success) {
            Log.w(TAG, "Root execution for strip-down commands failed or was skipped")
        }
    }

    companion object {
        const val VERSION: Int = 7
        const val ID: String = "007_strip_system_bloat"
        private const val TAG = "Migration007"

        fun defaultRootExecutor(commands: List<String>): Boolean {
            return try {
                val process = Runtime.getRuntime().exec("su")
                val os = java.io.DataOutputStream(process.outputStream)
                for (cmd in commands) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes("exit\n")
                os.flush()
                os.close()
                process.waitFor() == 0
            } catch (e: Exception) {
                Log.w(TAG, "Root execution error: ${e.message}")
                false
            }
        }
    }
}
