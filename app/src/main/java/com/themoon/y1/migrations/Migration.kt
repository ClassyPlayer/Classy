package com.themoon.y1.migrations

/**
 * One versioned migration step.
 *
 * - A migration is for one of two changes. (1) A change outside the APK that an app update cannot deliver: System
 *   Update (OTA) installs only the APK. Examples: files in /system, the boot screen, framework resources. (2) A move of
 *   app or user files, when without it users cannot get to their data (for example playlists in an old folder).
 * - Never write a migration for a default of a setting, the order or visibility of menu items, or a library rescan.
 *   Change the default in the code. Users change settings and menus in Settings. The code discards an old library
 *   cache and scans again.
 * - A migration must be idempotent, and safe on a player with a freshly flashed ROM (it can have the change already,
 *   and it has no old folders).
 * - Do not use the version or the id of a removed migration again ([MigrationManager] lists them).
 */
interface Migration {
    /**
     * Monotonically increasing version number (e.g. 1, 2, 3...).
     * Migrations are executed in strictly ascending order of version.
     */
    val version: Int

    /**
     * Unique string identifier for logging, tracking, and diagnostics.
     */
    val id: String

    /**
     * Brief human-readable description of what this migration performs.
     */
    val description: String

    /**
     * Executes the migration logic.
     * Must be idempotent where feasible.
     * If an exception is thrown, the migration is treated as failed
     * and will NOT be marked as completed.
     *
     * @param context Migration context providing storage and preferences access.
     * @throws Exception If migration fails.
     */
    @Throws(Exception::class)
    fun migrate(context: MigrationContext)
}
