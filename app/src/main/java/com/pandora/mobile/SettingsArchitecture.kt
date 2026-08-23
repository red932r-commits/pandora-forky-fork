package com.pandora.mobile

import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pandora.mobile.linux.RootfsInstaller
import com.pandora.mobile.linux.WorkspaceBackup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SettingsDestination(val title: String) {
    ACCOUNT_MODELS("Account & models"),
    TERMINAL_LINUX("Terminal & Linux"),
    STORAGE_RECOVERY("Storage & recovery"),
    APPEARANCE("Appearance"),
    DIAGNOSTICS("Diagnostics"),
}

@Composable
internal fun SettingsScreen(
    initialDestination: SettingsDestination? = null,
    themePreference: AppSettings.ThemePreference,
    activeChatCount: Int,
    activeTerminalCount: Int,
    onThemePreferenceChanged: (AppSettings.ThemePreference) -> Unit,
    onBack: () -> Unit,
    onArchive: () -> Unit,
    onPlugins: () -> Unit,
    onVoice: () -> Unit,
    onCodexLogin: () -> Unit,
    onModelProviderChanged: (ModelProviderMode) -> Unit,
    onChangeModelProvider: () -> Unit,
    onStopAllForRepair: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as PandoraApplication
    var destinationName by rememberSaveable(initialDestination) { mutableStateOf(initialDestination?.name) }
    val directEntry = rememberSaveable(initialDestination) { initialDestination != null }
    val destination = destinationName?.let { name ->
        SettingsDestination.entries.firstOrNull { it.name == name }
    }
    val navigateBack = {
        if (destination == null || directEntry) onBack() else destinationName = null
    }
    BackHandler(enabled = destination != null) { navigateBack() }

    SettingsScaffold(
        title = destination?.title ?: "Settings",
        onBack = navigateBack,
    ) {
        when (destination) {
            null -> SettingsIndex(
                themePreference = themePreference,
                onOpen = { destinationName = it.name },
                onVoice = onVoice,
                onDeviceControl = onPlugins,
            )
            SettingsDestination.ACCOUNT_MODELS -> AccountAndModelsSettings(
                activeChatCount = activeChatCount,
                onCodexLogin = onCodexLogin,
                onModelProviderChanged = onModelProviderChanged,
                onChangeModelProvider = onChangeModelProvider,
            )
            SettingsDestination.TERMINAL_LINUX -> TerminalAndLinuxSettings(
                activeChatCount = activeChatCount,
                activeTerminalCount = activeTerminalCount,
                onStopAllForRepair = onStopAllForRepair,
            )
            SettingsDestination.STORAGE_RECOVERY -> StorageAndRecoverySettings(
                workspaceCoordinator = application.workspaceCoordinator,
                activeChatCount = activeChatCount,
                activeTerminalCount = activeTerminalCount,
                onArchive = onArchive,
            )
            SettingsDestination.APPEARANCE -> AppearanceSettings(
                themePreference = themePreference,
                onThemePreferenceChanged = onThemePreferenceChanged,
            )
            SettingsDestination.DIAGNOSTICS -> DiagnosticsSettings()
        }
    }
}

