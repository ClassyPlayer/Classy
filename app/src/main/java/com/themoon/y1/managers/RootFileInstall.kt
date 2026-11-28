package com.themoon.y1.managers

/** Root shell lines that put a file into a system folder. */
internal object RootFileInstall {
    /**
     * Copies [source] (a file that root can read) to [target], mode 644, owner root: first to a staged file in the same
     * folder, then a rename, so [target] is never a half-written file.
     */
    fun lines(source: String, target: String): List<String> {
        val staged = "$target.classy_tmp"
        return listOf("cp $source $staged", "chmod 644 $staged", "chown root:root $staged", "mv $staged $target")
    }
}
