package com.themoon.y1.migrations

import android.content.SharedPreferences
import com.themoon.y1.managers.UsbConnection

/**
 * Migration 010: the toggle Ask on USB Connect ("usb_storage_prompt") is now the setting Computer Connection
 * ("usb_connection"): On → Ask, Off → Media Device. Removes the old key. (#49 uses 007.)
 */
class Migration010ComputerConnection : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Change Ask on USB Connect to Computer Connection"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        context.settingsPrefs?.let { move(it) }
    }

    companion object {
        const val VERSION: Int = 10
        const val ID: String = "010_computer_connection"

        /** The new value for the old toggle; null = no old value (the default applies). */
        fun newValue(old: Boolean?): Int? = old?.let { UsbConnection.fromOldToggle(it) }

        /** Writes the new value for the old key and removes the old key. A value that is already set stays. */
        fun move(prefs: SharedPreferences) {
            if (!prefs.contains(UsbConnection.OLD_PREF)) return
            val value = newValue(prefs.getBoolean(UsbConnection.OLD_PREF, true))
            val editor = prefs.edit().remove(UsbConnection.OLD_PREF)
            if (value != null && !prefs.contains(UsbConnection.PREF)) editor.putInt(UsbConnection.PREF, value)
            check(editor.commit()) { "Cannot write ${UsbConnection.PREF}" }
        }
    }
}
