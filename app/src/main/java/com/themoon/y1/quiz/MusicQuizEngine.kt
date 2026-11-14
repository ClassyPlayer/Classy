package com.themoon.y1.quiz

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AudioSources
import com.themoon.y1.managers.VirtualVolumeManager
import com.themoon.y1.models.SongItem
import java.io.File
import java.util.Collections
import java.util.Random

/**
 * Headless state and game loop engine for iPod Music Quiz.
 * Manages question pool building, 5 question types (Title, Artist, Album, Year, Album Cover),
 * endless progressive rounds (6 questions per round), non-linear speed scaling,
 * time-decay scoring, life recovery, and audio sample preview player.
 */
class MusicQuizEngine(private val context: Context? = null, private val random: Random = Random()) {

    companion object {
        const val QUESTIONS_PER_ROUND = 6
        const val INITIAL_LIVES = 3
        const val MAX_LIVES = 5
        const val HEART_PRICE = 1500

        const val TYPE_TITLE = 0
        const val TYPE_ARTIST = 1
        const val TYPE_ALBUM = 2
        const val TYPE_YEAR = 3
        const val TYPE_ALBUM_COVER = 4

        /** The time in which a clip must start: the player is ready and plays. */
        const val CLIP_START_CHECK_MS = 5000L

        /** A smaller step of the clip position in [CLIP_START_CHECK_MS] is no progress (the clip hangs). */
        const val CLIP_MIN_PROGRESS_MS = 500L
    }

    data class CoverOption(
        val file: File,
        val album: String
    )

    data class QuizQuestion(
        val type: Int,
        val promptText: String,
        val song: SongItem,
        val correctAnswer: String,
        val textOptions: List<String> = emptyList(),
        val coverOptions: List<CoverOption> = emptyList()
    )

    var score: Int = 0
        private set
    var roundNumber: Int = 1
        private set
    var questionInRound: Int = 0
        private set
    var lives: Int = INITIAL_LIVES
        private set
    var isGameOver: Boolean = false
        private set
    var mistakesInRound: Int = 0
        private set
    var heartBoughtThisRound: Boolean = false
        private set

    /**
     * Optional external predicate to verify whether a SongItem has actual album cover art.
     * Prevents fallback placeholders from appearing in TYPE_ALBUM_COVER questions.
     */
    var coverArtPredicate: ((SongItem) -> Boolean)? = null

    private val quizPool = ArrayList<SongItem>()
    /**
     * The clip player: one ExoPlayer for the whole quiz, so that its buffer (one block of 4 MB, see
     * [AudioPlayerManager.newAudioPlayer]) is made only once. [releasePreview] releases it.
     */
    private var previewExoPlayer: SimpleExoPlayer? = null
    // The song of the clip that plays (null: no clip), and the start of the clip (-1 until the player is ready)
    private var previewFile: File? = null
    private var clipStart = -1L
    private var previewCheck: Runnable? = null
    private val previewHandler by lazy { Handler(Looper.getMainLooper()) }
    // A volume change during a clip
    private val previewGain: (Float) -> Unit = { gain -> previewExoPlayer?.volume = gain }
    private var lastTargetSongFile: File? = null

    /** Songs whose clip did not play. They are not asked again. */
    private val unplayableFiles = HashSet<File>()

    fun resetSession(library: List<SongItem>): Boolean {
        score = 0
        roundNumber = 1
        questionInRound = 0
        lives = INITIAL_LIVES
        isGameOver = false
        mistakesInRound = 0
        heartBoughtThisRound = false
        lastTargetSongFile = null

        stopPreview()

        quizPool.clear()
        for (song in library) {
            val path = song.file.absolutePath
            if (path.contains("/Audiobooks") || path.contains("/Podcasts") || path.startsWith("/PODCAST_STREAM")) {
                continue
            }
            if (song.title.isNullOrBlank()) {
                continue
            }
            quizPool.add(song)
        }

        return quizPool.size >= 4
    }

    fun isRoundComplete(): Boolean = questionInRound >= QUESTIONS_PER_ROUND

    /**
     * Non-linear question time scaling:
     * Reaches the 5.0s floor at round 8+.
     */
    fun getQuestionTimeMs(): Long {
        return when (roundNumber) {
            1 -> 15000L
            2 -> 13500L
            3 -> 11500L
            4 -> 9500L
            5 -> 8000L
            6 -> 6800L
            7 -> 5800L
            else -> 5000L
        }
    }

