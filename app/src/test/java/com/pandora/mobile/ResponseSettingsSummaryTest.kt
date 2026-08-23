package com.pandora.mobile

import com.pandora.mobile.linux.CodexModel
import com.pandora.mobile.linux.CodexReasoningEffort
import com.pandora.mobile.linux.CodexServiceTier
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseSettingsSummaryTest {
    private val model = CodexModel(
        id = "gpt-5.6",
        model = "gpt-5.6",
        displayName = "GPT-5.6",
        description = "Frontier model",
        isDefault = true,
        supportedReasoningEfforts = listOf(CodexReasoningEffort("xhigh", "Deep reasoning")),
        serviceTiers = listOf(CodexServiceTier("fast", "Fast", "Priority processing")),
        serviceTiersAdvertised = true,
    )

    @Test
    fun `summary shows model thinking and speed`() {
        assertEquals(
            "GPT-5.6 · Extra high · Fast",
            responseSettingsSummary(model, "fallback", "xhigh", "fast"),
        )
    }

    @Test
    fun `summary names the default speed tier`() {
        assertEquals(
            "GPT-5.6 · Extra high · Standard",
            responseSettingsSummary(model, "fallback", "xhigh", null),
        )
    }
}
