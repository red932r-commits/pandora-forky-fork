package com.pandora.mobile

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pandora.mobile.linux.ChatAttachment
import com.pandora.mobile.linux.ChatAttachmentKind
import com.pandora.mobile.linux.RootfsInstaller
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ComposerContextKind(val label: String) {
    FILE("File"),
    CHANGES("Changes"),
    TERMINAL("Terminal"),
    ARTIFACT("Artifact"),
}

internal sealed interface ComposerContextSeed {
    val key: String
    val label: String
    val detail: String
    val kind: ComposerContextKind

    data class FileReference(
        val path: String,
        override val label: String = path.substringAfterLast('/'),
        override val detail: String = path.replaceFirst("/root", "~"),
        override val kind: ComposerContextKind = ComposerContextKind.FILE,
    ) : ComposerContextSeed {
        override val key: String = "file:$path"
    }

    data class Changes(
        val summary: ChangeSummary,
        override val label: String = "Current diff",
        override val detail: String = "${summary.files.size} changed ${if (summary.files.size == 1) "file" else "files"}",
    ) : ComposerContextSeed {
        override val key: String = "changes:${summary.repositoryRoot}"
        override val kind: ComposerContextKind = ComposerContextKind.CHANGES
    }

    data class TerminalText(
        val terminalName: String,
        val text: String,
        override val label: String = terminalName,
        override val detail: String = "Selected terminal output",
    ) : ComposerContextSeed {
        override val key: String = "terminal:$terminalName:${text.hashCode()}"
        override val kind: ComposerContextKind = ComposerContextKind.TERMINAL
    }
}

internal data class ComposerContextSuggestion(
    val seed: ComposerContextSeed,
    val keywords: String = "",
)

internal data class PreparedComposerContext(
    val seed: ComposerContextSeed,
    val attachment: ChatAttachment,
    val generated: Boolean,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val truncated: Boolean = false,
    val redactionCount: Int = 0,
)

internal data class ComposerContextIndex(
    val suggestions: List<ComposerContextSuggestion>,
    val unavailableSources: List<String> = emptyList(),
)

internal data class ContextHandoffRequest(
    val seed: ComposerContextSeed,
    val suggestedWorkingDirectory: String?,
    val copyText: String? = null,
)

internal fun contextMentionQuery(draft: String): String? {
    val marker = draft.lastIndexOf('@')
    if (marker < 0) return null
    if (marker > 0 && !draft[marker - 1].isWhitespace()) return null
    val query = draft.substring(marker + 1)
    if ('\n' in query || '\r' in query || query.any(Char::isWhitespace)) return null
    return query
}

internal fun removeContextMention(draft: String): String {
    val marker = draft.lastIndexOf('@')
    if (marker < 0) return draft
    return draft.substring(0, marker).trimEnd().let { if (it.isBlank()) "" else "$it " }
}

internal fun filterContextSuggestions(
    suggestions: List<ComposerContextSuggestion>,
    query: String,
): List<ComposerContextSuggestion> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isBlank()) {
        val byKind = suggestions.groupBy { it.seed.kind }
        val prioritized = buildList {
            addAll(byKind[ComposerContextKind.FILE].orEmpty().sortedBy { it.seed.label.lowercase() }.take(3))
            addAll(byKind[ComposerContextKind.CHANGES].orEmpty().sortedBy { it.seed.label.lowercase() }.take(1))
            addAll(byKind[ComposerContextKind.TERMINAL].orEmpty().sortedBy { it.seed.label.lowercase() }.take(2))
            addAll(byKind[ComposerContextKind.ARTIFACT].orEmpty().sortedBy { it.seed.label.lowercase() }.take(2))
        }
        return (prioritized + suggestions.sortedBy { it.seed.label.lowercase() })
            .distinctBy { it.seed.key }
            .take(MAX_CONTEXT_SUGGESTIONS)
    }
    return suggestions.asSequence()
        .mapNotNull { suggestion ->
            val title = suggestion.seed.label.lowercase(Locale.ROOT)
            val searchable = "$title ${suggestion.seed.detail.lowercase(Locale.ROOT)} ${suggestion.keywords.lowercase(Locale.ROOT)}"
            if (normalized !in searchable) return@mapNotNull null
            val rank = when {
                title == normalized -> 0
                title.startsWith(normalized) -> 10
                else -> 20
            }
            rank to suggestion
        }
        .sortedWith(compareBy<Pair<Int, ComposerContextSuggestion>> { it.first }.thenBy { it.second.seed.label.lowercase() })
        .map { it.second }
        .take(MAX_CONTEXT_SUGGESTIONS)
        .toList()
}

