package com.pandora.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadinessStatusTest {
    @Test
    fun deviceControlRequiresPairingBeforeItIsReady() {
        val enabledButUnpaired = AdbPluginState(enabled = true, stage = AdbPluginStage.READY)

        assertEquals(ReadinessLevel.SETUP_REQUIRED, adbReadiness(enabledButUnpaired).level)
        assertEquals("Setup required", adbReadiness(enabledButUnpaired).label)
        assertEquals(
            ReadinessLevel.READY,
            adbReadiness(enabledButUnpaired.copy(stage = AdbPluginStage.PAIRED)).level,
        )
    }

    @Test
    fun deviceControlActiveOverridesConnectionStage() {
        val state = AdbPluginState(
            enabled = true,
            stage = AdbPluginStage.CONNECTED,
            controlActive = true,
        )

        assertEquals(ReadinessLevel.ACTIVE, adbReadiness(state).level)
    }

    @Test
    fun uninstalledVoiceModelCannotBeReportedSelected() {
        val status = speechModelReadiness(SpeechModelDownload.NotInstalled, selected = true)

        assertEquals(ReadinessLevel.SETUP_REQUIRED, status.level)
        assertEquals("Download required", status.label)
    }
}
