package com.pandora.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pandora.mobile.linux.CodexModel
import com.pandora.mobile.linux.CodexReasoningEffort

@Composable
internal fun ResponseSettingsButton(
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (enabled) {
                    contentDescription = "Response settings: $summary"
                    role = Role.Button
                }
            }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Codex",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = summary,
                modifier = Modifier.weight(1f, fill = false),
                color = if (enabled) accent else muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (enabled) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResponseSettingsSheet(
    models: List<CodexModel>,
    selectedModel: String?,
    selectedReasoningEffort: String?,
    selectedServiceTier: String?,
    onSelectModel: (String) -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    onSelectServiceTier: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selected = models.firstOrNull { it.model == selectedModel } ?: if (selectedModel == null) {
        models.firstOrNull(CodexModel::isDefault) ?: models.firstOrNull()
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Response settings",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Choose how Codex responds in this chat. Changes apply from your next message.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close response settings")
                    }
                }

                Spacer(Modifier.height(24.dp))
                SettingsSectionLabel("Model")
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column {
                        models.forEachIndexed { index, model ->
                            SettingsChoiceRow(
                                title = model.displayName,
                                description = model.description,
                                selected = model.model == selected?.model,
                                onClick = { onSelectModel(model.model) },
                            )
                            if (index != models.lastIndex) HorizontalDivider()
                        }
                    }
                }

                if (selected != null && selected.supportedReasoningEfforts.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    SettingsSectionLabel("Thinking")
                    Spacer(Modifier.height(8.dp))
                    ReasoningEffortGrid(
                        options = selected.supportedReasoningEfforts,
                        selected = selectedReasoningEffort,
                        onSelect = onSelectReasoningEffort,
                    )
                    selected.supportedReasoningEfforts
                        .firstOrNull { it.value == selectedReasoningEffort }
                        ?.description
                        ?.takeIf(String::isNotBlank)
                        ?.let { description ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                }

                if (selected?.serviceTiersAdvertised == true) {
                    Spacer(Modifier.height(24.dp))
                    SettingsSectionLabel("Speed")
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column {
                            SettingsChoiceRow(
                                title = "Standard",
                                description = "Balanced speed for everyday tasks.",
                                selected = selectedServiceTier == null,
                                onClick = { onSelectServiceTier(null) },
                            )
                            selected.serviceTiers.forEach { tier ->
                                HorizontalDivider()
                                SettingsChoiceRow(
                                    title = tier.name,
                                    description = tier.description,
                                    selected = selectedServiceTier == tier.id,
                                    onClick = { onSelectServiceTier(tier.id) },
                                )
                            }
                        }
                    }
                    if (selected.serviceTiers.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No additional speed tier is available for this model.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (
                    selected != null &&
                    selected.supportedReasoningEfforts.isEmpty() &&
                    !selected.serviceTiersAdvertised
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "This model provider does not expose thinking or speed controls.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun ReasoningEffortGrid(
    options: List<CodexReasoningEffort>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { option ->
                    val isSelected = option.value == selected
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.value) },
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text(
                                    text = reasoningEffortLabel(option.value),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                repeat(3 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal fun reasoningEffortLabel(value: String): String = when (value.lowercase()) {
    "none" -> "Minimal"
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "Extra high"
    "max" -> "Max"
    "ultra" -> "Ultra"
    else -> value.replace('-', ' ').replaceFirstChar(Char::uppercase)
}

internal fun responseSettingsSummary(
    model: CodexModel?,
    fallbackModel: String,
    reasoningEffort: String?,
    serviceTier: String?,
): String {
    val parts = mutableListOf(model?.displayName ?: fallbackModel)
    if (model?.supportedReasoningEfforts?.isNotEmpty() == true && reasoningEffort != null) {
        parts += reasoningEffortLabel(reasoningEffort)
    }
    if (model?.serviceTiersAdvertised == true) {
        parts += model.serviceTiers.firstOrNull { it.id == serviceTier }?.name ?: "Standard"
    }
    return parts.joinToString(" · ")
}
