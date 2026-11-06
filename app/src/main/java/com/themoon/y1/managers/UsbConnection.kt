package com.themoon.y1.managers

/**
 * Settings → Connectivity → Computer Connection: what a USB cable plug to a computer does, as the iPod's
 * "Enable disk use" setting. [ASK] shows the question (only with a microSD card that can be shared),
 * [MEDIA_DEVICE] does nothing (the computer sees the storage over MTP), [DISK_MODE] shares the card at once.
 */
object UsbConnection {
    /** AppPrefs key. It replaces the toggle Ask on USB Connect ([OLD_PREF]; migration 010). */
    const val PREF = "usb_connection"
    const val OLD_PREF = "usb_storage_prompt"
    const val ASK = 0
    const val MEDIA_DEVICE = 1
    const val DISK_MODE = 2

    /** English labels of the values (the app translates them). */
    val LABELS = arrayOf("Ask", "Media Device", "Disk Mode")

    fun normalize(value: Int): Int = if (value in LABELS.indices) value else ASK

    /** The value for the old toggle Ask on USB Connect: On → [ASK], Off → [MEDIA_DEVICE]. */
    fun fromOldToggle(ask: Boolean): Int = if (ask) ASK else MEDIA_DEVICE

    enum class OnPlug { NOTHING, ASK, DISK_MODE }

    /**
     * What a cable plug does (once for each plug). [card] = a microSD card can be shared (null = not known).
     * Disk Mode without a card starts the share too: the share gives the notice to insert a card.
     */
    fun onPlug(value: Int, card: Boolean?): OnPlug = when (normalize(value)) {
        MEDIA_DEVICE -> OnPlug.NOTHING
        DISK_MODE -> OnPlug.DISK_MODE
        else -> if (card == false) OnPlug.NOTHING else OnPlug.ASK
    }
}
