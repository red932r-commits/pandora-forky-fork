package com.pandora.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ImpactPreflight(
    val title: String,
    val summary: String,
    val confirmLabel: String,
    val affectedSessions: String,
    val savedState: String,
    val dataInvolved: String,
    val expectedDuration: String,
    val cancellation: String,
    val rollback: String,
    val caution: String,
)

fun providerSwitchPreflight(activeChats: Int, target: String): ImpactPreflight = ImpactPreflight(
    title = "Switch model provider?",
    summary = "Pandora will stop current provider sessions before routing new work through $target.",
    confirmLabel = "Stop chats and switch",
    affectedSessions = countLabel(activeChats, "active chat") + " will stop",
    savedState = "Messages and files already written stay on this device",
    dataInvolved = "Provider routing and saved credentials; project files are unchanged",
    expectedDuration = "Usually under one minute",
    cancellation = "Available until switching starts",
    rollback = "Switch back from Account & models",
    caution = "Stopped chats cannot resume on the old provider process. Their history remains available.",
)

fun backupPreflight(activeChats: Int, activeTerminals: Int): ImpactPreflight = ImpactPreflight(
    title = "Save a workspace backup?",
    summary = "Pandora stops active writers before exporting a consistent copy of the Linux workspace.",
    confirmLabel = "Choose backup destination",
    affectedSessions = sessionCountLabel(activeChats, activeTerminals) + " will stop",
    savedState = "Completed messages and files are included after sessions stop",
    dataInvolved = "Complete /root, including projects, settings, Codex, and GitHub sessions",
    expectedDuration = "Depends on workspace size and destination speed",
    cancellation = "Available before the export begins",
    rollback = "No device data is changed by creating a backup",
    caution = "The exported ZIP may contain credentials. Store and share it as sensitive data.",
)

fun restorePreflight(activeChats: Int, activeTerminals: Int): ImpactPreflight = ImpactPreflight(
    title = "Restore this backup?",
    summary = "The selected backup will replace the current Linux workspace after validation.",
    confirmLabel = "Stop sessions and restore",
    affectedSessions = sessionCountLabel(activeChats, activeTerminals) + " will stop",
    savedState = "The backup is validated and staged before workspace activation",
    dataInvolved = "Replaces /root, including projects, settings, and account sessions",
    expectedDuration = "Depends on backup size",
    cancellation = "Available until restore starts",
    rollback = "If activation fails, Pandora restores the original workspace",
    caution = "Unsaved process state is not recoverable. Files already written are replaced by the backup.",
)

fun repairPreflight(activeChats: Int, activeTerminals: Int): ImpactPreflight = ImpactPreflight(
    title = "Repair the Linux container?",
    summary = "Pandora will reinstall Debian system files and required packages.",
    confirmLabel = "Stop sessions and repair",
    affectedSessions = sessionCountLabel(activeChats, activeTerminals) + " will stop",
    savedState = "Files and projects in persistent /root remain untouched",
    dataInvolved = "Debian system files and installed system packages",
    expectedDuration = "Usually several minutes; network access may be required",
    cancellation = "Available until repair starts",
    rollback = "No automatic package rollback; persistent /root is preserved",
    caution = "Packages installed outside /root may need to be installed again after repair.",
)

fun deviceControlPreflight(): ImpactPreflight = ImpactPreflight(
    title = "Set up device control?",
    summary = "Pair Pandora with Android Wireless debugging before an agent can control the phone.",
    confirmLabel = "Review Android permissions",
    affectedSessions = "No active session receives control during setup",
    savedState = "Chat, terminal, and project state remain available",
    dataInvolved = "Screen content and taps, typing, swipes, and app launches during control",
    expectedDuration = "Setup usually takes two to five minutes",
    cancellation = "Cancel setup now; stop active control anytime from the safety frame",
    rollback = "Stopping revokes control; actions already performed in apps are not automatically undone",
    caution = "Only allow control for tasks you trust. Avoid exposing secrets while control is active.",
)

private fun countLabel(count: Int, singular: String): String = when (count) {
    0 -> "No $singular"
    1 -> "1 $singular"
    else -> "$count ${singular}s"
}

private fun sessionCountLabel(chats: Int, terminals: Int): String =
    "${countLabel(chats, "active chat")} and ${countLabel(terminals, "active terminal")}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpactPreflightSheet(
    preflight: ImpactPreflight,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.outline, CircleShape),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(.94f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        preflight.title,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preflight.summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            ) {
                PreflightFact(Icons.Rounded.Info, "Affected sessions", preflight.affectedSessions)
                PreflightDivider()
                PreflightFact(Icons.Rounded.Save, "Saved state", preflight.savedState)
                PreflightDivider()
                PreflightFact(Icons.Rounded.Folder, "Data involved", preflight.dataInvolved)
                PreflightDivider()
                PreflightFact(Icons.Rounded.AccessTime, "Duration", preflight.expectedDuration)
                PreflightDivider()
                PreflightFact(Icons.Rounded.Cancel, "Cancellation", preflight.cancellation)
                PreflightDivider()
                PreflightFact(Icons.Rounded.History, "Rollback", preflight.rollback)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                    .padding(13.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text(
                    preflight.caution,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(15.dp))
                    .clickable(role = Role.Button, onClickLabel = preflight.confirmLabel, onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    preflight.confirmLabel,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(15.dp))
                    .clickable(role = Role.Button, onClickLabel = "Cancel", onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PreflightFact(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(.38f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(.62f),
        )
    }
}

@Composable
private fun PreflightDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 51.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
