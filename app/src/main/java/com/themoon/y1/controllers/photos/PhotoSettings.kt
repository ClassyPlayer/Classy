package com.themoon.y1.controllers.photos

import android.content.SharedPreferences
import com.themoon.y1.Prefs
import com.themoon.y1.controllers.settings.SettingRow
import com.themoon.y1.controllers.settings.SettingRows

/**
 * The slideshow settings of Settings → Photos ([rows]). No music setting: Play/Pause keeps the music on all Photos
 * screens.
 */
object PhotoSettings {

    /** Time Per Slide in seconds; 0 = Manual (the wheel or Next moves on). */
    val SLIDE_SECONDS = intArrayOf(2, 3, 5, 10, 20, 0)
    private val SLIDE_LABELS = arrayOf("2 Seconds", "3 Seconds", "5 Seconds", "10 Seconds", "20 Seconds", "Manual")

    const val TRANSITION_OFF = 0
    const val TRANSITION_DISSOLVE = 1
    const val TRANSITION_PUSH = 2
    const val TRANSITION_RANDOM = 3
    private val TRANSITION_LABELS = arrayOf("Off", "Dissolve", "Push", "Random")

    fun rows(r: SettingRows): List<SettingRow> = listOf(
        r.cycle("Time Per Slide", Prefs.PHOTOS_SLIDE_TIME, SLIDE_LABELS, { if (it in SLIDE_SECONDS.indices) it else 1 }),
        r.toggle("Repeat Slideshow", Prefs.PHOTOS_REPEAT),
        r.toggle("Shuffle Photos", Prefs.PHOTOS_SHUFFLE),
        r.cycle("Transitions", Prefs.PHOTOS_TRANSITION, TRANSITION_LABELS),
    )

    /** The time of one slide in ms, or 0 for Manual. */
    fun slideMs(prefs: SharedPreferences): Long {
        val i = Prefs.PHOTOS_SLIDE_TIME.get(prefs)
        return SLIDE_SECONDS[if (i in SLIDE_SECONDS.indices) i else 1] * 1000L
    }

    fun transition(prefs: SharedPreferences): Int = Prefs.PHOTOS_TRANSITION.get(prefs).let { if (it in 0..3) it else TRANSITION_DISSOLVE }
}
