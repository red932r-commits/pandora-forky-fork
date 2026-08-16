package com.pandora.mobile.linux

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class CatalogCorruptionException(file: File, cause: Throwable) : IllegalStateException(
    "${file.name} is damaged. Pandora preserved it as ${file.name}.corrupt instead of replacing it.",
    cause,
)

/** Fsyncs and atomically replaces a small text catalog while retaining a validated mirror. */
internal class DurableCatalogFile(private val file: File) {
    private val backup = File(file.parentFile, "${file.name}.bak")
    private val corrupt = File(file.parentFile, "${file.name}.corrupt")
    private val temporary = File(file.parentFile, "${file.name}.tmp")

    fun <T> read(defaultValue: T, parse: (String) -> T): T {
        val primary = file.takeIf(File::isFile)?.let { runCatching { parse(it.readText()) } }
        primary?.getOrNull()?.let { return it }

        val fallback = backup.takeIf(File::isFile)?.let { runCatching { parse(it.readText()) } }
        fallback?.getOrNull()?.let { value ->
            primary?.exceptionOrNull()?.let { original ->
                runCatching { quarantine(file) }.exceptionOrNull()?.let { preservationFailure ->
                    throw CatalogCorruptionException(file, original).also { it.addSuppressed(preservationFailure) }
                }
            }
            restoreBackup()
            return value
        }

        val failure = primary?.exceptionOrNull() ?: fallback?.exceptionOrNull()
        if (failure != null) {
            val damagedFile = if (primary?.isFailure == true) file else backup
            val preservationFailure = runCatching { quarantine(damagedFile) }.exceptionOrNull()
            throw CatalogCorruptionException(file, failure).also { error ->
                fallback?.exceptionOrNull()?.takeIf { it !== failure }?.let(error::addSuppressed)
                preservationFailure?.let(error::addSuppressed)
            }
        }
        if (corrupt.isFile) {
            throw CatalogCorruptionException(file, IllegalStateException("A damaged catalog is awaiting recovery"))
        }
        return defaultValue
    }

    fun write(text: String, validate: (String) -> Unit) {
        validate(text)
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create catalog directory" } }
        writeSynced(temporary, text)
        try {
            if (file.isFile) {
                if (runCatching { validate(file.readText()) }.isSuccess) {
                    move(file, backup)
                } else {
                    quarantine(file)
                }
            }
            move(temporary, file)
            writeSynced(temporary, text)
            move(temporary, backup)
        } catch (error: Throwable) {
            if (!file.exists() && backup.isFile) runCatching(::restoreBackup)
            throw error
        } finally {
            temporary.delete()
        }
    }

    fun delete() {
        check(file.delete() || !file.exists()) { "Could not delete ${file.name}" }
        check(backup.delete() || !backup.exists()) { "Could not delete ${backup.name}" }
        temporary.delete()
    }

    private fun restoreBackup() {
        check(backup.isFile) { "Catalog backup is missing" }
        writeSynced(temporary, backup.readText())
        move(temporary, file)
    }

    private fun quarantine(source: File) {
        if (!source.isFile) return
        Files.copy(source.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING)
        check(source.delete() || !source.exists()) { "Could not quarantine ${source.name}" }
    }

    private fun writeSynced(target: File, text: String) {
        FileOutputStream(target).use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).also { writer ->
                writer.write(text)
                writer.flush()
            }
            stream.fd.sync()
        }
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
