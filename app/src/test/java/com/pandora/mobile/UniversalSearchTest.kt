package com.pandora.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalSearchTest {
    private val items = listOf(
        UniversalSearchItem(
            id = "project:pandora",
            kind = UniversalSearchKind.PROJECT,
            title = "Pandora",
            subtitle = "~/projects/pandora",
            target = UniversalSearchTarget.Project("/root/projects/pandora"),
        ),
        UniversalSearchItem(
            id = "file:settings",
            kind = UniversalSearchKind.FILE,
            title = "SettingsScreen.kt",
            subtitle = "~/projects/pandora/app/SettingsScreen.kt",
            target = UniversalSearchTarget.File(
                "/root/projects/pandora/app/SettingsScreen.kt",
                "/root/projects/pandora",
            ),
        ),
        UniversalSearchItem(
            id = "command:review",
            kind = UniversalSearchKind.COMMAND,
            title = "/review",
            subtitle = "Review uncommitted workspace changes",
            target = UniversalSearchTarget.Command("/review"),
        ),
    )

    @Test
    fun everyWordMustMatchAcrossTheResult() {
        val result = filterUniversalSearch(items, "settings pandora", UniversalSearchFilter.ALL)

        assertEquals(listOf("file:settings"), result.map { it.id })
    }

    @Test
    fun exactTitleRanksAheadOfSubtitleMatches() {
        val result = filterUniversalSearch(items, "pandora", UniversalSearchFilter.ALL)

        assertEquals("project:pandora", result.first().id)
    }

    @Test
    fun categoryFiltersRemainStrict() {
        val result = filterUniversalSearch(items, "", UniversalSearchFilter.COMMANDS)

        assertEquals(listOf("command:review"), result.map { it.id })
        assertTrue(result.all { it.kind == UniversalSearchKind.COMMAND })
    }
}
