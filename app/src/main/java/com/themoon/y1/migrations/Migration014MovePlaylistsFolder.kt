package com.themoon.y1.migrations

import android.util.Log
import com.themoon.y1.StoragePaths
import java.io.File

/**
 * Migration 014: playlists move from Y1_Playlists on internal storage to Playlists on the microSD card (a computer can
 * reach it as a USB drive), or to Playlists on internal storage without a card. A name that is already there gets a
 * number ("Rock 2.m3u8"); nothing is overwritten. A file that cannot move stays in Y1_Playlists.
 */
class Migration014MovePlaylistsFolder : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Move playlists from Y1_Playlists to Playlists on the microSD card (internal storage without a card)"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        val legacy = StoragePaths.getLegacyPlaylistsDir()
        if (!legacy.isDirectory) return
        val moved = move(legacy, StoragePaths.getPlaylistsDir())
        Log.i(TAG, "Moved $moved item(s) from ${legacy.absolutePath}")
    }

    companion object {
        const val VERSION: Int = 14
        const val ID: String = "014_move_playlists_folder"
        private const val TAG = "Migration014"

        /** Moves each file and folder of [legacy] into [target], then deletes [legacy] when it is empty. Returns the count moved. */
        fun move(legacy: File, target: File): Int {
            if (!legacy.isDirectory) return 0
            if (!target.exists()) target.mkdirs()
            var moved = 0
            for (item in legacy.listFiles() ?: emptyArray()) {
                val dest = freeName(target, item.name)
                if (moveOne(item, dest)) moved++
            }
            if (legacy.listFiles()?.isEmpty() == true) legacy.delete()
            return moved
        }

        /** [name] in [dir], or "Name 2.ext", "Name 3.ext", ... when that name is taken. */
        fun freeName(dir: File, name: String): File {
            val first = File(dir, name)
            if (!first.exists()) return first
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 2
            while (true) {
                val candidate = File(dir, "$base $n$ext")
                if (!candidate.exists()) return candidate
                n++
            }
        }

        private fun moveOne(src: File, dest: File): Boolean {
            if (src.renameTo(dest)) return true
            // Another volume (internal storage to the card): copy, then delete the original
            return try {
                if (src.isDirectory) {
                    src.copyRecursively(dest, overwrite = false)
                    src.deleteRecursively()
                } else {
                    src.copyTo(dest, overwrite = false)
                    src.delete()
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not move ${src.absolutePath} to ${dest.absolutePath}: ${e.message}")
                if (dest.exists() && src.exists()) dest.deleteRecursively()
                false
            }
        }
    }
}
