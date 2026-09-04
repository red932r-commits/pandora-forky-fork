package com.pandora.mobile

import com.pandora.mobile.linux.RuntimeComponent
import com.pandora.mobile.linux.RuntimeUpdate
import org.junit.Assert.*
import org.junit.Test

class RuntimeUpdatePreflightTest {
    @Test fun identifiesSelectedVersionsAndExplainsTheRecoveryBoundary() {
        val preflight = runtimeUpdatePreflight(2, 3, listOf(RuntimeUpdate(RuntimeComponent.CODEX, "0.1.0", "0.2.0")))
        assertTrue(preflight.summary.contains("Codex: 0.2.0"))
        assertTrue(preflight.affectedSessions.contains("2 active chats and 3 active terminals"))
        assertTrue(preflight.affectedSessions.contains("detached terminals"))
        assertTrue(preflight.rollback.contains("Debian packages have no automatic rollback"))
        assertTrue(preflight.caution.contains("migrate"))
    }
}
