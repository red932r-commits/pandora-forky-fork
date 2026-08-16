package com.pandora.mobile.linux

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class CodexThreadSummary(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAtMillis: Long,
    val cwd: String,
)

/** Reads durable native Codex threads without keeping another app-server process alive. */
class CodexThreadCatalog(context: Context) {
    private val installer = RootfsInstaller(context.applicationContext)
    private val client = CodexAppServerClient(context.applicationContext)
    private val registry = PandoraChatRegistry(context.applicationContext)
    private val cache = File(installer.workspace, ".pandora/chat-list-cache.json")
    private val cacheStorage = DurableCatalogFile(cache)

    fun readCached(): List<CodexThreadSummary> = synchronized(CACHE_LOCK) {
        cacheStorage.read(emptyList(), ::parseCache)
    }

    fun read(): List<CodexThreadSummary> {
        val response = client.request(
            method = "thread/list",
            params = JSONObject()
                .put("limit", 100)
                .put("sortKey", "updated_at")
                .put("sortDirection", "desc"),
            fallbackError = "Could not load Codex threads",
        )
        val data = response.getJSONObject("result").getJSONArray("data")
        val registeredIds = registry.read()
        val threads = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val taggedByPandora = item.optString("threadSource") == "pandora_android"
                if (id !in registeredIds && !taggedByPandora) continue
                val preview = item.optString("preview").trim()
                val name = if (item.isNull("name")) "" else item.optString("name").trim()
                add(
                    CodexThreadSummary(
                        id = id,
                        title = name.ifBlank { preview.lineSequence().firstOrNull().orEmpty() }
                            .ifBlank { "Codex chat" }
                            .take(64),
                        preview = preview,
                        updatedAtMillis = item.optLong("updatedAt") * 1000L,
                        cwd = item.optString("cwd", "/root").ifBlank { "/root" },
                    ),
                )
            }
        }
        writeCache(threads)
        return threads
    }

    fun rename(threadId: String, newName: String) {
        val name = newName.trim().take(64)
        require(name.isNotBlank()) { "Enter a name for this chat" }
        request("thread/name/set", JSONObject().put("threadId", threadId).put("name", name))
        writeCache(readCached().map { if (it.id == threadId) it.copy(title = name) else it })
    }

    fun delete(threadId: String) {
        runCatching {
            request("thread/delete", JSONObject().put("threadId", threadId))
        }.onFailure { error ->
            val detail = error.message.orEmpty()
            val alreadyAbsent = detail.contains("not found", ignoreCase = true) ||
                detail.contains("does not exist", ignoreCase = true) ||
                detail.contains("unknown thread", ignoreCase = true)
            if (!alreadyAbsent) throw error
        }
        forget(threadId)
    }

    internal fun forget(threadId: String) {
        registry.remove(threadId)
        writeCache(readCached().filterNot { it.id == threadId })
    }

    private fun request(method: String, params: JSONObject) {
        client.request(method = method, params = params, fallbackError = "Could not update this chat")
    }

    private fun writeCache(threads: List<CodexThreadSummary>) = synchronized(CACHE_LOCK) {
        val data = JSONArray()
        threads.forEach { thread ->
            data.put(
                JSONObject()
                    .put("id", thread.id)
                    .put("title", thread.title)
                    .put("preview", thread.preview)
                    .put("updatedAtMillis", thread.updatedAtMillis)
                    .put("cwd", thread.cwd),
            )
        }
        cacheStorage.write(data.toString()) { parseCache(it) }
    }

    private fun parseCache(text: String): List<CodexThreadSummary> {
        val data = JSONArray(text)
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                add(
                    CodexThreadSummary(
                        id = item.getString("id").also { require(it.isNotBlank()) },
                        title = item.getString("title"),
                        preview = item.optString("preview"),
                        updatedAtMillis = item.getLong("updatedAtMillis"),
                        cwd = item.optString("cwd", "/root").ifBlank { "/root" },
                    ),
                )
            }
        }
    }

    private companion object {
        val CACHE_LOCK = Any()
    }
}