internal fun redactTerminalContext(text: String, maxLines: Int = MAX_TERMINAL_CONTEXT_LINES): String {
    var redacted = text.lineSequence().takeLastCompat(maxLines).joinToString("\n").take(MAX_CONTEXT_CHARACTERS)
    SECRET_ASSIGNMENT_PATTERNS.forEach { pattern ->
        redacted = pattern.replace(redacted) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" }
    }
    redacted = BEARER_PATTERN.replace(redacted) { "${it.groupValues[1]}[REDACTED]" }
    redacted = PRIVATE_KEY_PATTERN.replace(redacted, "-----BEGIN PRIVATE KEY-----\n[REDACTED]\n-----END PRIVATE KEY-----")
    return redacted.trim()
}

internal fun countContextRedactions(content: String): Int {
    var count = 0
    var offset = 0
    while (true) {
        val match = content.indexOf(REDACTED_MARKER, startIndex = offset)
        if (match < 0) return count
        count += 1
        offset = match + REDACTED_MARKER.length
    }
}

private fun Sequence<String>.takeLastCompat(count: Int): List<String> {
    val queue = ArrayDeque<String>(count)
    forEach { line ->
        if (queue.size == count) queue.removeFirst()
        queue.addLast(line)
    }
    return queue.toList()
}

internal class ComposerContextRepository(context: Context) {
    private val appContext = context.applicationContext
    private val installer = RootfsInstaller(appContext)

    fun load(workingDirectory: String): ComposerContextIndex {
        val suggestions = mutableListOf<ComposerContextSuggestion>()
        val unavailableSources = mutableListOf<String>()
        runCatching {
            val projectDirectory = resolveWorkspacePath(workingDirectory)
            if (projectDirectory?.isDirectory == true) {
                projectDirectory.walkTopDown()
                    .onEnter { directory -> directory == projectDirectory || directory.name !in IGNORED_DIRECTORIES }
                    .filter(File::isFile)
                    .take(MAX_CONTEXT_FILES)
                    .forEach { file ->
                        val relative = file.relativeTo(projectDirectory).invariantSeparatorsPath
                        val path = "${workingDirectory.trimEnd('/')}/$relative"
                        val artifact = file.extension.lowercase(Locale.ROOT) in ARTIFACT_EXTENSIONS
                        suggestions += ComposerContextSuggestion(
                            seed = ComposerContextSeed.FileReference(
                                path = path,
                                label = file.name,
                                detail = relative,
                                kind = if (artifact) ComposerContextKind.ARTIFACT else ComposerContextKind.FILE,
                            ),
                            keywords = relative,
                        )
                    }
                boundedArtifacts(projectDirectory).forEach { file ->
                    val relative = file.relativeTo(projectDirectory).invariantSeparatorsPath
                    val path = "${workingDirectory.trimEnd('/')}/$relative"
                    suggestions += ComposerContextSuggestion(
                        seed = ComposerContextSeed.FileReference(
                            path = path,
                            label = file.name,
                            detail = relative,
                            kind = ComposerContextKind.ARTIFACT,
                        ),
                        keywords = "build output package binary $relative",
                    )
                }
            }
        }.onFailure {
            unavailableSources += "Files and artifacts"
        }
        when (val changes = ChangesRepository(appContext).load(workingDirectory)) {
            is ChangesLoadState.Ready -> if (changes.summary.files.isNotEmpty()) {
                suggestions += ComposerContextSuggestion(
                    seed = ComposerContextSeed.Changes(changes.summary),
                    keywords = changes.summary.files.joinToString(" ", transform = ChangedFile::path),
                )
            }
            is ChangesLoadState.Unavailable -> if (!changes.detail.contains("not attached to a Git repository", ignoreCase = true)) {
                unavailableSources += "Changes"
            }
            ChangesLoadState.Loading -> Unit
        }
        return ComposerContextIndex(
            suggestions = suggestions.distinctBy { it.seed.key },
            unavailableSources = unavailableSources,
        )
    }

