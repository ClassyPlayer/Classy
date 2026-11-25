package com.themoon.y1.managers

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream

object AssetInstaller {

    fun installBundledLanguages(context: Context, prefs: SharedPreferences) {
        val lastInstalledVersion = prefs.getInt("last_lang_version", 0)
        // 1013: the Books strings; 1014: the Photos strings; 1015: Hold Back for Now Playing; 1016-1019: the WebDAV Sync strings
        // (1017-1019: more of them, for a player with a test build of the one before); 1020: Sort by Year;
        // 1021: No Duplicates in Playlists
        val currentPackVersion = 1021
        if (lastInstalledVersion >= currentPackVersion) return

        val langFolder = File(context.filesDir, "languages")
        if (!langFolder.exists()) langFolder.mkdirs()

        try {
            val assetFiles = context.assets.list("languages") ?: return
            for (filename in assetFiles) {
                if (!filename.endsWith(".json")) continue
                val outFile = File(langFolder, filename)
                context.assets.open("languages/$filename").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            prefs.edit().putInt("last_lang_version", currentPackVersion).apply()
        } catch (_: Exception) {}
    }
}
