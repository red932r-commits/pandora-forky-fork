package com.pandora.mobile.linux

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject

internal class DebianRecoveryRequired : IllegalStateException(
    "Debian has unfinished package configuration. Finish package setup before checking again.",
)

internal class RuntimeUpdateBackend(context: Context) {
    private val appContext = context.applicationContext
    private val installer = RootfsInstaller(appContext)

    fun canRollback(component: RuntimeComponent): Boolean = component != RuntimeComponent.DEBIAN &&
        store(component).canRollback

    fun check(onStatus: (String) -> Unit): List<RuntimeUpdate> {
        check(installer.rootfs.isDirectory) { "Open a Linux terminal to finish setup first." }
        return RuntimeComponent.entries.map { component ->
            onStatus("Checking ${component.title}…")
            val installed = installedVersion(component)
            try {
                when (component) {
                    RuntimeComponent.CODEX -> {
                        val metadata = JSONObject(fetchText("https://registry.npmjs.org/@openai%2Fcodex/latest"))
                        val latest = metadata.getString("version")
                        require(releaseVersion(latest) == latest && '-' !in latest) { "Invalid stable Codex release" }
                        val asset = releaseAsset("openai/codex", "rust-v$latest", "codex-package-aarch64-unknown-linux-musl.tar.gz")
                        RuntimeUpdate(component, installed, if (isNewerRelease(latest, installed)) latest else installed,
                            downloadUrl = asset.first, sha256 = asset.second)
                    }
                    RuntimeComponent.DEBIAN -> {
                        requireHealthyDebian()
                        refreshApt(onStatus)
                        val updates = parseDebianUpdates(command(listOf("/usr/bin/apt-get", "-s", "upgrade")))
                        RuntimeUpdate(component, installed, if (updates.isEmpty()) installed else "${updates.size} package updates", updates)
                    }
                    RuntimeComponent.ZMX -> {
                        val release = JSONObject(fetchText("https://api.github.com/repos/neurosnap/zmx/releases/latest"))
                        check(!release.optBoolean("prerelease") && !release.optBoolean("draft")) { "No stable zmx release found" }
                        val latest = release.getString("tag_name").removePrefix("v")
                        require(releaseVersion(latest) == latest && '-' !in latest) { "Invalid stable zmx release" }
                        val assets = release.getJSONArray("assets")
                        val expectedName = "zmx-$latest-linux-aarch64.tar.gz"
                        val asset = (0 until assets.length()).map(assets::getJSONObject)
                            .firstOrNull { it.getString("name") == expectedName }
                            ?: error("No ARM64 Linux download is available")
                        val expectedUrl = "https://github.com/neurosnap/zmx/releases/download/v$latest/$expectedName"
                        check(asset.getString("browser_download_url") == expectedUrl) { "Unexpected zmx download source" }
                        val digest = asset.optString("digest").removePrefix("sha256:")
                        check(Regex("[a-f0-9]{64}").matches(digest)) { "The release has no verified SHA-256 digest" }
                        RuntimeUpdate(component, installed, if (isNewerRelease(latest, installed)) latest else installed,
                            downloadUrl = expectedUrl, sha256 = digest)
                    }
                }
            } catch (error: Exception) {
                RuntimeUpdate(component, installed, error = error.message ?: "Could not check for updates",
                    needsPackageRecovery = error is DebianRecoveryRequired)
            }
        }
    }

