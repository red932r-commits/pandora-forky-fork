package com.pandora.mobile.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKnowledgeTest {
    @Test
    fun refreshesManagedSectionsAndPreservesUserInstructions() {
        val installed = """
            # Pandora environment

            User-authored preface.

            ## ADB phone control plugin

            Use the unauthenticated bridge.

            ## Agent notifications

            Send an unauthenticated notification.

            ## User workflow

            Never overwrite this section.
        """.trimIndent()
        val bundled = """
            # Pandora environment

            ## ADB phone control plugin

            Use the authenticated bridge token.

            ## Agent notifications

            Send an authenticated notification.
        """.trimIndent()

        val refreshed = refreshMarkdownSections(
            installed,
            bundled,
            listOf("## ADB phone control plugin", "## Agent notifications"),
        )

        assertTrue(refreshed.contains("User-authored preface."))
        assertTrue(refreshed.contains("Use the authenticated bridge token."))
        assertTrue(refreshed.contains("Send an authenticated notification."))
        assertTrue(refreshed.contains("Never overwrite this section."))
        assertFalse(refreshed.contains("unauthenticated"))
    }

    @Test
    fun appendsManagedSectionWhenAnOlderFileDoesNotHaveIt() {
        val refreshed = refreshMarkdownSections(
            content = "# Custom instructions\n\nKeep me.\n",
            source = "# Bundled\n\n## Agent notifications\n\nUse the bridge token.\n",
            headings = listOf("## Agent notifications"),
        )

        assertTrue(refreshed.contains("Keep me."))
        assertTrue(refreshed.contains("## Agent notifications\n\nUse the bridge token."))
    }
}
