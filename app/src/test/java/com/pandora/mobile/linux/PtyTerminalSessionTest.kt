package com.pandora.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class PtyTerminalSessionTest {
    @Test
    fun startsInteractiveBashForTabCompletion() {
        val command = terminalShellCommand(persistentSessionName = null, zmxAvailable = true)

        assertEquals(listOf("/bin/bash", "-l", "-i"), command)
        assertFalse(command.contains("/bin/sh"))
    }

    @Test
    fun startsBashInsidePersistentSessions() {
        assertEquals(
            listOf("/root/.local/bin/zmx", "attach", "pandora-test", "/bin/bash", "-l", "-i"),
            terminalShellCommand("pandora-test", zmxAvailable = true),
        )
    }

    @Test
    fun outputChannelBackpressuresInsteadOfDroppingChunks() = runBlocking {
        val channel = terminalOutputChannel(capacity = 1)
        channel.send(byteArrayOf(1))
        var secondSent = false
        val sender = launch {
            channel.send(byteArrayOf(2))
            secondSent = true
        }
        yield()
        assertFalse(secondSent)
        assertEquals(1, channel.receive().single().toInt())
        sender.join()
        assertTrue(secondSent)
        assertEquals(2, channel.receive().single().toInt())
    }
}
