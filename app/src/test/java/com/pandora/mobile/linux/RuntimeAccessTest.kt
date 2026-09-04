package com.pandora.mobile.linux

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeAccessTest {
    @Test fun waitsForPreviouslyLaunchedProcessesWithoutKillingThem() = runBlocking {
        val gate = RuntimeAccessGate()
        val process = gate.startProcess { FakeProcess() }
        gate.maintenance {
            var timedOut = false
            try { gate.awaitIdle(10) } catch (_: IllegalStateException) { timedOut = true }
            assertTrue(timedOut)
            assertTrue(process.isAlive)
            process.destroy()
            gate.awaitIdle(200)
        }
    }

    @Test fun blocksNewWorkButAllowsMaintenanceCommandsAndReopensAfterFailure() = runBlocking {
        val gate = RuntimeAccessGate()
        try {
            gate.maintenance {
                gate.access { /* Owner may start its installer. */ }
                val failure = AtomicReference<Throwable?>()
                val outsider = thread { try { gate.access { fail("New work was admitted") } } catch (e: Throwable) { failure.set(e) } }
                outsider.join(2000)
                assertFalse(outsider.isAlive)
                assertTrue(failure.get() is IllegalStateException)
                error("Installer failed")
            }
        } catch (_: IllegalStateException) { }
        var started = false
        gate.access { started = true }
        assertTrue(started)
    }

    @Test fun refusesToMutateWhileAnExistingInstallationIsRunning() = runBlocking {
        val gate = RuntimeAccessGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nested = AtomicReference<Throwable?>()
        val worker = thread {
            gate.access {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
                try { gate.access { /* Existing installer may finish its next command. */ } } catch (e: Throwable) { nested.set(e) }
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        gate.maintenance {
            var timedOut = false
            try { gate.awaitIdle(20) } catch (_: IllegalStateException) { timedOut = true }
            assertTrue(timedOut)
            release.countDown()
            worker.join(2000)
            gate.awaitIdle(200)
        }
        assertNull(nested.get())
        assertFalse(worker.isAlive)
    }

    private class FakeProcess : Process() {
        private var alive = true
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun destroy() { alive = false }
        override fun isAlive() = alive
    }
}
