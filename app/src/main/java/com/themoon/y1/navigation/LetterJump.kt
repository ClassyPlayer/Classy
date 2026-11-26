package com.themoon.y1.navigation

import com.themoon.y1.models.SortName
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

/**
 * iPod speed scrolling: a fast wheel turn in an alphabetical list starts letter mode ([Mode]). In letter mode
 * each wheel step moves the selection to the first row of the next (or previous) letter, and a letter box
 * shows the letter.
 */
object LetterJump {

    /**
     * Settings → Controls → Letter Jump Sensitivity: how fast a wheel turn must be to start letter mode. A step is
     * fast when it and the [steps] - 1 steps before it turn the same way, each at most [gapMs] apart. With the Y2 pad
     * wheel ([PadWheel]), a step is fast when the wheel turns at [padTurnsPerSecond] or more.
     */
    enum class Sensitivity(val steps: Int, val gapMs: Long, val padTurnsPerSecond: Double) {
        LOW(6, 80L, 2.2),
        NORMAL(4, 100L, 1.7),
        HIGH(3, 120L, 1.3);

        companion object {
            /** The labels of the Settings row (translation keys), in the order of the pref values (the [ordinal]). */
            val LABELS = arrayOf("Low (Letter Jump)", "Normal (Letter Jump)", "High (Letter Jump)")
            val DEFAULT = NORMAL

            /** The sensitivity of pref value [level]; [DEFAULT] for a value that is not known. */
            fun of(level: Int): Sensitivity = values().getOrElse(level) { DEFAULT }
        }
    }

    /** The sensitivity for the Letter Jump settings, or null when Letter Jump is Off (the wheel always steps row by row). */
    fun sensitivity(on: Boolean, level: Int): Sensitivity? = if (on) Sensitivity.of(level) else null

    /**
     * Letter mode ends this long after the last wheel step. The wheel driver sends no event when the thumb
     * leaves the wheel (each step is a key press and release at the same time), so a stop is the only sign.
     */
    const val IDLE_END_MS = 800L

    /**
     * The pad wheel ([PadWheel]) tells the lift of the thumb: then letter mode ends this long after the lift (a new
     * touch in this time goes on in letter mode).
     */
    const val LIFT_END_MS = 250L

    /** With the pad wheel, letter mode also ends after this time with no step while the thumb stays: then rows again. */
    const val TOUCH_IDLE_END_MS = 600L

    private val HANGUL_INITIALS = "ㄱㄱㄴㄷㄷㄹㅁㅂㅂㅅㅅㅇㅈㅈㅊㅋㅌㅍㅎ"

    /**
     * The index letter of a row: the first letter in upper case without accents ("Émile" → "E"),
     * "#" for a digit or a symbol, the initial consonant for Hangul. With [skipThe], the letter of the
     * [SortName] ("The Who" → "W"). Null for a blank row.
     */
    fun letterOf(text: String?, skipThe: Boolean = true): String? {
        var s = text?.trim() ?: return null
        if (s.isEmpty()) return null
        if (skipThe) s = SortName.of(s)
        val cp = s.codePointAt(0)
        if (cp in 0xAC00..0xD7A3) return HANGUL_INITIALS[(cp - 0xAC00) / (21 * 28)].toString()
        if (!Character.isLetter(cp)) return "#"
        val base = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
        val first = base.codePointAt(0)
        val upper = String(Character.toChars(if (Character.isLetter(first)) first else cp)).uppercase(Locale.ROOT)
        return String(Character.toChars(upper.codePointAt(0)))
    }

    /**
     * The letter of each row, or null when the rows are not in alphabetical order (then the wheel steps
     * row by row). The letters ignore a leading "The " only when that matches the order of the rows.
     */
    fun lettersFor(texts: List<String?>): List<String?>? {
        val skipThe = texts.map { letterOf(it, skipThe = true) }
        val plain = texts.map { letterOf(it, skipThe = false) }
        val letters = if (descents(plain) < descents(skipThe)) plain else skipThe
        val present = letters.filterNotNull()
        // A few rows out of order (for example accented titles sorted after Z) are allowed.
        val alphabetical = present.toSet().size >= 2 && descents(letters) * 10 <= present.size
        return if (alphabetical) letters else null
    }

