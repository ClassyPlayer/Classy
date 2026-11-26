package com.themoon.y1.managers

/**
 * Settings → Connectivity → USB Drive: what a USB cable plug to a computer does with the microSD card, as the iPod's
 * "Enable disk use" setting. With a cable in, the computer always sees the Y2 as a media device (MTP); only the card
 * can be lent as a USB drive. [ASK] shows the question (only with a card that can be lent), [ALWAYS] lends the card
 * at once, [NEVER] does nothing.
 */
object UsbDrive {
    /** AppPrefs key. */
    const val PREF = "usb_drive"
    const val ASK = 0
    const val ALWAYS = 1
    const val NEVER = 2

    /** The labels of the values (translation keys; "Never" alone is the text of "Last Synced: Never"). */
    val LABELS = arrayOf("Ask", "Always", "Never (USB Drive)")

    fun normalize(value: Int): Int = if (value in LABELS.indices) value else ASK

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

    /**
     * How a share ends: [EJECT] on the Y2 ("OK to disconnect."), [CABLE_OUT] before Eject ("Card not ejected."),
     * [CARD_REMOVED] while lent ("microSD card removed.").
     */
    enum class End { EJECT, CABLE_OUT, CARD_REMOVED }

    /** Music that played before the share plays again only after Eject. */
    fun resumesMusic(end: End): Boolean = end == End.EJECT

    /** The question says "Music will stop." when music from the card ([cardPath]) plays now: the share pauses it. */
    fun musicStops(playing: Boolean, playingPath: String?, cardPath: String): Boolean =
        playing && playingPath != null && (playingPath == cardPath || playingPath.startsWith("$cardPath/"))
}
