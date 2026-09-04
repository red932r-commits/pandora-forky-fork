package com.pandora.mobile.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Version directories and relative links remain valid through workspace backup/restore. */
internal class RuntimeExecutableStore(private val workspace: File, private val name: String) {
    val live = File(workspace, ".local/bin/$name")
    private val previous = File(workspace, ".local/bin/$name.previous")
    private val versions = File(workspace, ".local/share/pandora/runtimes/$name")
    val canRollback: Boolean get() = runCatching {
        previous.isFile && (!live.exists() || previous.canonicalFile != live.canonicalFile)
    }.getOrDefault(false)

    fun createStage(version: String): File {
        require(releaseVersion(version) == version) { "Invalid runtime version" }
        return File(versions, "$version-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Could not create runtime staging directory" }
            File(this, ".pandora-runtime").writeText("$name\n")
        }
    }

    fun activate(executable: File) {
        require(executable.isFile && executable.canExecute()) { "The new $name executable is not ready" }
        val root = File(workspace, ".local/share/pandora/runtimes/$name").canonicalFile.toPath()
        require(executable.canonicalFile.toPath().startsWith(root)) { "Executable is outside runtime staging" }
        live.parentFile!!.mkdirs()
        if (Files.exists(live.toPath(), NOFOLLOW_LINKS)) replaceWithCopy(live, previous)
        val temporary = File(live.parentFile, ".$name-${UUID.randomUUID()}")
        try {
            Files.createSymbolicLink(temporary.toPath(), live.parentFile!!.toPath().relativize(executable.toPath()))
            Files.move(temporary.toPath(), live.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun rollback() {
        check(canRollback) { "No previous $name installation is available" }
        replaceWithCopy(previous, live)
        previous.delete()
    }

    /** Remove only owned, unreferenced versions; never follow a symlink while deleting. */
    fun prune() {
        val retained = listOf(live, previous).filter { it.exists() }.map { it.canonicalFile.toPath() }
        versions.listFiles().orEmpty().filter { directory ->
            directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
                File(directory, ".pandora-runtime").isFile &&
                retained.none { it.startsWith(directory.canonicalFile.toPath()) }
        }.forEach { directory ->
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun replaceWithCopy(source: File, target: File) {
        val temporary = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}")
        try {
            Files.copy(source.toPath(), temporary.toPath(), NOFOLLOW_LINKS, REPLACE_EXISTING)
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