    /** "#" sorts first, then letters in code point order. */
    private fun rank(letter: String): Int = if (letter == "#") 0 else letter.codePointAt(0)

    private fun descents(letters: List<String?>): Int {
        var count = 0
        var prev: String? = null
        for (l in letters) {
            if (l == null) continue
            if (prev != null && rank(l) < rank(prev)) count++
            prev = l
        }
        return count
    }

    /**
     * The row to jump to from row [from]: the first row of the next letter ([direction] > 0) or of the
     * previous letter ([direction] < 0). Rows without a letter (for example "All Songs") are skipped.
     * -1 when there is no such letter.
     */
    fun target(letters: List<String?>, from: Int, direction: Int): Int {
        if (from !in letters.indices) return -1
        val current = letters[from]
        if (direction > 0) {
            var i = from + 1
            while (i < letters.size && (letters[i] == null || letters[i] == current)) i++
            return if (i < letters.size) i else -1
        }
        var start = from
        if (current != null) while (start > 0 && letters[start - 1] == current) start--
        var j = start - 1
        while (j >= 0 && letters[j] == null) j--
        if (j < 0) return -1
        val previous = letters[j]
        while (j > 0 && letters[j - 1] == previous) j--
        return j
    }

    /** Tells from the times of the wheel steps if the wheel turns fast. */
    class WheelSpeed {
        private val times = ArrayDeque<Long>()
        private var lastDirection = 0

        /**
         * Adds one step ([direction] -1 or 1, at [timeMs]) and returns true if the wheel now turns fast for
         * [sensitivity]. Null (Letter Jump Off): never fast.
         */
        fun onStep(direction: Int, timeMs: Long, sensitivity: Sensitivity? = Sensitivity.DEFAULT): Boolean {
            if (sensitivity == null) {
                reset()
                return false
            }
            val dir = Integer.signum(direction)
            val last = times.peekLast()
            if (dir != lastDirection || (last != null && timeMs - last > sensitivity.gapMs)) times.clear()
            lastDirection = dir
            times.addLast(timeMs)
            while (times.size > sensitivity.steps) times.removeFirst()
            return times.size == sensitivity.steps
        }

        fun reset() {
            times.clear()
            lastDirection = 0
        }
    }

    /** What a wheel step does. */
    enum class Step {
        /** Not in letter mode: the wheel steps one row. */
        ROW,
        /** Letter mode: the selection goes to [Mode.row], the first row of the next or previous letter. */
        LETTER,
        /** Letter mode, but there is no letter that way: the selection stays. */
        STAY
    }

    /** What a key press does. */
    enum class Press {
        /** Not for letter mode: the key works as usual. */
        PASS,
        /** The rest of a centre press that ended letter mode: ignore it. */
        DROP,
        /** End letter mode, and ignore the key (centre). */
        END,
        /** End letter mode, then the key works as usual (Back, Play/Pause). */
        END_AND_PASS
    }

    /**
     * Letter mode, as on the iPod. Fast steps start it ([WheelSpeed], with the [sensitivity] at each step: a
     * change applies at once; null = Letter Jump Off). Then each wheel step, slow or fast, moves one letter, and
     * the letter box stays. It ends [IDLE_END_MS] after the last step, or at a centre, Back or Play/Pause press.
     * With the pad wheel (a step with a pad speed), the speed starts it, and it ends [LIFT_END_MS] after the thumb
     * lifts ([onTouch]) or [TOUCH_IDLE_END_MS] after the last step. At the end, the screen goes to [row] (the end
     * value of [end]).
     * Pure state: the screen gives it the wheel steps, the touches and the keys, and runs the timer to [endMs].
     */
    class Mode(private val sensitivity: () -> Sensitivity? = { Sensitivity.DEFAULT }) {
        private val speed = WheelSpeed()
        private var lastStepMs = 0L
        private var dropCentre = false
        /** The last step came from the pad wheel: it ends from the touch, not from [IDLE_END_MS]. */
        private var byPad = false
        private var touching = false
        private var liftMs = 0L

        var isActive = false
            private set

