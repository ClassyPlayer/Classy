package com.themoon.y1.controllers.playlists

/** The rules for the name of a playlist (Rename Playlist). No Android code, so unit tests can check it. */
object PlaylistNames {

    /** The file of the On-The-Go playlist, in [com.themoon.y1.StoragePaths.getPlaylistsDir]. */
    const val ON_THE_GO_FILE = "On-The-Go.m3u8"

    /** The name of the On-The-Go playlist: another playlist cannot have it. */
    const val ON_THE_GO = "On-The-Go"

    /** The characters that FAT and exFAT (the microSD card) do not permit in a file name. */
    const val NOT_PERMITTED = "/\\:*?\"<>|"

    /** The result of [newFileName]: the new file name, or why the name cannot be used (English text for localize). */
    sealed interface Result {
        data class Ok(val fileName: String) : Result
        data class Refused(val message: String) : Result
    }

    /**
     * Why [name] (already trimmed) cannot be the name of a playlist file (English text for localize), or null when it
     * can: an empty name, a name that starts with "." (a hidden file: the playlist list does not show it), or a
     * character that FAT and exFAT do not permit ([NOT_PERMITTED] and the control characters).
     */
    fun problem(name: String): String? = when {
        name.isEmpty() -> "Please enter a name."
        name.startsWith(".") -> "A playlist name cannot start with a dot."
        name.any { it in NOT_PERMITTED || it < ' ' || it == '\u007F' } -> "A name cannot have these characters: / \\ : * ? \" < > |"
        else -> null
    }

    /**
     * The new file name of the playlist file [oldFileName] for the name [typed] (trimmed); the extension of the old
     * file stays. [otherNames]: the names (without the extension) of the other playlists in the same folder. FAT does
     * not see the case of a name, so a name that differs from one of them only in case is refused, as is [ON_THE_GO].
     */
    fun newFileName(typed: String, oldFileName: String, otherNames: Collection<String>): Result {
        val name = typed.trim()
        problem(name)?.let { return Result.Refused(it) }
        if (name.equals(ON_THE_GO, ignoreCase = true) || otherNames.any { it.equals(name, ignoreCase = true) }) {
            return Result.Refused("A playlist with this name already exists.")
        }
        val dot = oldFileName.lastIndexOf('.')
        return Result.Ok(name + if (dot > 0) oldFileName.substring(dot) else "")
    }
}