    /**
     * Advances to the next round.
     * If the player made 0 mistakes in the completed round and has fewer than MAX_LIVES,
     * awards +1 Life and returns true.
     */
    fun advanceRound(): Boolean {
        roundNumber++
        questionInRound = 0
        heartBoughtThisRound = false
        var bonusLifeAwarded = false
        if (mistakesInRound == 0 && lives < MAX_LIVES) {
            lives++
            bonusLifeAwarded = true
        }
        mistakesInRound = 0
        return bonusLifeAwarded
    }

    fun canBuyHeart(): Boolean = !heartBoughtThisRound && score >= HEART_PRICE && lives < MAX_LIVES

    fun buyHeart(): Boolean {
        if (canBuyHeart()) {
            score -= HEART_PRICE
            lives++
            heartBoughtThisRound = true
            return true
        }
        return false
    }

    fun nextQuestion(promptResolver: (Int, SongItem) -> String): QuizQuestion? {
        if (quizPool.size < 4 || isGameOver) return null
        questionInRound++

        val availableTypes = ArrayList<Int>()
        availableTypes.add(TYPE_TITLE)
        if (countDistinctField(TYPE_ARTIST) >= 4) availableTypes.add(TYPE_ARTIST)
        if (countDistinctField(TYPE_ALBUM) >= 4) availableTypes.add(TYPE_ALBUM)
        if (countDistinctField(TYPE_YEAR) >= 4) availableTypes.add(TYPE_YEAR)
        if (countDistinctField(TYPE_ALBUM_COVER) >= 3) availableTypes.add(TYPE_ALBUM_COVER)

        // Ask about a song whose clip plays; all songs stay available as wrong answers
        val targets = quizPool.filter { it.file !in unplayableFiles }.ifEmpty { quizPool }
        availableTypes.retainAll { type -> targets.any { isValidField(it, type) } }
        if (availableTypes.isEmpty()) availableTypes.add(TYPE_TITLE)

        val selectedType = availableTypes[random.nextInt(availableTypes.size)]

        val typePool = ArrayList<SongItem>()
        for (s in quizPool) {
            if (isValidField(s, selectedType)) typePool.add(s)
        }
        val pool = if (typePool.isNotEmpty()) typePool else quizPool

        val targetPool = targets.filter { isValidField(it, selectedType) }.ifEmpty { pool }

        // Anti-repeat protection: try not to pick the exact same song consecutively
        var candidateSongs = targetPool.filter { it.file != lastTargetSongFile }
        if (candidateSongs.isEmpty()) candidateSongs = targetPool

        val targetSong = candidateSongs[random.nextInt(candidateSongs.size)]
        lastTargetSongFile = targetSong.file

        val correctText = getFieldValue(targetSong, selectedType) ?: targetSong.title ?: ""
        val prompt = promptResolver(selectedType, targetSong)

        startPreview(targetSong.file)

        if (selectedType == TYPE_ALBUM_COVER) {
            val coveredPool = quizPool.filter { isValidField(it, TYPE_ALBUM_COVER) }
            val albumReps = LinkedHashMap<String, SongItem>()
            for (s in coveredPool) {
                val key = s.album?.trim()?.lowercase() ?: ""
                if (!albumReps.containsKey(key)) albumReps[key] = s
            }
            albumReps[targetSong.album?.trim()?.lowercase() ?: ""] = targetSong

            val repList = ArrayList(albumReps.values)
            Collections.shuffle(repList, random)

            val coverOptions = ArrayList<SongItem>()
            coverOptions.add(targetSong)
            for (rep in repList) {
                if (coverOptions.size >= 3) break
                val exists = coverOptions.any { it.album.equals(rep.album, ignoreCase = true) }
                if (!exists) coverOptions.add(rep)
            }
            Collections.shuffle(coverOptions, random)

            return QuizQuestion(
                type = selectedType,
                promptText = prompt,
                song = targetSong,
                correctAnswer = targetSong.album ?: "",
                coverOptions = coverOptions.map { CoverOption(it.file, it.album ?: "") }
            )
        } else if (selectedType == TYPE_YEAR) {
            val yearInt = extractYear(correctText)
            val optionList = if (yearInt != null) {
                generateTieredYearOptions(yearInt)
            } else {
                generateGenericTextOptions(pool, correctText, selectedType)
            }

            return QuizQuestion(
                type = selectedType,
                promptText = prompt,
                song = targetSong,
                correctAnswer = correctText,
                textOptions = optionList
            )
        } else {
            val optionList = generateGenericTextOptions(pool, correctText, selectedType)

            return QuizQuestion(
                type = selectedType,
                promptText = prompt,
                song = targetSong,
                correctAnswer = correctText,
                textOptions = optionList
            )
        }
    }