        /** The row of the letter in the box: the first row of that letter, or the start row; -1 when not active. */
        var row = -1
            private set

        /**
         * One wheel step ([direction] -1 or 1, at [timeMs]) with the selection at row [current]. [padSpeed]: the
         * wheel speed (turns a second, clockwise positive) of a pad-wheel step, null for a step key of the wheel.
         * [letters] gives the letter of each row ([lettersFor]), null when the rows are not alphabetical.
         */
        fun onStep(direction: Int, timeMs: Long, current: Int, padSpeed: Double? = null, letters: () -> List<String?>?): Step {
            if (!isActive && !isFast(direction, timeMs, padSpeed)) return Step.ROW
            val list = letters()
            val from = if (isActive) row else current
            if (list == null || from !in list.indices) {
                end()
                return Step.ROW
            }
            isActive = true
            lastStepMs = timeMs
            byPad = padSpeed != null
            if (byPad) touching = true
            val next = LetterJump.target(list, from, direction)
            if (next < 0) {
                row = from
                return Step.STAY
            }
            row = next
            return Step.LETTER
        }

        fun onStep(direction: Int, timeMs: Long, current: Int, letters: () -> List<String?>?): Step =
            onStep(direction, timeMs, current, null, letters)

        private fun isFast(direction: Int, timeMs: Long, padSpeed: Double?): Boolean {
            val s = sensitivity()
            if (padSpeed == null) return speed.onStep(direction, timeMs, s)
            return s != null && padSpeed * Integer.signum(direction) >= s.padTurnsPerSecond
        }

        /** The pad wheel: the thumb touched ([isTouching]) or lifted at [timeMs]. */
        fun onTouch(isTouching: Boolean, timeMs: Long) {
            touching = isTouching
            if (!isTouching) liftMs = timeMs
        }

        /** The time when letter mode ends if no step or touch comes before it; [Long.MAX_VALUE] when not active. */
        fun endMs(): Long = when {
            !isActive -> Long.MAX_VALUE
            !byPad -> lastStepMs + IDLE_END_MS
            touching -> lastStepMs + TOUCH_IDLE_END_MS
            else -> minOf(lastStepMs + TOUCH_IDLE_END_MS, maxOf(liftMs, lastStepMs) + LIFT_END_MS)
        }

        /** True when letter mode must end at [nowMs] ([endMs]). */
        fun isIdle(nowMs: Long): Boolean = nowMs >= endMs()

        /** A key event ([keyCode], down or up, [repeatCount]). The screen calls [end] for [Press.END] and [Press.END_AND_PASS]. */
        fun onKey(keyCode: Int, isDown: Boolean, repeatCount: Int): Press {
            val isCentre = keyCode == KEY_CENTRE || keyCode == KEY_ENTER
            if (isCentre && dropCentre) {
                if (!isDown || repeatCount > 0) {
                    if (!isDown) dropCentre = false
                    return Press.DROP
                }
                dropCentre = false // a new press: the release of the last one did not come
            }
            if (!isActive || !isDown || repeatCount > 0) return Press.PASS
            return when (keyCode) {
                KEY_CENTRE, KEY_ENTER -> {
                    dropCentre = true // its release must not click the row or the album
                    Press.END
                }
                KEY_BACK, KEY_BACK_UP, KEY_PLAY_PAUSE, KEY_PLAY, KEY_PAUSE -> Press.END_AND_PASS
                else -> Press.PASS
            }
        }

        /** Ends letter mode. Returns the row to go to ([row]), or -1 when letter mode was not active. */
        fun end(): Int {
            val result = if (isActive) row else -1
            isActive = false
            row = -1
            byPad = false
            speed.reset()
            return result
        }

        private companion object {
            // android.view.KeyEvent codes (the values, so the class has no Android dependencies).
            const val KEY_BACK = 4
            const val KEY_BACK_UP = 19 // InputRouter also takes DPAD_UP as Back
            const val KEY_CENTRE = 23
            const val KEY_ENTER = 66
            const val KEY_PLAY_PAUSE = 85
            const val KEY_PLAY = 126
            const val KEY_PAUSE = 127
        }
    }
}
