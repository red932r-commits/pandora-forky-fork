package com.pandora.mobile.linux

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeArchiveTest {
    @Test fun `extracts package resources and executable permissions`() = fixture { root ->
        val archive = archive(root, listOf("bin/codex", "bin/codex-code-mode-host", "codex-path/rg", "codex-resources/zsh/bin/zsh", "codex-package.json"))
        val stage = File(root, "stage").apply { mkdir() }
        extractCodexPackage(archive, stage)
        assertEquals("payload", File(stage, "codex-package.json").readText())
        assertTrue(File(stage, "bin/codex").canExecute())
        assertTrue(File(stage, "codex-resources/zsh/bin/zsh").canExecute())
    }

    @Test fun `rejects traversal absolute and unrelated paths`() = fixture { root ->
        listOf("bin/../../escaped", "/bin/codex", "../escaped", ".pandora-runtime", "bin//codex").forEachIndexed { index, name ->
            val stage = File(root, "stage$index").apply { mkdir() }
            val archive = archive(root, listOf(name))
            assertThrows(IllegalStateException::class.java) { extractCodexPackage(archive, stage) }
        }
        assertFalse(File(root, "escaped").exists())
    }

    @Test fun `rejects links duplicate entries and oversized manifest`() = fixture { root ->
        val stage = File(root, "stage").apply { mkdir() }
        assertThrows(IllegalStateException::class.java) { extractCodexPackage(archive(root, listOf("bin/codex", "bin/codex")), stage) }
        assertThrows(IllegalStateException::class.java) { extractCodexPackage(archive(root, listOf("bin/link"), link = true), stage) }
        assertThrows(IllegalStateException::class.java) { extractCodexPackage(archive(root, listOf("codex-package.json"), size = 20_000), stage) }
    }

    private fun archive(root: File, names: List<String>, link: Boolean = false, size: Int = 7): File {
        val file = File(root, "package.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(file.outputStream())).use { tar ->
            names.forEach { name ->
                val entry = if (link) TarArchiveEntry(name, '2'.code.toByte()) else TarArchiveEntry(name, true)
                entry.mode = 0b111101101
                if (link) entry.linkName = "../../outside" else entry.size = size.toLong()
                tar.putArchiveEntry(entry)
                if (!link) tar.write(if (size == 7) "payload".toByteArray() else ByteArray(size))
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("runtime-archive-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
