package com.pandora.mobile

import android.content.Context
import com.pandora.mobile.linux.RootfsInstaller
import com.pandora.mobile.linux.RuntimeComponent
import com.pandora.mobile.linux.RuntimeUpdate
import com.pandora.mobile.linux.RuntimeUpdateBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class RuntimeOperation { CHECK, UPDATE, ROLLBACK, PACKAGE_RECOVERY, REPAIR }

internal data class RuntimeUpdateState(
    val busy: Boolean = false,
    val checking: Boolean = false,
    val status: String = "Check for newer versions of your Linux tools.",
    val entries: List<RuntimeUpdate> = emptyList(),
    val checkedAt: Long? = null,
    val results: List<String> = emptyList(),
    val error: String? = null,
    val detail: String? = null,
    val operation: RuntimeOperation? = null,
    val rollbackAvailable: Set<RuntimeComponent> = emptySet(),
)

/** Application-owned work survives navigation and rotation; the journal explains process death. */
internal class RuntimeUpdateManager(context: Context, private val coordinator: WorkspaceCoordinator) {
    private val appContext = context.applicationContext
    private val backend = RuntimeUpdateBackend(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val journal = appContext.getSharedPreferences("runtime_updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(RuntimeUpdateState(
        status = if (journal.getBoolean("running", false)) {
            "The previous maintenance operation was interrupted. Check updates to verify installed versions and Debian package state."
        } else journal.getString("result", null) ?: "Check for newer versions of your Linux tools.",
        rollbackAvailable = rollbackOptions(),
        operation = RuntimeOperation.entries.firstOrNull { it.name == journal.getString("operation", null) },
    ))
    val state = _state.asStateFlow()

    fun checkUpdates() = launchOperation("Checking for updates…", checking = true, kind = RuntimeOperation.CHECK) {
        val entries = coordinator.inspectRuntime { backend.check(::status) }
        _state.update { it.copy(entries = entries, checkedAt = System.currentTimeMillis()) }
        val updates = entries.count { it.canUpdate }
        val failures = entries.count { it.error != null }
        when {
            failures > 0 -> "Checked available sources; $failures could not be checked. Retry when connected."
            updates > 0 -> "Updates are available. Choose which tools to update."
            else -> "Your Linux tools are up to date."
        }
    }

    fun updateSelected(selected: Set<RuntimeComponent>) {
        val snapshot = _state.value
        val updates = snapshot.entries.filter { it.component in selected && it.canUpdate }
        if (updates.isEmpty()) return
        launchOperation("Waiting for Linux sessions to stop…") {
            check(snapshot.checkedAt != null && System.currentTimeMillis() - snapshot.checkedAt < 15 * 60_000) {
                "This update check has expired. Check for updates again."
            }
            val results = mutableListOf<String>()
            coordinator.maintainRuntime {
                updates.forEach { update ->
                    try {
                        results += backend.update(update, ::status, ::detail)
                        _state.update { state -> state.copy(entries = state.entries.map { entry ->
                            if (entry.component != update.component) entry else entry.copy(
                                installed = if (entry.component == RuntimeComponent.DEBIAN) entry.installed else entry.available!!,
                                available = null, packages = emptyList(), error = null,
                            )
                        }) }
                    } catch (error: Exception) {
                        val detail = error.message ?: "Update failed"
                        results += "${update.component.title}: update failed. See its error details above."
                        _state.update { state -> state.copy(error = "Some updates did not finish. Check again before retrying.",
                            entries = state.entries.map { if (it.component == update.component) it.copy(error = detail) else it }) }
                    }
                    _state.update { it.copy(results = results.toList()) }
                }
            }
            _state.update { it.copy(checkedAt = null) }
            if (_state.value.error == null) "Selected updates finished. Reopen a chat or terminal to use them."
            else "Updates finished with errors. Successful updates remain installed."
        }
    }

    fun rollback(component: RuntimeComponent) = launchOperation("Stopping sessions before restoring ${component.title}…", kind = RuntimeOperation.ROLLBACK) {
        val result = coordinator.maintainRuntime { backend.rollback(component) }
        _state.update { it.copy(entries = emptyList(), checkedAt = null) }
        "$result. Check updates to refresh versions."
    }

    fun finishPackageSetup() = launchOperation("Stopping sessions before finishing package setup…", kind = RuntimeOperation.PACKAGE_RECOVERY) {
        val result = coordinator.maintainRuntime { backend.finishPackageSetup(::status, ::detail) }
        _state.update { it.copy(entries = emptyList(), checkedAt = null) }
        result
    }

    fun repairLinux() = launchOperation("Preparing Linux repair…", kind = RuntimeOperation.REPAIR) {
        coordinator.maintainRuntime {
            val installer = RootfsInstaller(appContext)
            installer.repair(::status)
            check(installer.isSetupComplete()) { "System files were repaired, but required packages are still missing. Reconnect and open a terminal to finish setup." }
        }
        _state.update { it.copy(entries = emptyList(), checkedAt = null) }
        "Repair complete. Extra Debian packages may need reinstalling."
    }

    @Synchronized
    private fun launchOperation(initialStatus: String, checking: Boolean = false, kind: RuntimeOperation = RuntimeOperation.UPDATE,
        operation: suspend () -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, checking = checking, status = initialStatus, error = null,
            checkedAt = if (checking) null else it.checkedAt, results = emptyList(), detail = null, operation = kind) }
        LinuxSessionService.setMaintenanceActive(appContext, true)
        scope.launch {
            try {
                check(journal.edit().putBoolean("running", true).putString("operation", kind.name).commit()) { "Could not save maintenance recovery state" }
                val result = operation()
                status(result)
                journal.edit().putString("result", result).putBoolean("running", false).commit()
            } catch (error: Exception) {
                val detail = error.message ?: "Linux maintenance failed"
                _state.update { it.copy(status = "Maintenance did not finish.", error = detail) }
                journal.edit().putString("result", "Previous maintenance failed: $detail").putBoolean("running", false).commit()
            } finally {
                _state.update { it.copy(busy = false, checking = false, rollbackAvailable = rollbackOptions()) }
                LinuxSessionService.setMaintenanceActive(appContext, false)
            }
        }
    }

    private fun status(message: String) { _state.update { it.copy(status = message, detail = null) } }
    private fun detail(message: String) { _state.update { it.copy(detail = message) } }
    private fun rollbackOptions() = setOf(RuntimeComponent.CODEX, RuntimeComponent.ZMX).filter(backend::canRollback).toSet()
}
