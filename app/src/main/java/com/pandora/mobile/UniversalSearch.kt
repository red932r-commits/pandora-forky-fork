package com.pandora.mobile

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pandora.mobile.linux.ArchiveCatalog
import com.pandora.mobile.linux.CodexThreadCatalog
import com.pandora.mobile.linux.ProjectCatalog
import com.pandora.mobile.linux.RootfsInstaller
import com.pandora.mobile.linux.ZmxSessionCatalog
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class UniversalSearchKind(val label: String) {
    PROJECT("Projects"),
    CHAT("Chats"),
    FILE("Files"),
    COMMAND("Commands"),
    TERMINAL("Terminals"),
    SETTING("Settings"),
    ARCHIVE("Archive"),
}

internal sealed interface UniversalSearchTarget {
    data class Project(val path: String) : UniversalSearchTarget
    data class Chat(val threadId: String, val cwd: String) : UniversalSearchTarget
    data class File(val path: String, val projectPath: String) : UniversalSearchTarget
    data class Command(val usage: String) : UniversalSearchTarget
    data class Terminal(val persistentName: String) : UniversalSearchTarget
    data class Setting(val destination: String) : UniversalSearchTarget
    data object Archive : UniversalSearchTarget
}

internal data class UniversalSearchItem(
    val id: String,
    val kind: UniversalSearchKind,
    val title: String,
    val subtitle: String,
    val keywords: String = "",
    val target: UniversalSearchTarget,
)

internal enum class UniversalSearchFilter(val label: String) {
    ALL("All"),
    PROJECTS("Projects"),
    CHATS("Chats"),
    FILES("Files"),
    COMMANDS("Commands"),
}

internal fun filterUniversalSearch(
    items: List<UniversalSearchItem>,
    query: String,
    filter: UniversalSearchFilter,
): List<UniversalSearchItem> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    val words = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
    return items.asSequence()
        .filter { item ->
            when (filter) {
                UniversalSearchFilter.ALL -> true
                UniversalSearchFilter.PROJECTS -> item.kind == UniversalSearchKind.PROJECT
                UniversalSearchFilter.CHATS -> item.kind == UniversalSearchKind.CHAT
                UniversalSearchFilter.FILES -> item.kind == UniversalSearchKind.FILE
                UniversalSearchFilter.COMMANDS -> item.kind == UniversalSearchKind.COMMAND
            }
        }
        .mapNotNull { item ->
            val title = item.title.lowercase(Locale.ROOT)
            val subtitle = item.subtitle.lowercase(Locale.ROOT)
            val haystack = "$title $subtitle ${item.keywords.lowercase(Locale.ROOT)}"
            if (words.any { it !in haystack }) return@mapNotNull null
            val score = when {
                normalized.isBlank() -> kindDefaultRank(item.kind)
                title == normalized -> 0
                title.startsWith(normalized) -> 10
                title.contains(normalized) -> 20
                subtitle.contains(normalized) -> 30
                else -> 40
            }
            score to item
        }
        .sortedWith(compareBy<Pair<Int, UniversalSearchItem>> { it.first }.thenBy { it.second.title.lowercase() })
        .map { it.second }
        .take(MAX_VISIBLE_RESULTS)
        .toList()
}

private fun kindDefaultRank(kind: UniversalSearchKind): Int = when (kind) {
    UniversalSearchKind.PROJECT -> 0
    UniversalSearchKind.CHAT -> 10
    UniversalSearchKind.COMMAND -> 20
    UniversalSearchKind.TERMINAL -> 30
    UniversalSearchKind.SETTING -> 40
    UniversalSearchKind.ARCHIVE -> 50
    UniversalSearchKind.FILE -> 60
}

internal class UniversalSearchRepository(context: Context) {
    private val appContext = context.applicationContext
    private val projectCatalog = ProjectCatalog(appContext)
    private val chatCatalog = CodexThreadCatalog(appContext)
    private val archiveCatalog = ArchiveCatalog(appContext)
    private val terminalCatalog = ZmxSessionCatalog(appContext)
    private val installer = RootfsInstaller(appContext)

