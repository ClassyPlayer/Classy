package com.themoon.y1.core

import android.os.Build
import android.os.StatFs
import android.view.View
import java.util.zip.ZipFile

/**
 * Fallbacks for Android versions before 4.4 (API 19), for the Y1 (Android 4.2, API 17).
 * On API 19 and later, each call does the same as the plain API call.
 */
object ApiCompat {
    /**
     * [View.isInLayout] (API 18). Before API 18, Android cannot tell if the view tree is in a layout pass:
     * then the value is [selfInLayout], which the caller sets while its own onLayout runs.
     */
    fun isInLayout(view: View, selfInLayout: Boolean): Boolean =
        if (Build.VERSION.SDK_INT >= 18) view.isInLayout else selfInLayout

    /** [View.isAttachedToWindow] (API 19). Before API 19: the view has a window token. */
    fun isAttachedToWindow(view: View): Boolean =
        if (Build.VERSION.SDK_INT >= 19) view.isAttachedToWindow else view.windowToken != null

    /** The free bytes for apps. Before API 18, StatFs has only the Int forms of the block counts. */
    @Suppress("DEPRECATION")
    fun availableBytes(stat: StatFs): Long =
        if (Build.VERSION.SDK_INT >= 18) stat.availableBlocksLong * stat.blockSizeLong
        else stat.availableBlocks.toLong() * stat.blockSize.toLong()
}

/**
 * [use] for a [ZipFile]: ZipFile is a Closeable only from API 19 on Android, so `ZipFile(f).use {}` fails before it.
 * Closes the file at the end; when [block] fails, a close failure does not hide the first exception.
 */
inline fun <R> ZipFile.useZip(block: (ZipFile) -> R): R {
    val result = try {
        block(this)
    } catch (e: Throwable) {
        try { close() } catch (_: Throwable) {}
        throw e
    }
    close()
    return result
}
