package com.themoon.y1.photos

/**
 * The EXIF orientation of a photo (tag 0x0112, values 1 to 8): how to turn the stored pixels so that the photo shows
 * upright. No Android types: the decoder builds its Matrix from [rotation] and [mirror].
 */
object ExifOrientation {
    const val NORMAL = 1

    /**
     * Degrees to rotate clockwise after the mirror. 5 (transpose) = mirror, then 270; 7 (transverse) = mirror,
     * then 90.
     */
    fun rotation(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        6, 7 -> 90
        5, 8 -> 270
        else -> 0
    }

    /** True when the photo is mirrored left to right (before the rotation). */
    fun mirror(orientation: Int): Boolean = orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7

    /** True when width and height change places (a quarter turn). */
    fun swapsSides(orientation: Int): Boolean = rotation(orientation) % 180 != 0

    /** A value that is not 1 to 8 (missing tag, broken file) is upright. */
    fun normalize(orientation: Int): Int = if (orientation in 1..8) orientation else NORMAL
}