    private fun boundedArtifacts(projectDirectory: File): List<File> {
        val pending = ArrayDeque<File>().apply { add(projectDirectory) }
        val artifacts = mutableListOf<File>()
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_ARTIFACT_SCAN_ENTRIES && artifacts.size < MAX_CONTEXT_ARTIFACTS) {
            val directory = pending.removeFirst()
            directory.listFiles().orEmpty().forEach { child ->
                if (visited++ >= MAX_ARTIFACT_SCAN_ENTRIES) return@forEach
                when {
                    child.isDirectory && child.name !in ARTIFACT_IGNORED_DIRECTORIES -> pending.addLast(child)
                    child.isFile && child.extension.lowercase(Locale.ROOT) in ARTIFACT_EXTENSIONS -> artifacts += child
                }
            }
        }
        return artifacts
    }

    private fun resolveWorkspacePath(containerPath: String): File? {
        val relative = containerPath.removePrefix("/root/")
        if (relative == containerPath) return null
        val workspace = installer.workspace.canonicalFile
        val candidate = File(workspace, relative).canonicalFile
        return candidate.takeIf { it.path == workspace.path || it.path.startsWith(workspace.path + File.separator) }
    }

    private companion object {
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", "build", "node_modules", "vendor", "dist")
        val ARTIFACT_IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", "node_modules", "vendor")
        val ARTIFACT_EXTENSIONS = setOf("apk", "aab", "aar", "jar", "zip", "tar", "tgz", "gz", "deb", "rpm", "so")
    }
}

internal object ComposerContextStore {
    fun prepare(context: Context, sessionId: String, seed: ComposerContextSeed): PreparedComposerContext = when (seed) {
        is ComposerContextSeed.FileReference -> PreparedComposerContext(
            seed = seed,
            attachment = ChatAttachment(
                kind = ChatAttachmentKind.FILE,
                name = seed.label,
                containerPath = seed.path,
                mimeType = contextMimeType(seed.label),
            ),
            generated = false,
        )
        is ComposerContextSeed.Changes -> {
            val repository = ChangesRepository(context)
            val fullContent = buildString {
                appendLine("Repository: ${seed.summary.repositoryRoot}")
                appendLine("Files: ${seed.summary.files.size}; additions: ${seed.summary.additions}; deletions: ${seed.summary.deletions}")
                seed.summary.files.forEach { file ->
                    appendLine()
                    appendLine("### ${file.kind.name.lowercase()} ${file.path}")
                    repository.loadDiff(seed.summary.repositoryRoot, file).forEach { line ->
                        appendLine(
                            when (line.kind) {
                                DiffLineKind.ADDITION -> "+${line.text}"
                                DiffLineKind.DELETION -> "-${line.text}"
                                else -> line.text
                            },
                        )
                    }
                }
            }
            generatedContext(
                context = context,
                sessionId = sessionId,
                seed = seed,
                name = "current-diff.patch",
                content = fullContent.take(MAX_CONTEXT_CHARACTERS),
                truncated = fullContent.length > MAX_CONTEXT_CHARACTERS,
            )
        }
        is ComposerContextSeed.TerminalText -> {
            val content = redactTerminalContext(seed.text)
            generatedContext(
                context = context,
                sessionId = sessionId,
                seed = seed,
                name = "${safeFileStem(seed.terminalName)}-terminal.txt",
                content = content,
                truncated = seed.text.lineSequence().count() > MAX_TERMINAL_CONTEXT_LINES ||
                    seed.text.length > MAX_CONTEXT_CHARACTERS,
                redactionCount = countContextRedactions(content),
            )
        }
    }

    fun deletePending(context: Context, prepared: PreparedComposerContext) {
        if (!prepared.generated) return
        ChatAttachmentStore.deletePending(context, prepared.attachment)
    }

