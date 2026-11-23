package com.themoon.y1.migrations

import android.util.Log
import com.themoon.y1.StoragePaths
import java.io.File

/**
 * Migration 017: the two themes are in the code now (ThemeManager), so the theme folders go once: the unpacked copies of
 * the old theme zips in the app folder (files/themes) and Y1_Themes on the storage. A missing folder is no problem; a folder
 * that cannot be deleted makes the migration fail, so it runs again at the next start.
 */
class Migration017RemoveThemeFolders : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Delete the old theme folders (files/themes and Y1_Themes)"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val folders = ArrayList<File>()
        context.context?.let { folders.add(File(it.filesDir, "themes")) }
        folders.add(File(StoragePaths.getPrimaryRoot(), STORAGE_FOLDER))
        for (folder in folders) {
            if (!folder.exists()) continue
            // A folder that stays: the migration fails, and the manager runs it again at the next start
            if (!delete(folder)) throw java.io.IOException("Could not delete ${folder.absolutePath}")
            Log.i(TAG, "Deleted ${folder.absolutePath}")
        }
    }

    companion object {
        const val VERSION: Int = 17
        const val ID: String = "017_remove_theme_folders"
        const val STORAGE_FOLDER = "Y1_Themes"
        private const val TAG = "Migration017"

        /** Deletes [folder] and all in it. False when it was not there or could not be deleted. */
        fun delete(folder: File): Boolean {
            if (!folder.exists()) return false
            return folder.deleteRecursively()
        }
    }
}
