package com.pandora.mobile

import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactPreflightTest {
    @Test
    fun everyHighImpactOperationUsesTheCompleteContract() {
        val preflights = listOf(
            providerSwitchPreflight(2, "DeepSeek"),
            backupPreflight(1, 2),
            restorePreflight(1, 2),
            repairPreflight(1, 2),
            deviceControlPreflight(),
        )

        preflights.forEach { preflight ->
            assertTrue(preflight.title.isNotBlank())
            assertTrue(preflight.confirmLabel.isNotBlank())
            assertTrue(preflight.affectedSessions.isNotBlank())
            assertTrue(preflight.savedState.isNotBlank())
            assertTrue(preflight.dataInvolved.isNotBlank())
            assertTrue(preflight.expectedDuration.isNotBlank())
            assertTrue(preflight.cancellation.isNotBlank())
            assertTrue(preflight.rollback.isNotBlank())
        }
    }
}
