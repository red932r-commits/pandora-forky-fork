package com.pandora.mobile.linux

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Closes admission before maintenance drains existing writers. Never kills package managers. */
internal class RuntimeAccessGate {
    private val monitor = Any()
    private val depth = ThreadLocal.withInitial { 0 }
    private val maintenanceOwner = ThreadLocal.withInitial { false }
    private var maintenance = false
    private var readers = 0
    private val processes = mutableListOf<Process>()

    fun <T> access(block: () -> T): T {
        val nested = (depth.get() ?: 0) > 0 || maintenanceOwner.get() == true
        synchronized(monitor) {
            check(!maintenance || nested) { "Linux maintenance is in progress. Try again when it finishes." }
            if (!nested) readers++
            depth.set((depth.get() ?: 0) + 1)
        }
        return try { block() } finally {
            synchronized(monitor) {
                depth.set((depth.get() ?: 1) - 1)
                if (!nested) readers--
            }
        }
    }

    fun startProcess(start: () -> Process): Process = access {
        start().also { process ->
            synchronized(monitor) {
                processes.removeAll { !it.isAlive }
                if (maintenanceOwner.get() != true) processes += process
            }
        }
    }

    /** Only for termination commands spawned by the session managers. */
    fun <T> cleanup(block: () -> T): T {
        val previous = maintenanceOwner.get()
        maintenanceOwner.set(true)
        return try { block() } finally { maintenanceOwner.set(previous) }
    }

    suspend fun <T> maintenance(block: suspend () -> T): T {
        synchronized(monitor) {
            check(!maintenance) { "Another Linux maintenance operation is running" }
            maintenance = true
        }
        return try {
            withContext(maintenanceOwner.asContextElement(true)) { block() }
        } finally {
            synchronized(monitor) { maintenance = false }
        }
    }

    suspend fun awaitIdle(timeoutMillis: Long = 30_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val idle = synchronized(monitor) {
                processes.removeAll { !it.isAlive }
                readers == 0 && processes.isEmpty()
            }
            if (idle) return
            check(System.nanoTime() < deadline) {
                "A Linux command is still running. Let it finish, then try maintenance again."
            }
            delay(50)
        }
    }
}

internal val runtimeAccess = RuntimeAccessGate()
