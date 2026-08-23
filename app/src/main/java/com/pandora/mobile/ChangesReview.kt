package com.pandora.mobile

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pandora.mobile.linux.RootfsInstaller
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ChangeKind { MODIFIED, ADDED, DELETED, RENAMED }

data class ChangedFile(
    val path: String,
    val kind: ChangeKind,
    val additions: Int = 0,
    val deletions: Int = 0,
)

data class ChangeSummary(
    val repositoryRoot: String,
    val files: List<ChangedFile>,
) {
    val additions: Int get() = files.sumOf(ChangedFile::additions)
    val deletions: Int get() = files.sumOf(ChangedFile::deletions)
}

enum class DiffLineKind { HEADER, HUNK, CONTEXT, ADDITION, DELETION, META }

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

sealed interface ChangesLoadState {
    data object Loading : ChangesLoadState
    data class Ready(val summary: ChangeSummary) : ChangesLoadState
    data class Unavailable(val detail: String) : ChangesLoadState
}

class ChangesRepository(context: Context) {
    private val installer = RootfsInstaller(context.applicationContext)

    fun load(workingDirectory: String): ChangesLoadState {
        if (!installer.isSetupComplete()) {
            return ChangesLoadState.Unavailable("Pandora Linux is not ready yet.")
        }
        val rootResult = runGit(workingDirectory, "rev-parse", "--show-toplevel")
        val root = rootResult.output.lineSequence().firstOrNull()?.trim().orEmpty()
        if (rootResult.exitCode != 0 || root.isBlank() || !root.startsWith("/root")) {
            return ChangesLoadState.Unavailable("This chat is not attached to a Git repository.")
        }
        val status = runGit(root, "-c", "core.quotepath=false", "status", "--porcelain=v1", "--untracked-files=all")
        if (status.exitCode != 0) {
            return ChangesLoadState.Unavailable(status.output.ifBlank { "Git status is unavailable." })
        }
        val stats = runGit(root, "-c", "core.quotepath=false", "diff", "--numstat", "HEAD", "--")
        return ChangesLoadState.Ready(
            ChangeSummary(
                repositoryRoot = root,
                files = parseChangedFiles(status.output, stats.output),
            ),
        )
    }

    fun loadDiff(repositoryRoot: String, file: ChangedFile): List<DiffLine> {
        val result = if (file.kind == ChangeKind.ADDED) {
            val tracked = runGit(repositoryRoot, "ls-files", "--error-unmatch", "--", file.path)
            if (tracked.exitCode == 0) {
                runGit(repositoryRoot, "diff", "--no-ext-diff", "--unified=3", "HEAD", "--", file.path)
            } else {
                runGit(repositoryRoot, "diff", "--no-index", "--unified=3", "--", "/dev/null", file.path)
            }
        } else {
            runGit(repositoryRoot, "diff", "--no-ext-diff", "--unified=3", "HEAD", "--", file.path)
        }
        return parseUnifiedDiff(result.output)
    }

    private fun runGit(workingDirectory: String, vararg args: String): LinuxCommandResult {
        val command = installer.containerCommandAt(workingDirectory, "/usr/bin/git", *args)
        val process = runCatching { installer.startContainerProcess(command, mergeError = true) }
            .getOrElse { return LinuxCommandResult(-1, it.message ?: "Could not start Git") }
        val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            .getOrDefault("")
            .take(MAX_GIT_OUTPUT_CHARS)
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            installer.terminateProcessTree(process)
            return LinuxCommandResult(-1, "Git command timed out")
        }
        return LinuxCommandResult(process.exitValue(), output)
    }

    private data class LinuxCommandResult(val exitCode: Int, val output: String)

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 20L
        const val MAX_GIT_OUTPUT_CHARS = 1_000_000
    }
}

fun parseChangedFiles(statusOutput: String, numstatOutput: String): List<ChangedFile> {
    val stats = numstatOutput.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val additions = parts[0].toIntOrNull() ?: 0
        val deletions = parts[1].toIntOrNull() ?: 0
        val path = parts.drop(2).joinToString("\t")
        path to (additions to deletions)
    }.toMap()
    return statusOutput.lineSequence().mapNotNull { line ->
        if (line.length < 4) return@mapNotNull null
        val code = line.take(2)
        val rawPath = line.drop(3)
        val path = rawPath.substringAfter(" -> ")
        val kind = when {
            code == "??" || 'A' in code -> ChangeKind.ADDED
            'D' in code -> ChangeKind.DELETED
            'R' in code -> ChangeKind.RENAMED
            else -> ChangeKind.MODIFIED
        }
        val (additions, deletions) = stats[path] ?: (0 to 0)
        ChangedFile(path = path, kind = kind, additions = additions, deletions = deletions)
    }.sortedWith(compareBy<ChangedFile>({ it.kind.ordinal }, ChangedFile::path)).toList()
}

