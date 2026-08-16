package com.pandora.mobile.linux

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WorkspaceBackup(private val filesDir: File) {
    data class Result(val files: Int, val bytes: Long)

    data class Progress(
        val completedBytes: Long,
        val totalBytes: Long,
        val completedFiles: Int,
        val totalFiles: Int,
    ) {
        val fraction: Float
            get() = when {
                totalBytes > 0 -> (completedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
                totalFiles > 0 -> (completedFiles.toFloat() / totalFiles).coerceIn(0f, 1f)
                else -> 1f
            }
    }

    private val workspace = File(filesDir, "linux-workspace")
    private val previousWorkspace = File(filesDir, PREVIOUS_WORKSPACE)
    private val restoreMarker = File(filesDir, RESTORE_MARKER)

    fun write(
        output: OutputStream,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        check(workspace.isDirectory) { "The Linux workspace has not been created yet" }
        val totals = measureWorkspace()
        var fileCount = 0
        var byteCount = 0L
        var lastProgressNanos = 0L
        val symlinks = mutableListOf<Pair<String, String>>()
        val executables = mutableListOf<String>()

        fun reportProgress(force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastProgressNanos >= PROGRESS_INTERVAL_NANOS) {
                onProgress(
                    Progress(
                        completedBytes = byteCount.coerceAtMost(totals.bytes),
                        totalBytes = totals.bytes,
                        completedFiles = fileCount.coerceAtMost(totals.files),
                        totalFiles = totals.files,
                    ),
                )
                lastProgressNanos = now
            }
        }

        reportProgress(force = true)

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putText(MANIFEST, "format=1\nroot=linux-workspace\n")
            Files.walk(workspace.toPath()).use { paths ->
                paths.forEach { path ->
                    val relative = workspace.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                    val archivePath = if (relative.isEmpty()) "workspace/" else "workspace/$relative"
                    when {
                        Files.isSymbolicLink(path) -> {
                            symlinks += relative to Files.readSymbolicLink(path).toString()
                        }
                        Files.isDirectory(path) -> {
                            val name = archivePath.trimEnd('/') + "/"
                            zip.putNextEntry(ZipEntry(name).apply { time = path.toFile().lastModified() })
                            zip.closeEntry()
                        }
                        Files.isRegularFile(path) -> {
                            zip.putNextEntry(ZipEntry(archivePath).apply { time = path.toFile().lastModified() })
                            Files.newInputStream(path).use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    zip.write(buffer, 0, count)
                                    byteCount += count
                                    reportProgress()
                                }
                            }
                            zip.closeEntry()
                            fileCount++
                            reportProgress()
                            if (Files.isExecutable(path)) executables += relative
                        }
                    }
                }
            }
            zip.putText(SYMLINKS, symlinks.joinToString("") { encode(it.first) + "\t" + encode(it.second) + "\n" })
            zip.putText(EXECUTABLES, executables.joinToString("") { encode(it) + "\n" })
        }
        reportProgress(force = true)
        return Result(fileCount, byteCount)
    }

    private fun measureWorkspace(): Result {
        var files = 0
        var bytes = 0L
        Files.walk(workspace.toPath()).use { paths ->
            paths.forEach { path ->
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files++
                    bytes += Files.size(path)
                }
            }
        }
        return Result(files, bytes)
    }

    @Synchronized
    fun restore(input: InputStream): Result {
        recoverInterruptedRestore()
        val staging = File(filesDir, "$RESTORE_STAGING_PREFIX${UUID.randomUUID()}")
        val restoredWorkspace = File(staging, "workspace")
        var manifest: String? = null
        var symlinkData = ""
        var executableData = ""
        var fileCount = 0
        var byteCount = 0L

        try {
            restoredWorkspace.mkdirs()
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++fileCount <= MAX_ENTRIES) { "Backup contains too many entries" }
                    when (entry.name) {
                        MANIFEST -> manifest = zip.readLimited(MAX_METADATA_BYTES)
                        SYMLINKS -> symlinkData = zip.readLimited(MAX_METADATA_BYTES)
                        EXECUTABLES -> executableData = zip.readLimited(MAX_METADATA_BYTES)
                        else -> {
                            check(entry.name.startsWith("workspace/")) { "Unknown backup entry: ${entry.name}" }
                            val relative = entry.name.removePrefix("workspace/").trimEnd('/')
                            if (relative.isNotEmpty()) {
                                val target = safeTarget(restoredWorkspace, relative)
                                if (entry.isDirectory) {
                                    check(target.mkdirs() || target.isDirectory) { "Could not create $relative" }
                                } else {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().buffered().use { output ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        while (true) {
                                            val count = zip.read(buffer)
                                            if (count < 0) break
                                            byteCount += count
                                            check(byteCount <= MAX_UNCOMPRESSED_BYTES) { "Backup is too large" }
                                            output.write(buffer, 0, count)
                                        }
                                    }
                                    if (entry.time > 0) target.setLastModified(entry.time)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            check(manifest?.lineSequence()?.any { it == "format=1" } == true) {
                "This is not a supported Pandora backup"
            }
            restoreSymlinks(restoredWorkspace, symlinkData)
            executableData.lineSequence().filter { it.isNotBlank() }.forEach { encoded ->
                safeTarget(restoredWorkspace, decode(encoded)).setExecutable(true, false)
            }
            replaceWorkspace(restoredWorkspace)
            return Result(fileCount, byteCount)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Resolves an interrupted directory swap before anything opens the workspace.
     * If the new workspace was activated, it wins; otherwise the previous one is
     * put back. Both outcomes leave a complete directory at the canonical path.
     */
    @Synchronized
    fun recoverInterruptedRestore() {
        if (restoreMarker.exists() || previousWorkspace.exists()) {
            val hadWorkspace = restoreMarker.takeIf(File::isFile)
                ?.readText()
                ?.lineSequence()
                ?.firstOrNull()
                ?.substringAfter("had-workspace=", "")
                ?.toBooleanStrictOrNull()
                ?: previousWorkspace.exists()
            when {
                workspace.exists() -> previousWorkspace.deleteRecursively()
                previousWorkspace.exists() -> moveDirectory(previousWorkspace, workspace)
                hadWorkspace -> error("An interrupted restore left no recoverable Linux workspace")
            }
            check(restoreMarker.delete() || !restoreMarker.exists()) {
                "Could not finish restore recovery"
            }
        }
        filesDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(RESTORE_STAGING_PREFIX) }
            .forEach(File::deleteRecursively)
    }

    private fun restoreSymlinks(root: File, data: String) {
        data.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t', limit = 2)
            check(parts.size == 2) { "Invalid symlink metadata" }
            val link = safeTarget(root, decode(parts[0]))
            check(!link.exists() && !Files.isSymbolicLink(link.toPath())) { "Duplicate backup path" }
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), File(decode(parts[1])).toPath())
        }
    }

    private fun replaceWorkspace(restored: File) {
        check(!previousWorkspace.exists()) { "A previous restore has not been recovered" }
        val hadWorkspace = workspace.exists()
        writeRestoreMarker("prepared", hadWorkspace)
        try {
            if (hadWorkspace) moveDirectory(workspace, previousWorkspace)
            writeRestoreMarker("previous-staged", hadWorkspace)
            moveDirectory(restored, workspace)
            writeRestoreMarker("restored-active", hadWorkspace)
            previousWorkspace.deleteRecursively()
            check(restoreMarker.delete() || !restoreMarker.exists()) { "Could not finalize the restored workspace" }
        } catch (error: Throwable) {
            if (!workspace.exists() && hadWorkspace && previousWorkspace.exists()) {
                runCatching { moveDirectory(previousWorkspace, workspace) }
            }
            throw error
        }
    }

    private fun writeRestoreMarker(state: String, hadWorkspace: Boolean) {
        FileOutputStream(restoreMarker).use { output ->
            output.write("had-workspace=$hadWorkspace\nstate=$state\n".toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun moveDirectory(source: File, target: File) {
        check(source.exists()) { "Workspace directory is missing: ${source.name}" }
        check(!target.exists()) { "Workspace directory already exists: ${target.name}" }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun safeTarget(root: File, relative: String): File {
        check(relative.isNotBlank() && !relative.contains('\\')) { "Unsafe backup path" }
        val target = File(root, relative).canonicalFile
        val rootPath = root.canonicalPath
        check(target.path.startsWith(rootPath + File.separator)) { "Unsafe backup path" }
        return target
    }

    private fun ZipOutputStream.putText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun ZipInputStream.readLimited(limit: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "Backup metadata is too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    companion object {
        private const val MANIFEST = ".pandora-backup/manifest.properties"
        private const val SYMLINKS = ".pandora-backup/symlinks.tsv"
        private const val EXECUTABLES = ".pandora-backup/executables.txt"
        private const val MAX_ENTRIES = 200_000
        private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 8L * 1024 * 1024 * 1024
        private const val PREVIOUS_WORKSPACE = "linux-workspace-restore-previous"
        private const val RESTORE_MARKER = "linux-workspace-restore-in-progress"
        private const val RESTORE_STAGING_PREFIX = ".workspace-restore-"
        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L
    }
}