    fun update(update: RuntimeUpdate, onStatus: (String) -> Unit, onOutput: (String) -> Unit): String {
        require(update.canUpdate) { "Check for updates before installing" }
        if (update.component != RuntimeComponent.DEBIAN) {
            check(isNewerRelease(checkNotNull(update.available), installedVersion(update.component))) {
                "The installed version changed. Check updates again before installing."
            }
        }
        val requiredMb = if (update.component == RuntimeComponent.CODEX) 600 else 400
        check(installer.workspace.usableSpace >= requiredMb * 1024L * 1024) { "Free at least $requiredMb MB before updating ${update.component.title}." }
        if (update.component == RuntimeComponent.DEBIAN) {
            requireHealthyDebian()
            refreshApt(onStatus)
            val packages = update.packages
            val currentPlan = parseDebianUpdates(command(listOf("/usr/bin/apt-get", "-s", "upgrade")))
            check(currentPlan.toSet() == packages.toSet()) { "The Debian package plan changed. Check updates again before installing." }
            val install = listOf("/usr/bin/apt-get", "--no-remove", "--only-upgrade", "install") +
                packages.map { "${it.name}=${it.available}" }
            val exactPlan = parseDebianUpdates(command(install + "--simulate"))
            check(exactPlan.toSet() == packages.toSet()) { "Debian now requires a different package plan. Check updates again." }
            onStatus("Installing ${packages.size} Debian package updates…")
            command(install + listOf("-y", "-o", "Dpkg::Options::=--force-confold", "-o", "Dpkg::Options::=--force-confdef"),
                timeoutSeconds = 1800, onOutput = onOutput)
            requireHealthyDebian()
            return "Updated ${packages.size} Debian packages"
        }

        val name = executableName(update.component)
        val executableStore = store(update.component)
        val stage = executableStore.createStage(checkNotNull(update.available))
        val guest = "/root/" + stage.relativeTo(installer.workspace).invariantSeparatorsPath
        var activated = false
        try {
            val executable = if (update.component == RuntimeComponent.CODEX) {
                onStatus("Downloading Codex ${update.available}…")
                val archive = downloadArchive(update, stage)
                try { extractCodexPackage(archive, stage) } finally { archive.delete() }
                val manifest = JSONObject(File(stage, "codex-package.json").readText())
                check(manifest.optInt("layoutVersion") == 1 && manifest.optString("version") == update.available &&
                    manifest.optString("target") == "aarch64-unknown-linux-musl" && manifest.optString("variant") == "codex" &&
                    manifest.optString("entrypoint") == "bin/codex" && manifest.optString("resourcesDir") == "codex-resources" &&
                    manifest.optString("pathDir") == "codex-path") { "The Codex package layout is not supported" }
                File(stage, "bin/codex").also { check(it.isFile && it.canExecute()) { "The Codex executable is missing" } }
            } else {
                onStatus("Downloading zmx ${update.available}…")
                downloadZmx(update, stage)
            }
            val guestExecutable = "/root/" + executable.relativeTo(installer.workspace).invariantSeparatorsPath
            onStatus("Verifying ${update.component.title} ${update.available}…")
            val version = command(listOf(guestExecutable, if (name == "codex") "--version" else "version"))
            check(releaseVersion(version) == update.available) { "The downloaded $name reported an unexpected version" }
            if (name == "codex") {
                val probe = File(stage, "probe-home").apply { mkdirs() }
                try {
                    // Isolate the probe so a failed candidate cannot migrate the user's Codex database.
                    CodexAppServerClient(appContext).request(
                        method = "thread/list", params = JSONObject().put("limit", 1), prepareInstallation = false,
                        executablePath = guestExecutable, isolatedHome = "$guest/probe-home",
                        fallbackError = "The new Codex app-server failed its compatibility check",
                    )
                } finally { probe.deleteRecursively() }
            } else {
                command(listOf(guestExecutable, "list", "--short"))
            }
            executableStore.activate(executable)
            activated = true
            runCatching(executableStore::prune).onFailure { onOutput("Older runtime files could not be removed: ${it.message}") }
            return "${update.component.title} updated to ${update.available}"
        } finally {
            if (!activated) stage.deleteRecursively()
        }
    }

    fun rollback(component: RuntimeComponent): String {
        store(component).rollback()
        runCatching { store(component).prune() }
        return "${component.title} restored to ${installedVersion(component)}"
    }

    fun finishPackageSetup(onStatus: (String) -> Unit, onOutput: (String) -> Unit): String {
        onStatus("Finishing Debian package configuration…")
        command(listOf("/usr/bin/dpkg", "--force-confold", "--force-confdef", "--configure", "-a"),
            timeoutSeconds = 1800, onOutput = onOutput)
        requireHealthyDebian()
        return "Debian package configuration finished. Check for updates again."
    }

    private fun installedVersion(component: RuntimeComponent): String {
        if (component == RuntimeComponent.DEBIAN) return "Debian 13 (Trixie)"
        val name = executableName(component)
        if (!File(installer.workspace, ".local/bin/$name").exists()) return "Not installed"
        return runCatching { releaseVersion(command(listOf("/root/.local/bin/$name",
            if (name == "codex") "--version" else "version"))) ?: "Unknown version" }.getOrDefault("Needs repair")
    }

    private fun requireHealthyDebian() {
        if (command(listOf("/usr/bin/dpkg", "--audit")).isNotBlank()) throw DebianRecoveryRequired()
    }

    private fun refreshApt(onStatus: (String) -> Unit) {
        onStatus("Refreshing Debian package lists…")
        command(listOf("/usr/bin/apt-get", "-o", "APT::Update::Error-Mode=any", "-o", "Acquire::Retries=1",
            "-o", "Acquire::http::Timeout=30", "-o", "Acquire::https::Timeout=30", "update"),
            timeoutSeconds = 180)
    }

