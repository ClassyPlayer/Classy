package com.themoon.y1.managers

import com.themoon.y1.Prefs
import com.themoon.y1.managers.UpdateChannel.BETA
import com.themoon.y1.managers.UpdateChannel.Outcome
import com.themoon.y1.managers.UpdateChannel.STABLE
import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateChannelTest {

    @Test
    fun ofVersion_betaTagsAreBeta() {
        assertEquals(BETA, UpdateChannel.ofVersion("20261114-beta"))
        assertEquals(BETA, UpdateChannel.ofVersion("20261114-beta.2"))
        assertEquals(STABLE, UpdateChannel.ofVersion("20261114"))
        assertEquals(STABLE, UpdateChannel.ofVersion("20261114-v3"))
        assertEquals(STABLE, UpdateChannel.ofVersion(null))
    }

    @Test
    fun effective_noChoice_followsTheInstalledBuild() {
        assertEquals(STABLE, UpdateChannel.effective("", "", "20261114"))
        assertEquals(BETA, UpdateChannel.effective(null, null, "20261114-beta"))
    }

    @Test
    fun effective_choiceOnThisBuild_holds() {
        assertEquals(BETA, UpdateChannel.effective(BETA, "20261114", "20261114"))
        assertEquals(STABLE, UpdateChannel.effective(STABLE, "20261114-beta", "20261114-beta"))
    }

    @Test
    fun effective_choiceAfterAnInAppUpdate_holds() {
        // A tester on Beta updated in the app to a newer stable release: still Beta
        assertEquals(BETA, UpdateChannel.effective(BETA, "20261114-beta,20261115", "20261115"))
    }

    @Test
    fun effective_buildInstalledByHand_resetsTheChoice() {
        assertEquals(STABLE, UpdateChannel.effective(BETA, "20261114-beta,20261114-beta.2", "20261116"))
        assertEquals(BETA, UpdateChannel.effective(STABLE, "20261114", "20261116-beta"))
    }

    @Test
    fun store_keepsTheChannelForTheGivenBuilds() {
        val map = mutableMapOf<String, Any?>()
        val prefs = FakePrefs(map)
        UpdateChannel.store(prefs, BETA, "20261114", "20261115-beta", null, "20261114")
        assertEquals(BETA, map[Prefs.UPDATE_CHANNEL.key])
        assertEquals("20261114,20261115-beta", map[Prefs.UPDATE_CHANNEL_BUILDS.key])
        assertEquals(BETA, UpdateChannel.current(prefs, "20261115-beta"))
        assertEquals(STABLE, UpdateChannel.current(prefs, "20261120"))
    }

    @Test
    fun newestManifestUrl_takesTheNewestReleaseWithTheManifest() {
        val json = """[
            {"tag_name": "y1-exp.2", "prerelease": true, "assets": [{"name": "Classy-y1.apk", "browser_download_url": "u0"}]},
            {"tag_name": "20261114-beta.2", "prerelease": true, "assets": [{"name": "update.json", "browser_download_url": "u1"}]},
            {"tag_name": "20261114-beta", "prerelease": true, "assets": [{"name": "update.json", "browser_download_url": "u2"}]},
            {"tag_name": "20261112", "prerelease": false, "assets": [{"name": "update.json", "browser_download_url": "u3"}]}
        ]"""
        assertEquals("u1", UpdateChannel.newestManifestUrl(json))
    }

    @Test
    fun newestManifestUrl_aNewerStableWins() {
        val json = """[
            {"tag_name": "20261114-beta.2", "prerelease": true, "assets": [{"name": "update.json", "browser_download_url": "beta"}]},
            {"tag_name": "20261114-v3", "prerelease": false, "assets": [{"name": "update.json", "browser_download_url": "stable"}]}
        ]"""
        assertEquals("stable", UpdateChannel.newestManifestUrl(json))
    }

    @Test
    fun newestManifestUrl_skipsDraftsAndOtherManifests() {
        val json = """[
            {"tag_name": "20261120-beta", "draft": true, "assets": [{"name": "update.json", "browser_download_url": "draft"}]},
            {"tag_name": "20261115", "assets": [{"name": "update-y1.json", "browser_download_url": "y1"}]},
            {"tag_name": "20261114", "assets": [{"name": "update.json", "browser_download_url": "y2"}]}
        ]"""
        assertEquals("y2", UpdateChannel.newestManifestUrl(json))
        assertEquals("y1", UpdateChannel.newestManifestUrl(json, "update-y1.json"))
    }

    @Test
    fun newestManifestUrl_noRelease_isNull() {
        assertNull(UpdateChannel.newestManifestUrl("[]"))
        assertNull(UpdateChannel.newestManifestUrl("""[{"tag_name": "y1-exp.1", "assets": []}]"""))
    }

    @Test
    fun outcome_comparesBothWays() {
        assertEquals(Outcome.UPDATE, UpdateChannel.outcome(2026111502, "20261115-beta.2", 2026111400, "20261114"))
        assertEquals(Outcome.SAME, UpdateChannel.outcome(2026111400, "20261114", 2026111400, "20261114"))
        // A beta with the channel Stable: the latest stable is older
        assertEquals(Outcome.AHEAD, UpdateChannel.outcome(2026111200, "20261112", 2026111400, "20261114-beta"))
    }

    @Test
    fun outcome_hotfixFromAnOlderStable_isNotAnUpdateForANewerBeta() {
        // 20261118-beta has 120 commits of main; the hotfix 20261120 from release/20261114 has the 100 of 20261114
        assertEquals(Outcome.AHEAD, UpdateChannel.outcome(2026112000, "20261120", 2026111800, "20261118-beta", 100, 120))
    }

    @Test
    fun outcome_promotedOlderBeta_isNotAnUpdateForANewerBeta() {
        // 20261114-beta (105 commits of main) promoted to stable 20261120, on a player with 20261118-beta (120)
        assertEquals(Outcome.AHEAD, UpdateChannel.outcome(2026112000, "20261120", 2026111800, "20261118-beta", 105, 120))
        // The same tag and code, but older code: no Reinstall
        assertEquals(Outcome.AHEAD, UpdateChannel.outcome(2026111400, "20261114", 2026111400, "20261114-beta", 105, 120))
    }

    @Test
    fun outcome_sameOrMoreCommitsOfMain_comparesTheTags() {
        // The beta promoted to stable: the same commit, a newer tag
        assertEquals(Outcome.UPDATE, UpdateChannel.outcome(2026112000, "20261120", 2026111800, "20261118-beta", 120, 120))
        // From a hotfix to the next release from main (it has more commits of main)
        assertEquals(Outcome.UPDATE, UpdateChannel.outcome(2026112500, "20261125", 2026112000, "20261120", 130, 100))
        // A second hotfix on the same release branch
        assertEquals(Outcome.UPDATE, UpdateChannel.outcome(2026112200, "20261122", 2026112000, "20261120", 100, 100))
        assertEquals(Outcome.SAME, UpdateChannel.outcome(2026112000, "20261120", 2026112000, "20261120", 100, 100))
        // More commits of main never make an update of an older tag
        assertEquals(Outcome.AHEAD, UpdateChannel.outcome(2026111200, "20261112", 2026111400, "20261114-beta", 130, 120))
    }

    @Test
    fun outcome_unknownCommitsOfMain_comparesOnlyTheTags() {
        // Local builds and releases from before the number: as before
        assertEquals(Outcome.UPDATE, UpdateChannel.outcome(2026112000, "20261120", 2026111800, "20261118-beta", 100, 0))
        assertEquals(Outcome.UPDATE, UpdateChannel.outcome(2026112000, "20261120", 2026111800, "20261118-beta", 0, 120))
        assertEquals(Outcome.UPDATE, UpdateChannel.outcome(2026112000, "20261120", 2026111800, "20261118-beta", -1, 120))
        assertEquals(Outcome.SAME, UpdateChannel.outcome(2026112000, "20261120", 2026112000, "20261120", 0, 120))
    }
}
