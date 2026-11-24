package com.themoon.y1.migrations

/**
 * Migration 008: the main menu has a new row *Play On* (after *Wi-Fi*). A saved order (Settings → Menus → Main
 * Menu Items) puts an item that it does not have at the end: put Play On after Wi-Fi in it once.
 */
class Migration008PlayOnMenuItem : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Put Play On after Wi-Fi in the saved main menu order"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val prefs = context.settingsPrefs ?: return
        NewMenuItem.putAfter(prefs, ITEM, AFTER)
    }

    companion object {
        const val VERSION: Int = 8
        const val ID: String = "008_play_on_menu_item"
        const val ITEM = "btn_play_on"
        const val AFTER = "btn_wifi"
    }
}
