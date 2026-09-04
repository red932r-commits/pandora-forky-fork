package com.pandora.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pandora.mobile.linux.RuntimeComponent
import com.pandora.mobile.linux.RuntimeUpdate

@Composable
internal fun RuntimeUpdatesSettings(activeChats: Int, activeTerminals: Int) {
    val app = LocalContext.current.applicationContext as PandoraApplication
    val manager = app.runtimeUpdates
    val state by manager.state.collectAsState()
    val workspaceBusy by app.workspaceCoordinator.busy.collectAsState()
    val enabled = !state.busy && !workspaceBusy
    var selectedNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showPackages by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.checkedAt) {
        selectedNames = state.entries.filter { it.canUpdate }.map { it.component.name }
    }
    val selected = state.entries.filter { it.component.name in selectedNames && it.canUpdate }

    Text("Update Codex, Debian packages, and persistent terminals without reinstalling Pandora.",
        style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = manager::checkUpdates, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (state.entries.isEmpty()) "Check for updates" else "Check again")
    }
    Spacer(Modifier.height(12.dp))
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
    }
    Text(state.status, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (state.busy) {
        Spacer(Modifier.height(8.dp))
        Text("You can leave this screen. Progress will continue.", style = MaterialTheme.typography.bodyMedium)
    }
    state.detail?.let { output ->
        TextButton(onClick = { showOutput = !showOutput }) { Text(if (showOutput) "Hide command output" else "Show command output") }
        if (showOutput) Text(output, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.error?.let { error ->
        Spacer(Modifier.height(12.dp))
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(20.dp))

    state.entries.forEach { entry ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = entry.component.name in selectedNames, enabled = enabled && entry.canUpdate,
                    role = Role.Checkbox, onValueChange = { checked ->
                        selectedNames = if (checked) selectedNames + entry.component.name else selectedNames - entry.component.name
                    }), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(entry.component.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Installed: ${entry.installed}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.canUpdate) {
                        Text("Available: ${entry.available}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    } else if (entry.error == null) {
                        Text("Up to date", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (entry.canUpdate) Checkbox(checked = entry.component.name in selectedNames,
                    onCheckedChange = null, enabled = enabled)
            }
            entry.error?.let { RuntimeUpdateError(it) }
            if (entry.packages.isNotEmpty()) {
                TextButton(onClick = { showPackages = !showPackages }) {
                    Text(if (showPackages) "Hide package changes" else "Show ${entry.packages.size} package changes")
                }
                if (showPackages) entry.packages.forEach { pkg ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(pkg.name, style = MaterialTheme.typography.labelLarge)
                        Text("${pkg.installed ?: "Not installed"} → ${pkg.available}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (entry.needsPackageRecovery) {
                OutlinedButton(onClick = { confirmation = "packages" }, enabled = enabled) { Text("Finish package setup") }
            }
        }
    }
    if (state.entries.any { it.canUpdate } && state.checkedAt != null) {
        Spacer(Modifier.height(12.dp))
        Button(onClick = { confirmation = "update" }, enabled = enabled && selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Update selected") }
    }
    if (state.results.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        Text("Results", style = MaterialTheme.typography.titleMedium)
        state.results.forEach { result ->
            Text(result, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (state.rollbackAvailable.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        Text("Previous versions", style = MaterialTheme.typography.titleMedium)
        Text("Restore the executable saved before its last update. Debian packages and Codex data are not rolled back.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
        state.rollbackAvailable.forEach { component ->
            OutlinedButton(onClick = { confirmation = component.name }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (component == RuntimeComponent.CODEX) "Restore previous Codex" else "Restore previous zmx")
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    Text("Pandora and its bundled Android runtime update through a new APK. Debian updates stay within the configured release.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    confirmation?.let { action ->
        val rollback = RuntimeComponent.entries.firstOrNull { it.name == action }
        ImpactPreflightSheet(
            preflight = runtimeUpdatePreflight(activeChats, activeTerminals, selected, rollback, action == "packages"),
            onConfirm = {
                confirmation = null
                when {
                    rollback != null -> manager.rollback(rollback)
                    action == "packages" -> manager.finishPackageSetup()
                    else -> manager.updateSelected(selected.map { it.component }.toSet())
                }
            },
            onDismiss = { confirmation = null },
        )
    }
}

@Composable
private fun RuntimeUpdateError(error: String) {
    var expanded by remember(error) { mutableStateOf(false) }
    val summary = error.lineSequence().firstOrNull { it.startsWith("npm ERR! code") }
        ?: error.lineSequence().firstOrNull().orEmpty().take(180)
    Text(summary, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    if (error != summary) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide error details" else "Show error details") }
        if (expanded) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun runtimeUpdatePreflight(
    chats: Int,
    terminals: Int,
    selected: List<RuntimeUpdate>,
    rollback: RuntimeComponent? = null,
    finishPackages: Boolean = false,
): ImpactPreflight = ImpactPreflight(
    title = when { rollback != null -> "Restore previous ${if (rollback == RuntimeComponent.CODEX) "Codex" else "zmx"}?"
        finishPackages -> "Finish Debian package setup?"; else -> "Update selected tools?" },
    summary = when { rollback != null -> "Pandora will reactivate the saved executable."
        finishPackages -> "Pandora will finish configuring packages from the interrupted installation."
        else -> selected.joinToString("\n") { "${it.component.title}: ${it.available}" } },
    confirmLabel = when { rollback != null -> "Stop sessions and restore"; finishPackages -> "Stop sessions and finish setup"; else -> "Stop sessions and update" },
    affectedSessions = "$chats active chats and $terminals active terminals, plus detached terminals and device control, will stop",
    savedState = "Saved messages, files, and projects remain on this device",
    dataInvolved = "Selected Linux tools and their package files",
    expectedDuration = "Several minutes, depending on download size and device speed",
    cancellation = "Cancel before starting. Installation continues if you leave Settings.",
    rollback = "Previous Codex and zmx executables are retained after updates. Debian packages have no automatic rollback.",
    caution = "In-progress work stops. Workspace backups cover /root, not Debian system packages. New Codex versions may migrate their own data when used.",
)