    fun load(): List<UniversalSearchItem> {
        val archive = runCatching(archiveCatalog::read).getOrDefault(com.pandora.mobile.linux.ArchivedWorkspace())
        val projects = (
            runCatching(projectCatalog::readRegistered).getOrDefault(emptyList()) +
                runCatching(projectCatalog::discoverFolders).getOrDefault(emptyList())
        ).distinctBy { it.path }
        val chats = runCatching(chatCatalog::readCached).getOrDefault(emptyList())
        val terminals = runCatching(terminalCatalog::read).getOrDefault(emptyList())

        return buildList {
            projects.forEach { project ->
                add(
                    UniversalSearchItem(
                        id = "project:${project.path}",
                        kind = UniversalSearchKind.PROJECT,
                        title = project.name,
                        subtitle = if (project.path in archive.projectPaths) "${project.displayPath} · Archived" else project.displayPath,
                        keywords = "repository workspace folder",
                        target = if (project.path in archive.projectPaths) {
                            UniversalSearchTarget.Archive
                        } else {
                            UniversalSearchTarget.Project(project.path)
                        },
                    ),
                )
            }
            chats.forEach { chat ->
                add(
                    UniversalSearchItem(
                        id = "chat:${chat.id}",
                        kind = UniversalSearchKind.CHAT,
                        title = chat.title,
                        subtitle = buildString {
                            append(chat.cwd.replaceFirst("/root", "~"))
                            if (chat.id in archive.chatIds || chat.cwd in archive.projectPaths) append(" · Archived")
                            chat.preview.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotBlank)?.let {
                                append(" · ")
                                append(it.take(90))
                            }
                        },
                        keywords = chat.preview,
                        target = if (chat.id in archive.chatIds || chat.cwd in archive.projectPaths) {
                            UniversalSearchTarget.Archive
                        } else {
                            UniversalSearchTarget.Chat(chat.id, chat.cwd)
                        },
                    ),
                )
            }
            supportedSlashCommands.forEach { command ->
                add(
                    UniversalSearchItem(
                        id = "command:${command.name}",
                        kind = UniversalSearchKind.COMMAND,
                        title = command.usage,
                        subtitle = command.description,
                        keywords = "slash action ${command.name}",
                        target = UniversalSearchTarget.Command(command.usage),
                    ),
                )
            }
            terminals.forEach { name ->
                add(
                    UniversalSearchItem(
                        id = "terminal:$name",
                        kind = UniversalSearchKind.TERMINAL,
                        title = name.removePrefix("pandora-").let { "Terminal $it" },
                        subtitle = "$name · Persistent session",
                        keywords = "shell linux zmx",
                        target = UniversalSearchTarget.Terminal(name),
                    ),
                )
            }
            SETTINGS.forEach { (title, description) ->
                add(
                    UniversalSearchItem(
                        id = "setting:$title",
                        kind = UniversalSearchKind.SETTING,
                        title = title,
                        subtitle = description,
                        keywords = "preferences configuration",
                        target = UniversalSearchTarget.Setting(title),
                    ),
                )
            }
            add(
                UniversalSearchItem(
                    id = "archive",
                    kind = UniversalSearchKind.ARCHIVE,
                    title = "Archive",
                    subtitle = "Archived projects and chats",
                    keywords = "restore hidden removed",
                    target = UniversalSearchTarget.Archive,
                ),
            )
            addAll(indexFiles(projects.map { it.path }))
        }
    }

    private fun indexFiles(projectPaths: List<String>): List<UniversalSearchItem> {
        if (!installer.workspace.isDirectory) return emptyList()
        val results = mutableListOf<UniversalSearchItem>()
        projectPaths.forEach { projectPath ->
            if (results.size >= MAX_INDEXED_FILES) return@forEach
            val relativeProjectPath = projectPath.removePrefix("/root/")
            val projectDirectory = File(installer.workspace, relativeProjectPath)
            if (!projectDirectory.isDirectory) return@forEach
            projectDirectory.walkTopDown()
                .onEnter { directory ->
                    directory == projectDirectory || directory.name !in IGNORED_DIRECTORIES
                }
                .filter(File::isFile)
                .take(MAX_INDEXED_FILES - results.size)
                .forEach { file ->
                    val relativePath = file.relativeTo(projectDirectory).invariantSeparatorsPath
                    val containerPath = "$projectPath/$relativePath"
                    results += UniversalSearchItem(
                        id = "file:$containerPath",
                        kind = UniversalSearchKind.FILE,
                        title = file.name,
                        subtitle = "${projectPath.replaceFirst("/root", "~")}/$relativePath",
                        keywords = relativePath,
                        target = UniversalSearchTarget.File(containerPath, projectPath),
                    )
                }
        }
        return results
    }

    private companion object {
        val SETTINGS = listOf(
            "Account & models" to "Authentication, providers, models, and response defaults",
            "Voice" to "On-device speech models, dictation, and playback",
            "Device control" to "Android pairing and automation readiness",
            "Terminal & Linux" to "Shell behavior and Linux workspace tools",
            "Storage & recovery" to "Backups, restore, repair, and data usage",
            "Appearance" to "Theme and display preferences",
            "Diagnostics" to "Logs, health checks, and build information",
        )
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", "build", "node_modules", "vendor", "dist")
    }
}