fun parseUnifiedDiff(diff: String): List<DiffLine> {
    var oldLine = 0
    var newLine = 0
    var inHunk = false
    val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
    return diff.lineSequence().map { line ->
        val match = hunk.find(line)
        when {
            match != null -> {
                oldLine = match.groupValues[1].toInt()
                newLine = match.groupValues[2].toInt()
                inHunk = true
                DiffLine(DiffLineKind.HUNK, line)
            }
            !inHunk && (line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("---") || line.startsWith("+++")) -> {
                DiffLine(DiffLineKind.HEADER, line)
            }
            inHunk && line.startsWith("+") -> DiffLine(DiffLineKind.ADDITION, line.drop(1), newLine = newLine++)
            inHunk && line.startsWith("-") -> DiffLine(DiffLineKind.DELETION, line.drop(1), oldLine = oldLine++)
            inHunk && line.startsWith(" ") -> DiffLine(
                DiffLineKind.CONTEXT,
                line.drop(1),
                oldLine = oldLine++,
                newLine = newLine++,
            )
            line.startsWith("\\") -> DiffLine(DiffLineKind.META, line)
            else -> DiffLine(DiffLineKind.HEADER, line)
        }
    }.toList()
}

@Composable
fun ChangesReviewScreen(
    workingDirectory: String,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context) { ChangesRepository(context) }
    var loadGeneration by remember { mutableStateOf(0) }
    var state by remember(workingDirectory) { mutableStateOf<ChangesLoadState>(ChangesLoadState.Loading) }
    var selectedFile by remember { mutableStateOf<ChangedFile?>(null) }
    LaunchedEffect(workingDirectory, loadGeneration) {
        state = ChangesLoadState.Loading
        state = withContext(Dispatchers.IO) { repository.load(workingDirectory) }
    }
    val ready = state as? ChangesLoadState.Ready
    val file = selectedFile
    if (file != null && ready != null) {
        DiffViewer(
            repository = repository,
            summary = ready.summary,
            file = file,
            onSelectFile = { selectedFile = it },
            onBack = { selectedFile = null },
        )
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        ChangesHeader("Changes", onBack)
        when (val current = state) {
            ChangesLoadState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ChangesLoadState.Unavailable -> ChangesUnavailable(
                detail = current.detail,
                onRefresh = { loadGeneration++ },
                modifier = Modifier.weight(1f),
            )
            is ChangesLoadState.Ready -> ChangesList(
                summary = current.summary,
                onOpenFile = { selectedFile = it },
                onRefresh = { loadGeneration++ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChangesHeader(title: String, onBack: () -> Unit, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable(
                role = Role.Button,
                onClickLabel = "Back",
                onClick = onBack,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ChangesList(
    summary: ChangeSummary,
    onOpenFile: (ChangedFile) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf<ChangeKind?>(null) }
    val filtered = summary.files.filter { filter == null || it.kind == filter }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChangeMetric("${summary.files.size} files", Icons.Rounded.Description, MaterialTheme.colorScheme.onBackground)
                ChangeMetric("+${summary.additions}", Icons.Rounded.Add, MaterialTheme.colorScheme.secondary)
                ChangeMetric("−${summary.deletions}", Icons.Rounded.Remove, MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChangeFilter("All", filter == null) { filter = null }
                ChangeFilter("Modified", filter == ChangeKind.MODIFIED) { filter = ChangeKind.MODIFIED }
                ChangeFilter("Added", filter == ChangeKind.ADDED) { filter = ChangeKind.ADDED }
                ChangeFilter("Deleted", filter == ChangeKind.DELETED) { filter = ChangeKind.DELETED }
            }
            Spacer(Modifier.height(20.dp))
            if (summary.files.isEmpty()) {
                ChangesEmpty(onRefresh)
            }
        }
        if (filtered.isNotEmpty()) {
            items(filtered, key = ChangedFile::path) { file ->
                ChangedFileRow(file, onClick = { onOpenFile(file) })
                Spacer(Modifier.height(9.dp))
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ChangeMetric(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChangeFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
    }
}

@Composable
private fun ChangedFileRow(file: ChangedFile, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(15.dp))
            .clickable(role = Role.Button, onClickLabel = "Review ${file.path}", onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.path,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(changeKindLabel(file.kind), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                if (file.additions > 0) Text("  +${file.additions}", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                if (file.deletions > 0) Text("  −${file.deletions}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun changeKindLabel(kind: ChangeKind): String = when (kind) {
    ChangeKind.MODIFIED -> "Modified"
    ChangeKind.ADDED -> "Added"
    ChangeKind.DELETED -> "Deleted"
    ChangeKind.RENAMED -> "Renamed"
}

@Composable
private fun ChangesEmpty(onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(12.dp))
        Text("Working tree clean", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text("Git reports no uncommitted files.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        RefreshAction(onRefresh)
    }
}

@Composable
private fun ChangesUnavailable(detail: String, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFD18B27), modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text("Changes unavailable", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp))
        RefreshAction(onRefresh)
    }
}

@Composable
private fun RefreshAction(onRefresh: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onRefresh).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text("Refresh changes", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DiffViewer(
    repository: ChangesRepository,
    summary: ChangeSummary,
    file: ChangedFile,
    onSelectFile: (ChangedFile) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var diff by remember(file.path) { mutableStateOf<List<DiffLine>?>(null) }
    LaunchedEffect(summary.repositoryRoot, file.path) {
        diff = withContext(Dispatchers.IO) { repository.loadDiff(summary.repositoryRoot, file) }
    }
    val index = summary.files.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        ChangesHeader(file.path.substringAfterLast('/'), onBack, "${index + 1} of ${summary.files.size} · read-only")
        val lines = diff
        if (lines == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No text diff is available for this file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val horizontal = rememberScrollState()
            val vertical = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(horizontal)
                    .verticalScroll(vertical)
                    .widthIn(min = 520.dp),
            ) {
                lines.forEach { line -> DiffLineRow(line) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DiffNavigateButton(
                label = "Previous file",
                enabled = index > 0,
                modifier = Modifier.weight(1f),
            ) { onSelectFile(summary.files[index - 1]) }
            DiffNavigateButton(
                label = "Next file",
                enabled = index < summary.files.lastIndex,
                forward = true,
                modifier = Modifier.weight(1f),
            ) { onSelectFile(summary.files[index + 1]) }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val background = when (line.kind) {
        DiffLineKind.ADDITION -> MaterialTheme.colorScheme.secondaryContainer
        DiffLineKind.DELETION -> MaterialTheme.colorScheme.errorContainer
        DiffLineKind.HUNK -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val foreground = when (line.kind) {
        DiffLineKind.ADDITION -> MaterialTheme.colorScheme.onSecondaryContainer
        DiffLineKind.DELETION -> MaterialTheme.colorScheme.onErrorContainer
        DiffLineKind.HUNK -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onBackground
    }
    Row(
        Modifier.fillMaxWidth().background(background).padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(line.oldLine?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(38.dp))
        Text(line.newLine?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(38.dp))
        Text(
            when (line.kind) {
                DiffLineKind.ADDITION -> "+"
                DiffLineKind.DELETION -> "−"
                else -> " "
            },
            color = foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(22.dp),
        )
        Text(line.text.ifEmpty { " " }, color = foreground, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, softWrap = false)
    }
}

@Composable
private fun DiffNavigateButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    forward: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (!forward) Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, modifier = Modifier.size(17.dp))
        if (!forward) Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        if (forward) Spacer(Modifier.width(6.dp))
        if (forward) Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(17.dp))
    }
}

@Composable
fun ChangesReadyCard(summary: ChangeSummary, onReview: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f), RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .34f), RoundedCornerShape(16.dp))
            .padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Code, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Changes ready to review", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Read-only Git diff", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${summary.files.size} files changed · +${summary.additions} −${summary.deletions}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        summary.files.take(3).forEach { file ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(file.path, color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (file.additions > 0) Text("+${file.additions}", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                if (file.deletions > 0) Text(" −${file.deletions}", color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(13.dp))
                .clickable(role = Role.Button, onClickLabel = "Review changes", onClick = onReview),
            contentAlignment = Alignment.Center,
        ) {
            Text("Review changes", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
