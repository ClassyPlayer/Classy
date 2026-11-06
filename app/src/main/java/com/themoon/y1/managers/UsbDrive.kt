package com.themoon.y1.managers

/**
 * Settings → Connectivity → USB Drive: what a USB cable plug to a computer does with the microSD card, as the iPod's
 * "Enable disk use" setting. With a cable in, the computer always sees the Y2 as a media device (MTP); only the card
 * can be lent as a USB drive. [ASK] shows the question (only with a card that can be lent), [ALWAYS] lends the card
 * at once, [NEVER] does nothing.
 */
object UsbDrive {
    /** AppPrefs key. Migration 010 moves the toggle Ask on USB Connect ([OLD_TOGGLE]) and [OLD_CHOICE] into it. */
    const val PREF = "usb_drive"
    const val OLD_TOGGLE = "usb_storage_prompt"
    /** The key of the first version of this setting (0 Ask, 1 Media Device, 2 Disk Mode), in test builds only. */
    const val OLD_CHOICE = "usb_connection"
    const val ASK = 0
    const val ALWAYS = 1
    const val NEVER = 2

    /** English labels of the values (the app translates them). */
    val LABELS = arrayOf("Ask", "Always", "Never")

    fun normalize(value: Int): Int = if (value in LABELS.indices) value else ASK

    /** The value for the old toggle Ask on USB Connect: On → [ASK], Off → [NEVER]. */
    fun fromOldToggle(ask: Boolean): Int = if (ask) ASK else NEVER

    /** The value for [OLD_CHOICE]: Ask → [ASK], Media Device → [NEVER], Disk Mode → [ALWAYS]; null for an unknown value. */
    fun fromOldChoice(value: Int): Int? = when (value) {
        0 -> ASK
        1 -> NEVER
        2 -> ALWAYS
        else -> null
    }

    enum class OnPlug { NOTHING, ASK, LEND }

    /**
     * What a cable plug does (once for each plug). [card] = a microSD card can be lent (null = not known).
     * Always without a card starts the share too: the share gives the notice "No microSD card.".
     */
    fun onPlug(value: Int, card: Boolean?): OnPlug = when (normalize(value)) {
        NEVER -> OnPlug.NOTHING
        ALWAYS -> OnPlug.LEND
        else -> if (card == false) OnPlug.NOTHING else OnPlug.ASK
    }
}
