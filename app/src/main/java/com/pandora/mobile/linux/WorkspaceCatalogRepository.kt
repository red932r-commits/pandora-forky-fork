package com.pandora.mobile.linux

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Completes idempotent, cross-catalog deletions from a durable intent journal. */
class WorkspaceCatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val projectCatalog = ProjectCatalog(appContext)
    private val archiveCatalog = ArchiveCatalog(appContext)
    private val chatCatalog = CodexThreadCatalog(appContext)
    private val journalFile = File(appContext.filesDir, "linux-workspace/.pandora/catalog-mutation.json")
    private val journal = DurableCatalogFile(journalFile)

    fun recoverPendingDeletion(): ArchivedWorkspace? = synchronized(LOCK) {
        readPending()?.let(::complete)
    }

    fun deleteProject(path: String, chatIds: Collection<String>): ArchivedWorkspace = synchronized(LOCK) {
        recoverPendingDeletion()
        val pending = PendingDeletion(projectPath = path, chatIds = chatIds.filter(String::isNotBlank).distinct())
        writePending(pending)
        complete(pending)
    }

    fun deleteChat(threadId: String): ArchivedWorkspace = synchronized(LOCK) {
        require(threadId.isNotBlank()) { "Chat identifier is missing" }
        recoverPendingDeletion()
        val pending = PendingDeletion(projectPath = null, chatIds = listOf(threadId))
        writePending(pending)
        complete(pending)
    }

    private fun complete(pending: PendingDeletion): ArchivedWorkspace {
        val requestedIds = pending.chatIds.toSet()
        if (requestedIds.isNotEmpty()) {
            pending.chatIds.forEach(chatCatalog::delete)
        }
        pending.projectPath?.let(projectCatalog::remove)
        var archive = archiveCatalog.read()
        pending.projectPath?.let { archive = archiveCatalog.setProjectArchived(it, false) }
        if (requestedIds.isNotEmpty()) archive = archiveCatalog.removeChats(requestedIds)
        journal.delete()
        return archive
    }

    private fun readPending(): PendingDeletion? = journal.read(null, ::parse)

    private fun writePending(pending: PendingDeletion) {
        val data = JSONObject()
            .put("projectPath", pending.projectPath ?: JSONObject.NULL)
            .put("chatIds", JSONArray(pending.chatIds))
        journal.write(data.toString()) { parse(it) }
    }

    private fun parse(text: String): PendingDeletion {
        val data = JSONObject(text)
        val path = if (data.isNull("projectPath")) null else data.getString("projectPath")
        path?.let { require(it.startsWith("/root/") && "/../" !in "$it/") { "Invalid pending project path" } }
        val values = data.getJSONArray("chatIds")
        val ids = buildList {
            for (index in 0 until values.length()) {
                val id = values.getString(index)
                require(id.isNotBlank()) { "Invalid pending chat identifier" }
                add(id)
            }
        }
        return PendingDeletion(path, ids.distinct())
    }

    private data class PendingDeletion(val projectPath: String?, val chatIds: List<String>)

    private companion object {
        val LOCK = Any()
    }
}
