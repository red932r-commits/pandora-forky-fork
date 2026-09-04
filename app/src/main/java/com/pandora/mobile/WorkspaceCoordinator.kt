package com.pandora.mobile

import com.pandora.mobile.linux.WorkspaceBackup
import com.pandora.mobile.linux.RootfsInstaller
import com.pandora.mobile.linux.runtimeAccess
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Serializes workspace-wide operations and stops every possible writer first. */
class WorkspaceCoordinator(
    private val backup: WorkspaceBackup,
    private val chatSessions: CodexChatSessionManager,
    private val terminalSessions: TerminalSessionManager,
    private val adbPlugin: AdbPluginManager,
    private val installer: RootfsInstaller,
) {
    private val operationMutex = Mutex()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    internal suspend fun <T> inspectRuntime(operation: () -> T): T = operationMutex.withLock {
        withContext(Dispatchers.IO) { runtimeAccess.access {
            installer.assertNoPendingPackageCommand()
            operation()
        } }
    }

    internal suspend fun <T> maintainRuntime(operation: () -> T): T = withQuiescedWorkspace {
        withContext(Dispatchers.IO) { operation() }
    }

    fun recoverInterruptedRestore() = backup.recoverInterruptedRestore()

    suspend fun createBackup(
        openOutput: () -> OutputStream,
        onProgress: (WorkspaceBackup.Progress) -> Unit = {},
    ): WorkspaceBackup.Result =
        withQuiescedWorkspace {
            withContext(Dispatchers.IO) {
                openOutput().use { output -> backup.write(output, onProgress) }
            }
        }

    suspend fun restoreBackup(openInput: () -> InputStream): WorkspaceBackup.Result =
        withQuiescedWorkspace {
            withContext(Dispatchers.IO) { openInput().use(backup::restore) }
        }

    private suspend fun <T> withQuiescedWorkspace(operation: suspend () -> T): T = operationMutex.withLock {
        _busy.value = true
        try {
            runtimeAccess.maintenance {
                val (chatCleanup, terminalCleanup) = withContext(Dispatchers.Main.immediate) {
                    val chats = chatSessions.stopAllForWorkspaceOperation()
                    adbPlugin.stopControl()
                    chats to terminalSessions.stopAllForWorkspaceOperation()
                }
                withTimeout(SESSION_QUIESCE_TIMEOUT_MS) {
                    adbPlugin.awaitWorkspaceIdle()
                    chatCleanup.forEach { it.awaitClosed() }
                    terminalCleanup.sessions.forEach { it.awaitStopped() }
                    withContext(Dispatchers.IO) {
                        val deadline = System.nanoTime() + SESSION_QUIESCE_TIMEOUT_MS * 1_000_000
                        terminalCleanup.persistentCleanup.forEach { cleanup ->
                            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                            cleanup.join(remainingMs)
                            check(!cleanup.isAlive) { "A persistent terminal did not stop in time" }
                        }
                        terminalSessions.stopRemainingPersistentSessions()
                        chatSessions.stopOrphanedAppServers()
                    }
                }
                runtimeAccess.awaitIdle()
                withContext(Dispatchers.IO) { installer.assertNoPendingPackageCommand() }
                operation()
            }
        } finally {
            _busy.value = false
        }
    }

    private companion object {
        const val SESSION_QUIESCE_TIMEOUT_MS = 20_000L
    }
}
