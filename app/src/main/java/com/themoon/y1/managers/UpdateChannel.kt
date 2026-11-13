package com.themoon.y1.managers

import android.content.SharedPreferences
import com.themoon.y1.Prefs
import org.json.JSONArray

/**
 * Settings → General → System Update → Update Channel: which releases System Update offers.
 * - [STABLE]: the releases marked Latest on GitHub (`releases/latest`, which never is a pre-release).
 * - [BETA]: the newest release of either kind (betas are GitHub pre-releases), so a tester also gets a newer stable.
 *
 * The player follows the channel of the build that is installed. A choice in Settings holds only for the builds it was
 * made on or updated to in the app ([Prefs.UPDATE_CHANNEL_BUILDS]): a build installed by hand (a flashed ROM, a
 * sideloaded APK) brings the player back to that build's own channel.
 */
object UpdateChannel {
    const val STABLE = "stable"
    const val BETA = "beta"

    /** The values in the order of the Update Channel list, and their English labels (the app translates them). */
    val VALUES = arrayOf(STABLE, BETA)
    val LABELS = arrayOf("Stable", "Beta")

    /** The release asset with the update metadata (written by the release workflow). */
    const val MANIFEST = "update.json"

    /** The releases that the Beta channel reads: the newest first, drafts are not in the list without a login. */
    const val RELEASES_API_URL = "https://api.github.com/repos/ClassyPlayer/Classy/releases?per_page=10"

    /** AppPrefs keys ([Prefs.UPDATE_CHANNEL], [Prefs.UPDATE_CHANNEL_BUILDS]). */
    const val PREF = "update_channel"
    const val PREF_BUILDS = "update_channel_builds"

    private const val SEPARATOR = ","

    fun label(channel: String): String = LABELS[VALUES.indexOf(normalize(channel)).coerceAtLeast(0)]

    fun normalize(channel: String?): String = if (channel == BETA) BETA else STABLE

    /** The channel of a build from its version name: `20261114-beta`, `20261114-beta.2` are betas. */
    fun ofVersion(versionName: String?): String =
        if (versionName != null && versionName.contains("-beta", ignoreCase = true)) BETA else STABLE

    /**
     * The channel in use: the [choice] made in Settings while [installed] is one of the builds it holds for ([builds],
     * as stored by [store]); otherwise the channel of the installed build.
     */
    fun effective(choice: String?, builds: String?, installed: String?): String {
        val holds = !choice.isNullOrEmpty() && installed != null &&
            builds.orEmpty().split(SEPARATOR).any { it.isNotEmpty() && it == installed }
        return if (holds) normalize(choice) else ofVersion(installed)
    }

    fun current(prefs: SharedPreferences, installed: String?): String =
        effective(Prefs.UPDATE_CHANNEL.get(prefs), Prefs.UPDATE_CHANNEL_BUILDS.get(prefs), installed)

    /** Keeps [channel] for the builds [versionNames] (the installed one, and the one an update installs). */
    fun store(prefs: SharedPreferences, channel: String, vararg versionNames: String?): Boolean {
        val builds = versionNames.filter { !it.isNullOrEmpty() && !it.contains(SEPARATOR) }.distinct().joinToString(SEPARATOR)
        return prefs.edit()
            .putString(Prefs.UPDATE_CHANNEL.key, normalize(channel))
            .putString(Prefs.UPDATE_CHANNEL_BUILDS.key, builds)
            .commit()
    }

    /**
     * The download URL of [manifest] in the newest release of a GitHub releases list ([releasesJson], the answer of
     * [RELEASES_API_URL]); null when no release has it. Releases without it (the hand-made `y1-exp.N`) and drafts are
     * skipped. "Newest" is by tag, as the updater compares versions ([OtaUpdateManager.compareVersionStrings]).
     */
    fun newestManifestUrl(releasesJson: String, manifest: String = MANIFEST): String? {
        val releases = JSONArray(releasesJson)
        var bestTag: String? = null
        var bestUrl: String? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name", "")
            if (tag.isEmpty()) continue
            val assets = release.optJSONArray("assets") ?: continue
            var url: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") == manifest) {
                    url = asset.optString("browser_download_url").takeIf { it.isNotEmpty() }
                    break
                }
            }
            if (url == null) continue
            if (bestTag == null || OtaUpdateManager.compareVersionStrings(tag, bestTag) > 0) {
                bestTag = tag
                bestUrl = url
            }
        }
        return bestUrl
    }

    /** The key in [MANIFEST] for the commits of `main` in the build (see [outcome]). */
    const val MAIN_COMMITS_KEY = "mainCommits"

    /** What a check found: a newer build, the same build, or an older one (the installed build is ahead). */
    enum class Outcome { UPDATE, SAME, AHEAD }

    /**
     * Compares the release ([serverCode], [serverName]) with the installed build by tag and versionCode.
     *
     * [serverMainCommits] and [myMainCommits] are the commits of `main` that each build has (the number of commits on
     * `main` up to the newest one in the build; the release workflow writes it, 0 = not known). A tag does not show
     * this: a promoted older beta and a hotfix from `release/<tag>` get a new date tag but have older code. A release
     * with fewer commits of `main` is [Outcome.AHEAD] (no update, no Reinstall), because its older code would run on data
     * that newer migrations changed. When a number is not known (local builds, releases before the number), only the
     * tags count, as before.
     */
    fun outcome(
        serverCode: Int,
        serverName: String,
        myCode: Int,
        myName: String,
        serverMainCommits: Int = 0,
        myMainCommits: Int = 0
    ): Outcome = when {
        serverMainCommits > 0 && myMainCommits > 0 && serverMainCommits < myMainCommits -> Outcome.AHEAD
        OtaUpdateManager.isServerVersionNewer(serverCode, serverName, myCode, myName) -> Outcome.UPDATE
        OtaUpdateManager.isServerVersionNewer(myCode, myName, serverCode, serverName) -> Outcome.AHEAD
        else -> Outcome.SAME
    }
}
