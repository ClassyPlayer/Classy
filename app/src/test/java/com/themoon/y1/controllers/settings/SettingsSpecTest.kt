package com.themoon.y1.controllers.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsSpecTest {

    private fun source(name: String, pkg: String = "settings"): String {
        val dir = "src/main/java/com/themoon/y1/controllers/$pkg"
        return listOf(File("$dir/$name"), File("app/$dir/$name")).first { it.exists() }.readText()
    }

    /** The groups of the spec in SettingsController ([SettingsController.groups]) and their row labels, in order. */
    private fun specGroups(): List<Pair<String, List<String>>> {
        val code = source("SettingsController.kt")
        val start = code.indexOf("private val groups: List<SettingsGroup>")
        assertTrue("the groups spec is not found", start >= 0)
        val end = code.indexOf("\n    ) } }", start)
        val re = Regex("""SettingsGroup\("([^"]+)"|\b(?:toggle|cycle|choice|action|SettingRow)\("([^"]+)"""")
        val out = ArrayList<Pair<String, MutableList<String>>>()
        for (m in re.findAll(code.substring(start, end))) {
            val group = m.groupValues[1]
            if (group.isNotEmpty()) out += group to ArrayList() else out.last().second += m.groupValues[2]
        }
        // Settings → Photos shows the slideshow rows (the same list as Photos → Slideshow Settings)
        out.firstOrNull { it.first == "Photos" }?.let { photos ->
            val code2 = source("PhotoSettings.kt", "photos")
            val from = code2.indexOf("fun rows(")
            val to = code2.indexOf("\n    )\n", from)
            for (m in re.findAll(code2.substring(from, to))) photos.second += m.groupValues[2]
        }
        // Settings → Books shows the rows of the reader (the same list as Reader Settings in a book)
        out.firstOrNull { it.first == "Books" }?.let { books ->
            val reader = source("ReaderSettings.kt", "books")
            val from = reader.indexOf("fun rows(")
            val to = reader.indexOf("\n    )\n", from)
            for (m in re.findAll(reader.substring(from, to))) books.second += m.groupValues[2]
        }
        return out
    }

    /** The groups, the rows, their order and labels as before the spec (SettingsGroups.GROUPS with RENAMES). */
    @Test
    fun theGroupsAndTheirRowsAreAsBefore() {
        assertEquals(
            listOf(
                "Playback" to listOf("Shuffle", "Repeat", "Previous Restarts Song", "EQ", "Sound Check", "Mono Audio", "Volume Limit", "Playback Speed"),
                "Display" to listOf("Brightness", "Backlight"),
                "Now Playing" to listOf("Spectrum", "Total Time", "Lyrics", "Lyrics Keep Screen On", "Cover Tilt"),
                "Photos" to listOf("Time Per Slide", "Repeat Slideshow", "Shuffle Photos", "Transitions"),
                "Books" to listOf("Page Colours", "Font Size", "Line Spacing", "Font", "Wheel", "Wheel Clicks per Page", "Keep Screen On"),
                "Status Bar" to listOf("Clock", "Battery Icon", "Battery Percentage"),
                "Appearance" to listOf("Theme", "Toggle Style", "List Edges", "Menu Cover Motion", "Fill Video Screen"),
                "Menus" to listOf("Main Menu Items", "Music Menu Items", "Group Albums", "Cover Flow"),
                "Controls" to listOf(
                    "Button Sound", "Click Volume", "Button Vibration", "Vibration Strength", "Wheel Mode", "Wheel Loop Scroll",
                    "List Glide", "Letter Jump", "Letter Jump Sensitivity", "Screen-Off Control", "Double-Click to Favorite", "Hold Back for Now Playing"
                ),
                "Connectivity" to listOf("Bluetooth", "Wi-Fi", "Web Server", "USB Drive", "Use as USB Drive"),
                "Battery" to listOf("Battery"),
                "Library" to listOf("Clear Album Art & Info", "Jellyfin Sync", "WebDAV Sync", "No Duplicates in Playlists", "Sync Scrobbles to Last.fm", "Last.fm Account"),
                "Date & Time" to listOf("Date & Time"),
                "General" to listOf(
                    "About", "Language", "System Update", "Legal", "Export Settings", "Import Settings", "Reset Settings", "Power"
                ),
            ),
            specGroups()
        )
    }

    @Test
    fun theTopLevelHasAtMostFifteenShortGroupsAndEachRowIsInOneGroup() {
        val groups = specGroups()
        // 13 with Battery (2026-10-31): the top level as Settings of iOS and macOS; 15 with Photos (the slideshow)
        // and Books (the ebook reader)
        assertTrue(groups.size in 6..15)
        for ((title, rows) in groups) {
            assertTrue("$title: label too long", title.length <= 12)
            // Controls: 12 with Wheel Mode and List Glide (Y2 only) and Hold Back for Now Playing; each switch keeps its setting row under it
            val most = if (title == "Controls") 12 else 10
            assertTrue("$title: ${rows.size} rows", rows.size in 1..most)
        }
        val all = groups.flatMap { it.second }
        assertEquals(all.size, all.toSet().size)
        assertTrue("Auto Fetch Album Art" !in all)
        assertTrue("the Vibration sub-screen is back", "Vibration" !in all)
    }
}