    private fun command(args: List<String>, timeoutSeconds: Long = 45, onOutput: (String) -> Unit = {}): String {
        val process = installer.startContainerProcess(installer.containerCommand(*args.toTypedArray()), mergeError = true, packageCommand = true)
        process.outputStream.close()
        val output = StringBuilder()
        val reader = thread(name = "runtime-update-output", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    var lastReport = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            output.append(buffer, 0, count)
                            if (output.length > 1_048_576) output.delete(0, output.length - 1_048_576)
                            if (System.nanoTime() - lastReport > 500_000_000) {
                                output.toString().lineSequence().filter(String::isNotBlank).lastOrNull()?.let { onOutput(it.take(2000)) }
                                lastReport = System.nanoTime()
                            }
                        }
                    }
                }
            }
        }
        try {
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                "The Linux command timed out. Check updates again; Debian may need package setup recovery."
            }
            reader.join(2000)
            check(!reader.isAlive) { "The Linux command did not close its output" }
            val text = synchronized(output) { output.toString().trim() }
            check(process.exitValue() == 0) { text.takeLast(1500).ifBlank { "Linux command failed (${process.exitValue()})" } }
            return text
        } finally {
            if (process.isAlive) installer.terminateProcessTree(process)
            runCatching { process.inputStream.close() }
            reader.join(2000)
        }
    }

    private fun fetchText(url: String): String = openConnection(url).let { connection ->
        try {
            check(connection.responseCode == 200) { "Update source returned HTTP ${connection.responseCode}. Try again later." }
            connection.inputStream.use { input ->
                val bytes = input.readBytesLimited(2 * 1024 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private fun releaseAsset(repository: String, tag: String, name: String): Pair<String, String> {
        val release = JSONObject(fetchText("https://api.github.com/repos/$repository/releases/tags/$tag"))
        check(!release.optBoolean("prerelease") && !release.optBoolean("draft") && release.getString("tag_name") == tag) {
            "No matching stable release found"
        }
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name") == name }
            ?: error("No supported ARM64 Linux package is available")
        val url = "https://github.com/$repository/releases/download/$tag/$name"
        check(asset.getString("browser_download_url") == url) { "Unexpected release download source" }
        val digest = asset.optString("digest").removePrefix("sha256:")
        check(Regex("[a-f0-9]{64}").matches(digest)) { "The release has no verified SHA-256 digest" }
        return url to digest
    }

    private fun downloadArchive(update: RuntimeUpdate, stage: File): File {
        val archive = File(stage, "download.tar.gz")
        val connection = openConnection(checkNotNull(update.downloadUrl))
        try {
            check(connection.responseCode == 200) { "Could not download ${update.component.title} (HTTP ${connection.responseCode})" }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input -> archive.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 192L * 1024 * 1024) { "Runtime download exceeds the size limit" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            } }
            check(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) { "${update.component.title} checksum did not match" }
            return archive
        } catch (error: Exception) {
            archive.delete()
            throw error
        } finally { connection.disconnect() }
    }

    private fun downloadZmx(update: RuntimeUpdate, stage: File): File {
        val archive = downloadArchive(update, stage)
        try {
            val executable = File(stage, "zmx")
            TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered())).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.name.removePrefix("./") == "zmx") {
                        check(entry.isFile && !entry.isSymbolicLink && !entry.isLink && entry.size <= 200L * 1024 * 1024) {
                            "Invalid zmx archive entry"
                        }
                        check(!executable.exists()) { "Duplicate executable in zmx archive" }
                        executable.outputStream().use(tar::copyTo)
                    }
                    entry = tar.nextEntry
                }
            }
            check(executable.isFile && executable.setExecutable(true, true)) { "The zmx executable is missing" }
            return executable
        } finally {
            archive.delete()
        }
    }

    private fun openConnection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        require(url.startsWith("https://")) { "Update downloads require HTTPS" }
        connectTimeout = 15_000
        readTimeout = 45_000
        setRequestProperty("User-Agent", "Pandora-runtime-updater")
        setRequestProperty("Accept", "application/json, application/octet-stream")
    }

    private fun store(component: RuntimeComponent) = RuntimeExecutableStore(installer.workspace, executableName(component))
    private fun executableName(component: RuntimeComponent) = when (component) {
        RuntimeComponent.CODEX -> "codex"
        RuntimeComponent.ZMX -> "zmx"
        RuntimeComponent.DEBIAN -> error("Debian does not support executable rollback")
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit) { "Update metadata exceeds the size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
