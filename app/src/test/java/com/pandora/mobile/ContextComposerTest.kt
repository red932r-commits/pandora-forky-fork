package com.pandora.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextComposerTest {
    @Test
    fun mentionOnlyStartsAtAWordBoundaryAndAtTheEnd() {
        assertEquals("Main", contextMentionQuery("Review @Main"))
        assertEquals("", contextMentionQuery("Review @"))
        assertNull(contextMentionQuery("dev@example.com"))
        assertNull(contextMentionQuery("Review @Main file"))
    }

    @Test
    fun selectingContextRemovesOnlyTheActiveMention() {
        assertEquals("Review ", removeContextMention("Review @Main"))
        assertEquals("", removeContextMention("@diff"))
    }

    @Test
    fun terminalContextRedactsCommonSecretsAndKeepsUsefulOutput() {
        val output = redactTerminalContext(
            """
            compiling MainActivity.kt
            API_KEY=sk-test-secret
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature
            BUILD FAILED
            """.trimIndent(),
        )

        assertTrue(output.contains("compiling MainActivity.kt"))
        assertTrue(output.contains("BUILD FAILED"))
        assertTrue(output.contains("[REDACTED]"))
        assertFalse(output.contains("sk-test-secret"))
        assertFalse(output.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun terminalContextKeepsOnlyTheRequestedTail() {
        val output = redactTerminalContext((1..10).joinToString("\n") { "line $it" }, maxLines = 3)

        assertEquals("line 8\nline 9\nline 10", output)
    }

    @Test
    fun terminalContextReportsEveryRedactedValue() {
        val output = redactTerminalContext("API_KEY=one\nPASSWORD=two\nAuthorization: Bearer three")

        assertEquals(3, countContextRedactions(output))
    }

    @Test
    fun emptyContextQueryKeepsDifferentSourceKindsVisible() {
        val files = (1..12).map { index ->
            ComposerContextSuggestion(ComposerContextSeed.FileReference("/root/project/File$index.kt"))
        }
        val terminal = ComposerContextSuggestion(
            ComposerContextSeed.TerminalText("Build terminal", "BUILD FAILED"),
        )

        val result = filterContextSuggestions(files + terminal, "")

        assertTrue(result.any { it.seed.kind == ComposerContextKind.TERMINAL })
        assertTrue(result.count { it.seed.kind == ComposerContextKind.FILE } < result.size)
    }
}