    /**
     * Submits an answer with dynamic time-decay scoring.
     * remainingFraction is between 0.0 (time expired) and 1.0 (answered instantly).
     */
    fun submitAnswer(selected: String?, question: QuizQuestion, remainingFraction: Float = 0f): Boolean {
        stopPreview()
        val correct = selected != null && selected.equals(question.correctAnswer, ignoreCase = true)
        if (correct) {
            val timeBonus = (900 * remainingFraction.coerceIn(0f, 1f)).toInt()
            val basePoints = 100 + timeBonus
            val multiplier = 1.0f + ((roundNumber - 1) * 0.1f).coerceAtMost(1.0f)
            val finalPoints = (basePoints * multiplier).toInt()
            score += finalPoints
        } else {
            mistakesInRound++
            lives--
            if (lives <= 0) {
                isGameOver = true
            }
        }
        return correct
    }

    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
        return match?.value?.toIntOrNull()
    }

    /**
     * Tiered Year Distractors:
     * - R1--2: 10-20
     * - R3--5: 5-15
     * - R6--8: 3-8
     * - R8+ (R9+): 1-5
     * Each year follows distance from closest neighbor option, avoiding centering the correct answer.
     */
    private fun generateTieredYearOptions(correctYearInt: Int): List<String> {
        val (minDist, maxDist) = when {
            roundNumber <= 2 -> 10 to 20
            roundNumber <= 5 -> 5 to 15
            roundNumber <= 8 -> 3 to 8
            else -> 1 to 5
        }
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val minYear = 1920

        // Find which position (0..3) correctYearInt can occupy such that
        // all items below can be separated by >= minDist down to minYear,
        // and all items above can be separated by >= minDist up to currentYear.
        val validIndices = ArrayList<Int>()
        for (k in 0..3) {
            val neededBelow = k * minDist
            val neededAbove = (3 - k) * minDist
            if (correctYearInt - neededBelow >= minYear && correctYearInt + neededAbove <= currentYear) {
                validIndices.add(k)
            }
        }

        val k = if (validIndices.isNotEmpty()) validIndices[random.nextInt(validIndices.size)] else 3

        val years = IntArray(4)
        years[k] = correctYearInt

        // Build downward from k
        for (i in k - 1 downTo 0) {
            val dist = minDist + random.nextInt(maxDist - minDist + 1)
            val minAllowed = minYear + (i * minDist)
            val maxAllowed = years[i + 1] - minDist
            val cand = years[i + 1] - dist
            years[i] = cand.coerceIn(minAllowed, maxAllowed)
        }

        // Build upward from k
        for (i in k + 1..3) {
            val dist = minDist + random.nextInt(maxDist - minDist + 1)
            val minAllowed = years[i - 1] + minDist
            val maxAllowed = currentYear - ((3 - i) * minDist)
            val cand = years[i - 1] + dist
            years[i] = cand.coerceIn(minAllowed, maxAllowed)
        }

        val list = years.map { it.toString() }.toMutableList()
        Collections.shuffle(list, random)
        return list
    }

    private fun generateGenericTextOptions(pool: List<SongItem>, correctText: String, selectedType: Int): List<String> {
        val options = LinkedHashSet<String>()
        options.add(correctText)
        var guard = 0
        while (options.size < 4 && guard < 200) {
            guard++
            val candidate = pool[random.nextInt(pool.size)]
            val candText = getFieldValue(candidate, selectedType) ?: continue
            if (!options.any { it.equals(candText, ignoreCase = true) }) {
                options.add(candText)
            }
        }
        val optionList = ArrayList(options)
        while (optionList.size < 4 && pool.isNotEmpty()) {
            val fallbackText = getFieldValue(pool[random.nextInt(pool.size)], selectedType) ?: ""
            optionList.add(fallbackText)
        }
        Collections.shuffle(optionList, random)
        return optionList
    }

    private fun countDistinctField(type: Int): Int {
        val distinct = HashSet<String>()
        for (s in quizPool) {
            if (isValidField(s, type)) {
                getFieldValue(s, type)?.trim()?.lowercase()?.let { distinct.add(it) }
            }
        }
        return distinct.size
    }

    private fun isValidField(s: SongItem, type: Int): Boolean {
        if (type == TYPE_TITLE) return !s.title.isNullOrBlank()
        if (type == TYPE_ALBUM_COVER) {
            val album = s.album
            return !album.isNullOrBlank() &&
                    !album.trim().lowercase().startsWith("unknown") &&
                    (coverArtPredicate?.invoke(s) == true)
        }
        val v = getFieldValue(s, type)
        return !v.isNullOrBlank() && !v.trim().lowercase().startsWith("unknown")
    }

    private fun getFieldValue(s: SongItem, type: Int): String? {
        return when (type) {
            TYPE_ARTIST -> s.artist
            TYPE_ALBUM, TYPE_ALBUM_COVER -> s.album
            TYPE_YEAR -> s.year
            else -> s.title
        }
    }

    /**
     * Plays a clip of [file] in the clip player, from a random point in the middle of the song. A clip that does not
     * start in [CLIP_START_CHECK_MS] (or an error) skips the song in later questions. Without a context (unit tests)
     * there is no clip.
     */
    private fun startPreview(file: File) {
        stopPreview()
        val player = previewPlayer() ?: return
        AudioSources.app.musicStarts()
        try {
            previewFile = file
            clipStart = -1L
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            watchClipStart(file)
        } catch (ignored: Throwable) {
            onPreviewError(file)
        }
    }

    /** The clip player, made at the first clip of the quiz with the audio renderers of the main player, without its effects. */
    private fun previewPlayer(): SimpleExoPlayer? {
        previewExoPlayer?.let { return it }
        val ctx = context ?: return null
        val player = try {
            AudioPlayerManager.newAudioPlayer(ctx, emptyList())
        } catch (e: Throwable) {
            android.util.Log.w("MusicQuizEngine", "Cannot make the clip player", e)
            return null
        }
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.addListener(object : Player.EventListener {
            override fun onPlaybackStateChanged(state: Int) {
                val file = previewFile ?: return
                if (state != Player.STATE_READY || clipStart >= 0) return
                clipStart = clipStartMs(player.duration)
                player.seekTo(clipStart)
                player.playWhenReady = true
                watchClipStart(file)
            }

            override fun onPlayerError(error: ExoPlaybackException) {
                previewFile?.let { onPreviewError(it) }
            }
        })
        previewExoPlayer = player
        VirtualVolumeManager.getInstance().addGainListener(previewGain)
        return player
    }

    /** Not ready, or no progress, [CLIP_START_CHECK_MS] after now: the clip of [file] does not play. */
    private fun watchClipStart(file: File) {
        previewCheck?.let { previewHandler.removeCallbacks(it) }
        val check = Runnable {
            previewCheck = null
            val player = previewExoPlayer
            if (previewFile != file || player == null) return@Runnable
            if (clipStart < 0 || player.currentPosition - clipStart < CLIP_MIN_PROGRESS_MS) onPreviewError(file)
        }
        previewCheck = check
        previewHandler.postDelayed(check, CLIP_START_CHECK_MS)
    }

    /** A random start in the middle of a song of [durationMs], so that the clip is not the intro; 0 for a short song. */
    private fun clipStartMs(durationMs: Long): Long {
        if (durationMs <= 20000) return 0L
        val maxStart = durationMs - 12000
        return (maxStart * 0.2).toLong() + random.nextInt((maxStart * 0.5).toInt().coerceAtLeast(1))
    }

    /** The clip of [file] did not play: stop it, and skip the song in later questions. */
    internal fun onPreviewError(file: File) {
        android.util.Log.w("MusicQuizEngine", "The clip of ${file.absolutePath} does not play")
        stopPreview()
        onPreviewFailed(file)
    }

    /** The clip of [file] did not play: later questions ask about other songs. */
    internal fun onPreviewFailed(file: File) {
        unplayableFiles.add(file)
    }

    fun stopPreview() {
        previewFile = null
        previewCheck?.let { previewHandler.removeCallbacks(it) }
        previewCheck = null
        previewExoPlayer?.let { exo ->
            try {
                exo.stop()
                exo.clearMediaItems()
            } catch (ignored: Throwable) {}
        }
    }

    /** The quiz closes: stop the clip, and release the clip player and its buffer. */
    fun releasePreview() {
        stopPreview()
        VirtualVolumeManager.getInstance().removeGainListener(previewGain)
        previewExoPlayer?.let { exo ->
            try { exo.release() } catch (ignored: Throwable) {}
        }
        previewExoPlayer = null
    }
}
