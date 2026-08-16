package com.pandora.mobile.linux

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class ZmxSessionCatalog(context: Context) {
    private val installer = RootfsInstaller(context.applicationContext)

    fun read(): List<String> {
        val zmx = File(installer.workspace, ".local/bin/zmx")
        if (!zmx.isFile || !installer.rootfs.isDirectory) return emptyList()
        return runCatching { readRequired() }.getOrDefault(emptyList())
    }

    fun stopAll() {
        val zmx = File(installer.workspace, ".local/bin/zmx")
        if (!zmx.isFile || !installer.rootfs.isDirectory) return
        readRequired().forEach { session ->
            val process = installer.startContainerProcess(
                installer.containerCommand("/root/.local/bin/zmx", "kill", session, "--force"),
                mergeError = true,
            )
            try {
                check(process.waitFor(10, TimeUnit.SECONDS)) { "Timed out stopping terminal $session" }
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                check(process.exitValue() == 0) { output.ifBlank { "Could not stop terminal $session" } }
            } finally {
                if (process.isAlive) installer.terminateProcessTree(process)
            }
        }
    }

    private fun readRequired(): List<String> {
        val process = installer.startContainerProcess(
            installer.containerCommand("/root/.local/bin/zmx", "list", "--short"),
            mergeError = true,
        )
        return try {
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Timed out checking persistent terminals" }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.exitValue() == 0) { output.trim().ifBlank { "Could not check persistent terminals" } }
            output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        } finally {
            if (process.isAlive) installer.terminateProcessTree(process)
        }
    }
}
