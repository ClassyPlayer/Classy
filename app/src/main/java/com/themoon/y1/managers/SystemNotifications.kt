package com.themoon.y1.managers

import android.content.Context
import android.util.Log

/**
 * Hides Android's notification icons and tickers (for example "USB debugging connected" or "Connected as USB storage")
 * while the app is in front. The activity is full screen, but on Android 4.4 a ticker still shows the status bar over it.
 *
 * It uses the hidden StatusBarManager.disable(). That needs android.permission.STATUS_BAR, which Android gives only to
 * a system app (the ROM installs the app in /system/app). Without the permission it does nothing.
 * The notifications stay in Android's list; only the status bar does not show them. Android removes the flags when the
 * process stops, so they cannot stay after a crash. [disable] must use one StatusBarManager: its token owns the flags.
 */
class SystemNotifications(private val disable: ((Int) -> Unit)?) {

    private var isAvailable = disable != null

    fun hide() = set(HIDE_FLAGS)

    fun show() = set(0)

    private fun set(flags: Int) {
        if (!isAvailable) return
        try {
            disable?.invoke(flags)
        } catch (t: Throwable) {
            // No STATUS_BAR permission (not a system app) or no such method: do not try again
            isAvailable = false
            Log.w(TAG, "status bar disable not available: ${t.cause ?: t}")
        }
    }

    companion object {
        private const val TAG = "SystemNotifications"

        // android.app.StatusBarManager (hidden API), the same values in Android 4.4
        const val DISABLE_EXPAND = 0x00010000
        const val DISABLE_NOTIFICATION_ICONS = 0x00020000
        const val DISABLE_NOTIFICATION_TICKER = 0x00080000

        /**
         * Only the notifications and the pull-down panel. Not HOME, BACK or RECENT, and not the sounds
         * (DISABLE_NOTIFICATION_ALERTS): the keys, the keyguard and the power menu work as before.
         */
        const val HIDE_FLAGS = DISABLE_EXPAND or DISABLE_NOTIFICATION_ICONS or DISABLE_NOTIFICATION_TICKER

        fun create(context: Context): SystemNotifications {
            val disable: ((Int) -> Unit)? = try {
                val manager = context.applicationContext.getSystemService("statusbar")
                val method = manager?.javaClass?.getMethod("disable", Int::class.javaPrimitiveType)
                if (manager != null && method != null) { flags -> method.invoke(manager, flags) } else null
            } catch (t: Throwable) {
                Log.w(TAG, "no status bar manager: $t")
                null
            }
            return SystemNotifications(disable)
        }
    }
}
