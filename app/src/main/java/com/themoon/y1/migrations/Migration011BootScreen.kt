package com.themoon.y1.migrations

import android.content.Context
import android.util.Log

/**
 * Migration 011: Installs the Classy boot screen (android.policy.jar) and patches
 * the boot-progress strings in framework-res.apk for OTA-updated devices.
 */
class Migration011BootScreen : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Install Classy boot screen and update boot strings"

    override fun migrate(context: MigrationContext) {
        val appCtx = context.context ?: return
        try {
            BootScreenMigrator.applyMigration(appCtx)
        } catch (e: Throwable) {
            Log.e("Classy", "Migration011BootScreen skipped or failed", e)
        }
    }

    companion object {
        const val VERSION: Int = 11
        const val ID: String = "011_boot_screen"
    }
}
