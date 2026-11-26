package com.themoon.y1

import android.content.Context
import android.content.SharedPreferences

/**
 * The preference file of the app settings.
 */
object AppPrefs {
    const val NAME = "AppPrefs"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