private sealed interface UniversalSearchLoadState {
    data object Loading : UniversalSearchLoadState
    data class Ready(val items: List<UniversalSearchItem>) : UniversalSearchLoadState
    data class Failed(val detail: String) : UniversalSearchLoadState
}

@Composable
internal fun UniversalSearchScreen(
    onBack: () -> Unit,
    onSelect: (UniversalSearchTarget) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { UniversalSearchRepository(context) }
    var loadState by remember(repository) { mutableStateOf<UniversalSearchLoadState>(UniversalSearchLoadState.Loading) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(UniversalSearchFilter.ALL) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(repository) {
        loadState = withContext(Dispatchers.IO) {
            runCatching(repository::load).fold(
                onSuccess = UniversalSearchLoadState::Ready,
                onFailure = { UniversalSearchLoadState.Failed(it.message ?: "Could not build the search index") },
            )
        }
        focusRequester.requestFocus()
    }

    val filtered = remember(loadState, query, filter) {
        val items = (loadState as? UniversalSearchLoadState.Ready)?.items.orEmpty()
        filterUniversalSearch(items, query, filter)
    }
    val grouped = remember(filtered) { filtered.groupBy(UniversalSearchItem::kind) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 50.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search workspace", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = "Search workspace" },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    )
                }
                if (query.isNotEmpty()) {
                    Box(Modifier.size(48.dp).clickable { query = "" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search", modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollStateCompat())
                .selectableGroup()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UniversalSearchFilter.entries.forEach { candidate ->
                val selected = filter == candidate
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.Tab) { filter = candidate },
                ) {
                    Text(
                        candidate.label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        when (val state = loadState) {
            UniversalSearchLoadState.Loading -> {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Indexing workspace…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
            is UniversalSearchLoadState.Failed -> SearchMessage(
                title = "Search is unavailable",
                detail = state.detail,
            )
            is UniversalSearchLoadState.Ready -> if (filtered.isEmpty()) {
                SearchMessage(
                    title = if (query.isBlank()) "Nothing in this category" else "No results for “${query.take(40)}”",
                    detail = "Try a project, chat title, file path, setting, or slash command.",
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    UniversalSearchKind.entries.forEach { kind ->
                        val section = grouped[kind].orEmpty()
                        if (section.isNotEmpty()) {
                            item("heading:${kind.name}") {
                                Text(
                                    kind.label.uppercase(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.semantics { heading() }.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(section, key = UniversalSearchItem::id) { item ->
                                UniversalSearchResultRow(item = item, onClick = { onSelect(item.target) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UniversalSearchResultRow(item: UniversalSearchItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${item.title}", onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(searchIcon(item.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (item.kind == UniversalSearchKind.COMMAND || item.kind == UniversalSearchKind.FILE) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                item.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SearchMessage(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

private fun searchIcon(kind: UniversalSearchKind): ImageVector = when (kind) {
    UniversalSearchKind.PROJECT -> Icons.Rounded.Folder
    UniversalSearchKind.CHAT -> Icons.Rounded.ChatBubbleOutline
    UniversalSearchKind.FILE -> Icons.AutoMirrored.Rounded.InsertDriveFile
    UniversalSearchKind.COMMAND -> Icons.Rounded.Code
    UniversalSearchKind.TERMINAL -> Icons.Rounded.Terminal
    UniversalSearchKind.SETTING -> Icons.Rounded.Settings
    UniversalSearchKind.ARCHIVE -> Icons.Rounded.Archive
}

@Composable
private fun rememberScrollStateCompat() = androidx.compose.foundation.rememberScrollState()

private const val MAX_INDEXED_FILES = 2_000
private const val MAX_VISIBLE_RESULTS = 120
