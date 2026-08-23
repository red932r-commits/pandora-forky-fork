package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorContextTest {
    @Test
    fun recentTextReturnsABoundedPlainTextTail() {
        val emulator = TerminalEmulator(cols = 12, rows = 3)
        emulator.feed("one\r\ntwo\r\nthree\r\nfour".toByteArray())

        val tail = emulator.recentText(maxLines = 2)

        assertFalse(tail.contains("one"))
        assertTrue(tail.contains("three"))
        assertTrue(tail.contains("four"))
        assertEquals(2, tail.lineSequence().count())
    }
}