@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsIndex(
    themePreference: AppSettings.ThemePreference,
    onOpen: (SettingsDestination) -> Unit,
    onVoice: () -> Unit,
    onDeviceControl: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as PandoraApplication
    val adbState by application.adbPlugin.state.collectAsState()
    val provider = remember { ModelProviderSettings.mode(context) }
    val customProvider = remember { ModelProviderSettings.savedCustomProvider(context) }
    val customProviderHost = customProvider?.let { architectureProviderHost(it.baseUrl) }
    val linuxReady = remember { RootfsInstaller(context.applicationContext).isSetupComplete() }
    val items = listOf(
        SettingsIndexItem(
            title = "Account & models",
            summary = when (provider) {
                ModelProviderMode.CODEX -> "ChatGPT · Codex"
                ModelProviderMode.CUSTOM -> "Codex OSS · ${customProviderHost ?: "provider configured"}"
            },
            icon = Icons.Rounded.AccountCircle,
            action = { onOpen(SettingsDestination.ACCOUNT_MODELS) },
        ),
        SettingsIndexItem(
            title = "Voice",
            summary = "Dictation and spoken responses",
            icon = Icons.Rounded.GraphicEq,
            action = onVoice,
        ),
        SettingsIndexItem(
            title = "Device control",
            summary = adbReadiness(adbState).label,
            icon = Icons.Rounded.PhoneAndroid,
            action = onDeviceControl,
        ),
        SettingsIndexItem(
            title = "Terminal & Linux",
            summary = if (linuxReady) "Pandora Linux ready" else "Setup incomplete",
            icon = Icons.Rounded.Terminal,
            action = { onOpen(SettingsDestination.TERMINAL_LINUX) },
        ),
        SettingsIndexItem(
            title = "Storage & recovery",
            summary = "Backups, restore, and archive",
            icon = Icons.Rounded.Storage,
            action = { onOpen(SettingsDestination.STORAGE_RECOVERY) },
        ),
        SettingsIndexItem(
            title = "Appearance",
            summary = when (themePreference) {
                AppSettings.ThemePreference.SYSTEM -> "System theme"
                AppSettings.ThemePreference.LIGHT -> "Light theme"
                AppSettings.ThemePreference.DARK -> "Dark theme"
            },
            icon = Icons.Rounded.Tune,
            action = { onOpen(SettingsDestination.APPEARANCE) },
        ),
        SettingsIndexItem(
            title = "Diagnostics",
            summary = "Build and runtime status",
            icon = Icons.Rounded.Memory,
            action = { onOpen(SettingsDestination.DIAGNOSTICS) },
        ),
    )
    items.forEachIndexed { index, item ->
        SettingsIndexRow(item)
        if (index != items.lastIndex) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 62.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

private data class SettingsIndexItem(
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val action: () -> Unit,
)

@Composable
private fun SettingsIndexRow(item: SettingsIndexItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
            .clickable(role = Role.Button, onClickLabel = "Open ${item.title}", onClick = item.action)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                item.summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AccountAndModelsSettings(
    activeChatCount: Int,
    onCodexLogin: () -> Unit,
    onModelProviderChanged: (ModelProviderMode) -> Unit,
    onChangeModelProvider: () -> Unit,
) {
    val context = LocalContext.current
    val codexInstalled = remember { File(context.filesDir, "linux-workspace/.local/bin/codex").exists() }
    val codexSignedIn = remember { File(context.filesDir, "linux-workspace/.codex/auth.json").exists() }
    val customProvider = remember { ModelProviderSettings.savedCustomProvider(context) }
    val customProviderHost = customProvider?.let { architectureProviderHost(it.baseUrl) }
    val customConfigured = customProvider != null && ModelProviderSecretStore.hasApiKey(context)
    var providerMode by remember { mutableStateOf(ModelProviderSettings.mode(context)) }
    var pendingProviderMode by remember { mutableStateOf<ModelProviderMode?>(null) }
    var pendingProviderSetup by remember { mutableStateOf(false) }

    SettingsGroupTitle("Workspace on this device")
    SettingsInfoPanel(
        icon = Icons.Rounded.Storage,
        title = "Linux workspace",
        summary = "/root · projects, chats, and settings stay on this device",
    )

    Spacer(Modifier.height(26.dp))
    SettingsGroupTitle("Model provider · remote inference")
    ProviderChoice(
        title = "Codex OSS",
        summary = customProvider?.let {
            "Remote via ${customProviderHost ?: "saved provider"}\n${it.defaultModel.substringAfterLast('/')}"
        } ?: "OpenAI-compatible endpoint\nConfigure a remote model",
        selected = providerMode == ModelProviderMode.CUSTOM,
        enabled = codexInstalled,
        icon = Icons.Rounded.Hub,
        onClick = {
            if (customConfigured) {
                if (providerMode != ModelProviderMode.CUSTOM) {
                    pendingProviderMode = ModelProviderMode.CUSTOM
                }
            } else {
                pendingProviderSetup = true
            }
        },
    )
    Spacer(Modifier.height(10.dp))
    ProviderChoice(
        title = "Codex",
        summary = "Hosted by OpenAI\nUses your ChatGPT account",
        selected = providerMode == ModelProviderMode.CODEX,
        enabled = codexInstalled,
        icon = null,
        customIcon = {
            Image(
                painter = painterResource(R.drawable.codex_mark),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
        onClick = {
            if (providerMode != ModelProviderMode.CODEX) {
                pendingProviderMode = ModelProviderMode.CODEX
            }
        },
    )

    Spacer(Modifier.height(26.dp))
    SettingsGroupTitle("Accounts and profiles")
    ArchitectureActionRow(
        title = "ChatGPT account",
        subtitle = when {
            !codexInstalled -> "Codex is still installing"
            codexSignedIn -> "Signed in · authenticate again"
            else -> "Sign in with ChatGPT"
        },
        icon = null,
        customIcon = {
            Image(
                painter = painterResource(R.drawable.codex_mark),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
        enabled = codexInstalled,
        onClick = onCodexLogin,
    )
    ArchitectureDivider()
    ArchitectureActionRow(
        title = "Codex OSS profile",
        subtitle = if (customConfigured) {
            "${customProviderHost ?: "Saved provider"} · ${customProvider?.defaultModel?.substringAfterLast('/')}"
        } else {
            "Set up an OpenAI-compatible provider"
        },
        icon = Icons.Rounded.Tune,
        enabled = codexInstalled,
        onClick = onChangeModelProvider,
    )

    val pending = pendingProviderMode
    if (pending != null || pendingProviderSetup) {
        val target = when {
            pendingProviderSetup -> "a new Codex OSS profile"
            pending == ModelProviderMode.CODEX -> "OpenAI"
            else -> customProvider?.defaultModel ?: "the saved remote provider"
        }
        ImpactPreflightSheet(
            preflight = providerSwitchPreflight(activeChatCount, target),
            onConfirm = {
                pendingProviderMode = null
                val openSetup = pendingProviderSetup
                pendingProviderSetup = false
                if (openSetup) {
                    onChangeModelProvider()
                } else if (pending != null) {
                    onModelProviderChanged(pending)
                    providerMode = pending
                }
            },
            onDismiss = {
                pendingProviderMode = null
                pendingProviderSetup = false
            },
        )
    }
}

@Composable
private fun ProviderChoice(
    title: String,
    summary: String,
    selected: Boolean,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    customIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 94.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f) else Color.Transparent,
                RoundedCornerShape(15.dp),
            )
            .border(1.dp, borderColor, RoundedCornerShape(15.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                customIcon != null -> customIcon()
                icon != null -> Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(3.dp))
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(22.dp)
                .border(2.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(11.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@Composable
private fun TerminalAndLinuxSettings(
    activeChatCount: Int,
    activeTerminalCount: Int,
    onStopAllForRepair: () -> Unit,
) {
    val context = LocalContext.current
    var fontSize by remember { mutableFloatStateOf(AppSettings.terminalFontSize(context)) }
    var showRepairPreflight by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var repairStatus by remember { mutableStateOf<String?>(null) }
    var repairError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun updateFontSize(value: Float) {
        fontSize = value.roundToInt().toFloat().coerceIn(
            AppSettings.MIN_TERMINAL_FONT_SIZE,
            AppSettings.MAX_TERMINAL_FONT_SIZE,
        )
        AppSettings.setTerminalFontSize(context, fontSize)
    }

    SettingsGroupTitle("Terminal text")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Font size", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            Text("Applies to newly opened terminals", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Text("${fontSize.roundToInt()} sp", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ArchitectureStepButton("−") { updateFontSize(fontSize - 1f) }
        Slider(
            value = fontSize,
            onValueChange = ::updateFontSize,
            valueRange = AppSettings.MIN_TERMINAL_FONT_SIZE..AppSettings.MAX_TERMINAL_FONT_SIZE,
            steps = 12,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
        ArchitectureStepButton("+") { updateFontSize(fontSize + 1f) }
    }
    Spacer(Modifier.height(14.dp))
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF111210), RoundedCornerShape(15.dp)).padding(16.dp),
    ) {
        Text(
            "localhost:~# lscpu\nArchitecture: aarch64",
            color = Color(0xFFD8E4D1),
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.45f).sp,
            fontFamily = FontFamily.Monospace,
        )
    }

    Spacer(Modifier.height(28.dp))
    SettingsGroupTitle("Linux container")
    Text(
        "Maintenance resets Debian system files while keeping your persistent /root workspace.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    Spacer(Modifier.height(8.dp))
    ArchitectureActionRow(
        title = "Repair Linux container",
        subtitle = "Stop sessions, reinstall system files, and keep project data",
        icon = Icons.Rounded.Build,
        enabled = !repairing,
        onClick = {
            repairError = null
            showRepairPreflight = true
        },
    )
    repairStatus?.let { status ->
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }

    repairError?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    }

    if (showRepairPreflight) {
        ImpactPreflightSheet(
            preflight = repairPreflight(activeChatCount, activeTerminalCount),
            onConfirm = {
                showRepairPreflight = false
                repairing = true
                repairStatus = "Preparing repair…"
                repairError = null
                onStopAllForRepair()
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            RootfsInstaller(context.applicationContext).repair { repairStatus = it }
                        }
                    }.onSuccess {
                        repairing = false
                        repairStatus = "Repair complete"
                    }.onFailure { error ->
                        repairing = false
                        repairError = "Repair failed: ${error.message}"
                    }
                }
            },
            onDismiss = { showRepairPreflight = false },
        )
    }
}

@Composable
private fun StorageAndRecoverySettings(
    workspaceCoordinator: WorkspaceCoordinator,
    activeChatCount: Int,
    activeTerminalCount: Int,
    onArchive: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupBusy by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var backupProgress by remember { mutableStateOf<WorkspaceBackup.Progress?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var showBackupPreflight by remember { mutableStateOf(false) }
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            backupBusy = true
            backupStatus = "Saving backup…"
            backupProgress = null
            scope.launch {
                val result = runCatching {
                    workspaceCoordinator.createBackup(
                        openOutput = {
                            checkNotNull(context.contentResolver.openOutputStream(uri, "w")) {
                                "Could not open the selected file"
                            }
                        },
                        onProgress = { progress -> scope.launch { backupProgress = progress } },
                    )
                }
                result.onSuccess {
                    backupStatus = "Backup saved · ${it.files} files · ${architectureFormatBytes(it.bytes)}"
                }.onFailure {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    backupStatus = "Backup failed: ${it.message}"
                    backupProgress = null
                }
                backupBusy = false
            }
        }
    }
    val chooseBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }

    SettingsGroupTitle("Backup and restore")
    ArchitectureActionRow(
        title = "Save backup",
        subtitle = "Export chats, projects, settings, and account sessions",
        icon = Icons.Rounded.Backup,
        enabled = !backupBusy,
        onClick = {
            backupProgress = null
            showBackupPreflight = true
        },
    )
    ArchitectureDivider()
    ArchitectureActionRow(
        title = "Restore backup",
        subtitle = "Replace this device's workspace from a Pandora backup",
        icon = Icons.Rounded.Restore,
        enabled = !backupBusy,
        onClick = { chooseBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
    )
    if (backupStatus != null || backupBusy) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (backupBusy && backupProgress == null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(9.dp))
            }
            Text(backupStatus.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        backupProgress?.let { progress ->
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                architectureBackupProgressLabel(progress),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }

    Spacer(Modifier.height(26.dp))
    SettingsGroupTitle("Library")
    ArchitectureActionRow(
        title = "Archive",
        subtitle = "Hidden chats and projects",
        icon = Icons.Rounded.Archive,
        onClick = onArchive,
    )

    Spacer(Modifier.height(26.dp))
    SettingsGroupTitle("On-device storage")
    val storage = remember {
        StatFs(context.filesDir.path).let { stat ->
            stat.availableBytes to stat.totalBytes
        }
    }
    SettingsInfoPanel(
        icon = Icons.Rounded.Storage,
        title = "Internal app storage",
        summary = "${architectureFormatBytes(storage.first)} free of ${architectureFormatBytes(storage.second)}",
    )

    if (showBackupPreflight) {
        ImpactPreflightSheet(
            preflight = backupPreflight(activeChatCount, activeTerminalCount),
            onConfirm = {
                showBackupPreflight = false
                val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
                createBackup.launch("pandora-backup-$stamp.zip")
            },
            onDismiss = { showBackupPreflight = false },
        )
    }

    val selectedRestoreUri = restoreUri
    if (selectedRestoreUri != null) {
        ImpactPreflightSheet(
            preflight = restorePreflight(activeChatCount, activeTerminalCount),
            onConfirm = {
                restoreUri = null
                backupBusy = true
                backupStatus = "Restoring backup…"
                backupProgress = null
                scope.launch {
                    val result = runCatching {
                        workspaceCoordinator.restoreBackup {
                            checkNotNull(context.contentResolver.openInputStream(selectedRestoreUri)) {
                                "Could not open the selected backup"
                            }
                        }
                    }
                    result.onSuccess {
                        backupStatus = "Restore complete · ${it.files} entries · ${architectureFormatBytes(it.bytes)}"
                    }.onFailure {
                        backupStatus = "Restore failed: ${it.message}"
                    }
                    backupBusy = false
                }
            },
            onDismiss = { restoreUri = null },
        )
    }
}

@Composable
private fun AppearanceSettings(
    themePreference: AppSettings.ThemePreference,
    onThemePreferenceChanged: (AppSettings.ThemePreference) -> Unit,
) {
    SettingsGroupTitle("Theme")
    Text(
        "Follow your device or keep Pandora in a fixed light or dark appearance.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    Spacer(Modifier.height(14.dp))
    listOf(
        Triple(AppSettings.ThemePreference.SYSTEM, "System", Icons.Rounded.SettingsBrightness),
        Triple(AppSettings.ThemePreference.LIGHT, "Light", Icons.Rounded.LightMode),
        Triple(AppSettings.ThemePreference.DARK, "Dark", Icons.Rounded.DarkMode),
    ).forEachIndexed { index, (preference, title, icon) ->
        ProviderChoice(
            title = title,
            summary = when (preference) {
                AppSettings.ThemePreference.SYSTEM -> "Match the Android system setting"
                AppSettings.ThemePreference.LIGHT -> "Use light surfaces in every screen"
                AppSettings.ThemePreference.DARK -> "Use dark surfaces in every screen"
            },
            selected = themePreference == preference,
            enabled = true,
            icon = icon,
            onClick = { onThemePreferenceChanged(preference) },
        )
        if (index < 2) Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DiagnosticsSettings() {
    val context = LocalContext.current
    val linuxReady = remember { RootfsInstaller(context.applicationContext).isSetupComplete() }
    val codexInstalled = remember { File(context.filesDir, "linux-workspace/.local/bin/codex").exists() }
    SettingsGroupTitle("Runtime")
    SettingsStatusRow("Linux workspace", if (linuxReady) "Ready" else "Setup incomplete", linuxReady)
    ArchitectureDivider()
    SettingsStatusRow("Codex CLI", if (codexInstalled) "Installed" else "Installing or unavailable", codexInstalled)
    Spacer(Modifier.height(28.dp))
    SettingsGroupTitle("Build")
    SettingsInfoPanel(
        icon = Icons.Rounded.DeveloperMode,
        title = "Pandora ${BuildConfig.VERSION_NAME}",
        summary = "Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_DATE}",
    )
}

@Composable
private fun SettingsStatusRow(title: String, status: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SettingsInfoPanel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun ArchitectureActionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    enabled: Boolean = true,
    customIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = title, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                customIcon != null -> customIcon()
                icon != null -> Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ArchitectureDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 57.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun ArchitectureStepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp)
    }
}

private fun architectureFormatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

private fun architectureProviderHost(baseUrl: String): String {
    val parsedHost = runCatching { Uri.parse(baseUrl).host }.getOrNull()
    return parsedHost
        ?.removePrefix("www.")
        ?.takeIf(String::isNotBlank)
        ?: baseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .ifBlank { "saved provider" }
}

private fun architectureBackupProgressLabel(progress: WorkspaceBackup.Progress): String {
    val percent = (progress.fraction * 100).roundToInt()
    return if (progress.totalBytes > 0) {
        "$percent% · ${architectureFormatBytes(progress.completedBytes)} of ${architectureFormatBytes(progress.totalBytes)}"
    } else {
        "$percent% · ${progress.completedFiles} of ${progress.totalFiles} files"
    }
}
