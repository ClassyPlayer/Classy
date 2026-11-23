package com.themoon.y1

import com.themoon.y1.io.SafeFiles
import java.io.File
import java.io.IOException

/**
 * The top level of the web file browser: one folder for each storage. A web path starts with the folder of its
 * storage, for example "Internal Storage/Music/song.mp3" or "SD Card/Music/song.mp3". A path that starts with a
 * different name is on internal storage, so that the paths from before the storage folders still work
 * ("Music/song.mp3"). No path can go out of its storage (see [SafeFiles.resolve]).
 */
class WebRoots(private val internal: File, private val sdCard: () -> File?) {

    /** The storage folders of the top level: internal storage, then the microSD card when it is in the player. */
    fun names(): List<String> = if (sdCard() != null) listOf(INTERNAL, SD_CARD) else listOf(INTERNAL)

    /**
     * The file of the web path [path] (no "/" at the start). A storage folder gives the root of its storage.
     * Throws for the top level (""), for [SD_CARD] when there is no card, and for a path out of its storage.
     */
    @Throws(IOException::class)
    fun resolve(path: String): File {
        val (root, rest) = split(path)
        return SafeFiles.resolve(root, rest)
    }

    /** The root of the storage of [path] (see [resolve]). */
    @Throws(IOException::class)
    fun rootOf(path: String): File = split(path).first

    @Throws(IOException::class)
    private fun split(path: String): Pair<File, String> {
        if (path.isEmpty()) throw IOException("The top level is not a folder")
        val first = path.substringBefore('/')
        val rest = if (path.contains('/')) path.substringAfter('/') else ""
        return when (first) {
            INTERNAL -> internal to rest
            SD_CARD -> (sdCard() ?: throw IOException("No microSD card")) to rest
            else -> internal to path
        }
    }

    companion object {
        const val INTERNAL = "Internal Storage"
        const val SD_CARD = "SD Card"

        /** The storages of the player. The card is read again at each call, so that it can go in or out while the server runs. */
        fun ofDevice(): WebRoots = WebRoots(StoragePaths.getPrimaryRoot()) { StoragePaths.getSecondaryRoot() }
    }
}
