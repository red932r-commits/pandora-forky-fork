package com.pandora.mobile.linux

import java.io.File
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Extract only the official package layout; never create links or paths outside staging. */
internal fun extractCodexPackage(archive: File, stage: File) {
    val seen = mutableSetOf<String>()
    var total = 0L
    TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered())).use { tar ->
        var entry = tar.nextEntry
        while (entry != null) {
            val name = entry.name.removePrefix("./").trimEnd('/')
            val parts = name.split('/')
            check(name.length <= 512 && parts.none { it.isEmpty() || it == "." || it == ".." } &&
                parts.first() in setOf("bin", "codex-package.json", "codex-path", "codex-resources")) { "Invalid Codex package path" }
            check(seen.add(name) && seen.size <= 2000) { "Duplicate or excessive Codex package entries" }
            check(!entry.isSymbolicLink && !entry.isLink && (entry.isFile || entry.isDirectory)) { "Unsupported Codex package entry" }
            total += entry.size
            check(entry.size >= 0 && total <= 512L * 1024 * 1024 &&
                (name != "codex-package.json" || entry.size <= 16 * 1024)) { "Codex package exceeds the size limit" }
            val target = File(stage, name)
            check(target.canonicalFile.toPath().startsWith(stage.canonicalFile.toPath())) { "Codex package escaped staging" }
            if (entry.isDirectory) {
                check(target.mkdirs() || target.isDirectory) { "Could not create Codex package directory" }
            } else {
                check(!target.exists()) { "Codex package would overwrite a staged file" }
                target.parentFile!!.mkdirs()
                target.outputStream().use(tar::copyTo)
                if (entry.mode and 0b001001001 != 0) check(target.setExecutable(true, true)) { "Could not make Codex file executable" }
            }
            entry = tar.nextEntry
        }
    }
}
