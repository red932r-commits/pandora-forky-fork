package com.pandora.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbBridgeSecurityTest {
    @Test
    fun bridgeTokenMustMatchExactly() {
        assertTrue(secureEquals("secret-token", "secret-token"))
        assertFalse(secureEquals("secret-token", null))
        assertFalse(secureEquals("secret-token", "secret-toke"))
        assertFalse(secureEquals("secret-token", "secret-token-extra"))
    }
}
