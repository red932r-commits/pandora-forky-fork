package com.pandora.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexModelCatalogTest {
    private val model = CodexModel(
        id = "gpt-5.6",
        model = "gpt-5.6",
        displayName = "GPT-5.6",
        description = "Frontier model",
        isDefault = true,
        supportedReasoningEfforts = listOf(
            CodexReasoningEffort("low", "Fast reasoning"),
            CodexReasoningEffort("medium", "Balanced reasoning"),
            CodexReasoningEffort("high", "Deeper reasoning"),
        ),
        defaultReasoningEffort = "medium",
        serviceTiers = listOf(
            CodexServiceTier("fast", "Fast", "Priority processing"),
        ),
        serviceTiersAdvertised = true,
    )

    @Test
    fun `compatible response settings survive model selection`() {
        assertEquals("high", model.resolveReasoningEffort("high"))
        assertEquals("fast", model.resolveServiceTier("fast"))
    }

    @Test
    fun `unsupported reasoning effort falls back to model default`() {
        assertEquals("medium", model.resolveReasoningEffort("max"))
    }

    @Test
    fun `unsupported service tier falls back to standard`() {
        assertNull(model.resolveServiceTier("flex"))
    }

    @Test
    fun `standard speed stays selected when another model defaults to fast`() {
        assertNull(model.copy(defaultServiceTier = "fast").resolveServiceTier(null))
    }
}
