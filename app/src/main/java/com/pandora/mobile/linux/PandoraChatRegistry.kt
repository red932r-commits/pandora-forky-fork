package com.pandora.mobile.linux

import android.content.Context
import java.io.File

/** Exact durable set of Codex thread IDs created by Pandora's New chat action. */
class PandoraChatRegistry(context: Context) {
    private val file = File(context.filesDir, "linux-workspace/.pandora/chat-thread-ids")
    private val storage = DurableCatalogFile(file)

    fun read(): Set<String> = synchronized(LOCK) {
        storage.read(emptySet(), ::parse)
    }

    fun add(threadId: String) = synchronized(LOCK) {
        if (threadId.isBlank()) return@synchronized
        val ids = read().toMutableSet()
        if (!ids.add(threadId)) return@synchronized
        write(ids)
    }

    fun remove(threadId: String) = synchronized(LOCK) {
        val ids = read().toMutableSet()
        if (!ids.remove(threadId)) return@synchronized
        if (ids.isEmpty()) {
            storage.delete()
        } else {
            write(ids)
        }
    }

    private fun write(ids: Set<String>) {
        storage.write(ids.sorted().joinToString("\n", postfix = "\n")) { parse(it) }
    }

    private fun parse(text: String): Set<String> = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .onEach { require('\u0000' !in it) { "Invalid chat identifier" } }
        .toSet()

    private companion object {
        val LOCK = Any()
    }
}
