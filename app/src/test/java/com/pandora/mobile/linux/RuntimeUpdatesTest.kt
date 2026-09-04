package com.pandora.mobile.linux

import org.junit.Assert.*
import org.junit.Test

class RuntimeUpdatesTest {
    @Test fun parsesDebianEpochsArchitecturesAndNewDependencies() {
        val result = parseDebianUpdates("""
            Reading package lists...
            Inst git [1:2.47.2-0+deb13u1] (1:2.47.3-0+deb13u1 Debian:13/stable [arm64])
            Inst libc6:arm64 [2.41-12] (2.41-12+deb13u1 Debian-Security:13 [arm64])
            Inst new-tool (1.0-1 Debian:13 [arm64])
            Conf git (1:2.47.3-0+deb13u1 Debian:13/stable [arm64])
        """.trimIndent())
        assertEquals(3, result.size)
        assertEquals(DebianPackageUpdate("git", "1:2.47.2-0+deb13u1", "1:2.47.3-0+deb13u1"), result[0])
        assertEquals("libc6:arm64", result[1].name)
        assertNull(result[2].installed)
    }

    @Test(expected = IllegalStateException::class) fun rejectsPlansThatRemovePackages() {
        parseDebianUpdates("Inst git [1] (2 stable)\nRemv bash [5.2]")
    }

    @Test(expected = IllegalStateException::class) fun rejectsUnreadableInstallLines() {
        parseDebianUpdates("Inst git unexpected-format")
    }

    @Test fun heldPackagesAreNotPresentedAsInstallable() {
        assertTrue(parseDebianUpdates("The following packages have been kept back:\n  git\n0 upgraded").isEmpty())
    }

    @Test fun comparesNumericVersionsWithoutDowngrading() {
        assertTrue(isNewerRelease("0.152.0", "codex-cli 0.99.0"))
        assertFalse(isNewerRelease("0.99.0", "0.152.0"))
        assertFalse(isNewerRelease("0.152.0", "0.152.0"))
        assertTrue(isNewerRelease("1.0.0", "1.0.0-alpha.3"))
        assertTrue(isNewerRelease("0.8.0", "Not installed"))
        assertEquals("0.8.0", releaseVersion("zmx version v0.8.0\nsocket directory: /tmp"))
    }

    @Test fun failedChecksCannotBecomeSelectable() {
        assertFalse(RuntimeUpdate(RuntimeComponent.CODEX, "0.1.0", "0.2.0", error = "Offline").canUpdate)
        assertFalse(RuntimeUpdate(RuntimeComponent.CODEX, "0.2.0", "0.2.0").canUpdate)
        assertTrue(RuntimeUpdate(RuntimeComponent.CODEX, "Not installed", "0.2.0").canUpdate)
    }
}
