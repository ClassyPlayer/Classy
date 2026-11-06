package com.themoon.y1.migrations

import android.content.SharedPreferences
import com.themoon.y1.managers.UsbDrive

/**
 * Migration 010: the toggle Ask on USB Connect ("usb_storage_prompt") is now the setting USB Drive ("usb_drive"):
 * On → Ask, Off → Never. The key of the first version of the setting ("usb_connection", test builds) moves too:
 * Ask → Ask, Media Device → Never, Disk Mode → Always. Removes the old keys. (#49 uses 007.)
 */
class Migration010UsbDrive : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Change Ask on USB Connect to USB Drive"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        context.settingsPrefs?.let { move(it) }
    }

    companion object {
        const val VERSION: Int = 10
        const val ID: String = "010_usb_drive"

        /** The new value for the old keys (the newer [oldChoice] first); null = no old value (the default applies). */
        fun newValue(oldToggle: Boolean?, oldChoice: Int?): Int? =
            oldChoice?.let { UsbDrive.fromOldChoice(it) } ?: oldToggle?.let { UsbDrive.fromOldToggle(it) }

        /** Writes the new value for the old keys and removes the old keys. A value that is already set stays. */
        fun move(prefs: SharedPreferences) {
            val all = prefs.all
            if (UsbDrive.OLD_TOGGLE !in all && UsbDrive.OLD_CHOICE !in all) return
            val value = newValue(all[UsbDrive.OLD_TOGGLE] as? Boolean, all[UsbDrive.OLD_CHOICE] as? Int)
            val editor = prefs.edit().remove(UsbDrive.OLD_TOGGLE).remove(UsbDrive.OLD_CHOICE)
            if (value != null && !prefs.contains(UsbDrive.PREF)) editor.putInt(UsbDrive.PREF, value)
            check(editor.commit()) { "Cannot write ${UsbDrive.PREF}" }
        }
    }
}