    private fun generatedContext(
        context: Context,
        sessionId: String,
        seed: ComposerContextSeed,
        name: String,
        content: String,
        truncated: Boolean = false,
        redactionCount: Int = 0,
    ): PreparedComposerContext {
        val safeSession = safeFileStem(sessionId)
        val directory = File(context.filesDir, "linux-workspace/.pandora/context/$safeSession")
        check(directory.mkdirs() || directory.isDirectory) { "Could not prepare context storage" }
        val file = File(directory, "${UUID.randomUUID()}-$name")
        file.writeText(content)
        return PreparedComposerContext(
            seed = seed,
            attachment = ChatAttachment(
                kind = ChatAttachmentKind.FILE,
                name = name,
                containerPath = "/root/.pandora/context/$safeSession/${file.name}",
                mimeType = "text/plain",
                sizeBytes = file.length(),
            ),
            generated = true,
            truncated = truncated,
            redactionCount = redactionCount,
        )
    }

    private fun safeFileStem(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
}

@Composable
internal fun ComposerContextPalette(
    suggestions: List<ComposerContextSuggestion>,
    loading: Boolean,
    unavailableSources: List<String>,
    onRetry: () -> Unit,
    onSelect: (ComposerContextSuggestion) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(18.dp))
            .padding(vertical = 5.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Add workspace context", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(48.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, contentDescription = "Close context picker", modifier = Modifier.size(18.dp))
            }
        }
        if (loading) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Indexing workspace context…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        } else if (unavailableSources.isNotEmpty()) {
            ContextIndexWarning(unavailableSources, onRetry)
        }
        if (!loading && suggestions.isEmpty()) {
            Text(
                if (unavailableSources.isEmpty()) {
                    "No matching files, changes, terminals, or artifacts."
                } else {
                    "No context is available from the remaining sources."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        } else {
            suggestions.forEach { suggestion ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clickable(role = Role.Button, onClickLabel = "Add ${suggestion.seed.label}") { onSelect(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(contextIcon(suggestion.seed.kind), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(suggestion.seed.label, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(suggestion.seed.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(suggestion.seed.kind.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
internal fun PreparedContextChip(
    context: PreparedComposerContext,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Review ${context.seed.label} context", onClick = onPreview)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(contextIcon(context.seed.kind), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            context.seed.label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 12.sp,
            fontFamily = if (context.seed.kind == ComposerContextKind.FILE || context.seed.kind == ComposerContextKind.ARTIFACT) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.size(48.dp).clickable(onClickLabel = "Remove ${context.seed.label}", onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ContextIndexWarning(unavailableSources: List<String>, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text("Some context is unavailable", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                unavailableSources.joinToString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(48.dp).clickable(onClickLabel = "Retry context indexing", onClick = onRetry), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        }
    }
}

private data class ContextPreviewContent(
    val text: String?,
    val sizeBytes: Long?,
    val previewLimited: Boolean,
)

@Composable
internal fun PreparedContextPreviewDialog(context: PreparedComposerContext, onDismiss: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    var preview by remember(context.attachment.containerPath) { mutableStateOf<ContextPreviewContent?>(null) }
    LaunchedEffect(context.attachment.containerPath) {
        preview = withContext(Dispatchers.IO) {
            val resolved = ChatAttachmentStore.resolve(appContext, context.attachment)
            ContextPreviewContent(
                text = ChatAttachmentStore.readTextPreview(
                    appContext,
                    context.attachment,
                    maxCharacters = MAX_CONTEXT_PREVIEW_CHARACTERS,
                ),
                sizeBytes = resolved?.length() ?: context.attachment.sizeBytes,
                previewLimited = resolved?.length()?.let { it > MAX_CONTEXT_PREVIEW_CHARACTERS } == true,
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Review ${context.seed.kind.label.lowercase()} context", fontWeight = FontWeight.SemiBold)
                Text(
                    context.seed.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            val loaded = preview
            Column {
                ContextPreviewFact("Source", context.seed.detail)
                ContextPreviewFact(
                    "Captured",
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(context.capturedAtMillis)),
                )
                loaded?.let { content ->
                    val lineCount = content.text?.lineSequence()?.count()
                    val approximateTokens = content.text?.length?.let { (it + 3) / 4 }
                    ContextPreviewFact(
                        "Payload",
                        buildList {
                            lineCount?.let { add("$it ${if (it == 1) "line" else "lines"}") }
                            approximateTokens?.let { add("~${formatContextCount(it)} tokens") }
                            content.sizeBytes?.let { add(formatContextBytes(it)) }
                        }.joinToString(" · ").ifBlank { "Attachment metadata" },
                    )
                }
                if (context.redactionCount > 0) {
                    ContextPreviewFact("Redaction", "${context.redactionCount} secret ${if (context.redactionCount == 1) "value" else "values"} removed")
                }
                if (context.truncated || preview?.previewLimited == true) {
                    ContextPreviewFact("Limit", "Preview or generated context was truncated")
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    when {
                        preview == null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        preview?.text != null -> Text(
                            preview?.text.orEmpty(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                        else -> Text(
                            "Text preview is unavailable. The file will be attached from ${context.attachment.containerPath}.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ContextPreviewFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextHandoffSheet(
    request: ContextHandoffRequest,
    currentChatAvailable: Boolean,
    onDismiss: () -> Unit,
    onAddToCurrentChat: () -> Unit,
    onStartNewChat: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Send ${request.seed.kind.label.lowercase()} context", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "${request.seed.label} · review before sending from the composer",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            if (currentChatAvailable) {
                HandoffAction(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    title = "Add to current chat",
                    detail = "Keep the existing conversation and draft",
                    onClick = onAddToCurrentChat,
                )
                Spacer(Modifier.height(9.dp))
            }
            HandoffAction(
                icon = Icons.Rounded.Add,
                title = "Start a new workspace chat",
                detail = request.suggestedWorkingDirectory?.replaceFirst("/root", "~") ?: "Use a new general workspace",
                onClick = onStartNewChat,
            )
            if (request.copyText != null) {
                Spacer(Modifier.height(9.dp))
                HandoffAction(
                    icon = Icons.Rounded.ContentCopy,
                    title = "Copy selection",
                    detail = "Copy the original terminal text",
                    onClick = {
                        clipboard.setText(AnnotatedString(request.copyText))
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun HandoffAction(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun contextIcon(kind: ComposerContextKind): ImageVector = when (kind) {
    ComposerContextKind.FILE -> Icons.AutoMirrored.Rounded.InsertDriveFile
    ComposerContextKind.CHANGES -> Icons.Rounded.Code
    ComposerContextKind.TERMINAL -> Icons.Rounded.Terminal
    ComposerContextKind.ARTIFACT -> Icons.Rounded.Inventory2
}

private fun contextMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "json" -> "application/json"
    "pdf" -> "application/pdf"
    else -> "text/plain"
}

private fun formatContextCount(value: Int): String = if (value >= 1_000) {
    String.format(Locale.US, "%.1fk", value / 1_000.0)
} else {
    value.toString()
}

private fun formatContextBytes(value: Long): String = when {
    value >= 1_048_576 -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
    value >= 1_024 -> String.format(Locale.US, "%.1f KB", value / 1_024.0)
    else -> "$value B"
}

private const val MAX_CONTEXT_FILES = 600
private const val MAX_CONTEXT_ARTIFACTS = 40
private const val MAX_ARTIFACT_SCAN_ENTRIES = 5_000
private const val MAX_CONTEXT_SUGGESTIONS = 8
private const val MAX_CONTEXT_CHARACTERS = 500_000
private const val MAX_CONTEXT_PREVIEW_CHARACTERS = 500_000
private const val MAX_TERMINAL_CONTEXT_LINES = 120
private const val REDACTED_MARKER = "[REDACTED]"
private val SECRET_ASSIGNMENT_PATTERNS = listOf(
    Regex("(?im)\\b(api[_-]?key|access[_-]?token|auth[_-]?token|password|passwd|secret)(\\s*[:=]\\s*)[^\\s]+"),
)
private val BEARER_PATTERN = Regex("(?i)\\b(Bearer\\s+)[A-Za-z0-9._~+/-]+=*")
private val PRIVATE_KEY_PATTERN = Regex("(?s)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
