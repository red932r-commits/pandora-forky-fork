package com.pandora.mobile.linux

internal enum class RuntimeComponent(val title: String) {
    CODEX("Codex"), DEBIAN("Debian packages"), ZMX("Persistent terminals (zmx)"),
}

internal data class DebianPackageUpdate(val name: String, val installed: String?, val available: String)

internal data class RuntimeUpdate(
    val component: RuntimeComponent,
    val installed: String,
    val available: String? = null,
    val packages: List<DebianPackageUpdate> = emptyList(),
    val downloadUrl: String? = null,
    val sha256: String? = null,
    val error: String? = null,
    val needsPackageRecovery: Boolean = false,
) {
    val canUpdate: Boolean get() = error == null && available != null && available != installed
}

/** Parse apt's C-locale simulation, including multiarch names and Debian version epochs. */
internal fun parseDebianUpdates(output: String): List<DebianPackageUpdate> {
    val install = Regex("^Inst (\\S+)(?: \\[([^]]+)])? \\((\\S+).*$")
    check(output.lineSequence().none { it.startsWith("Remv ") }) {
        "This package plan removes installed packages. Update them manually in the terminal."
    }
    return output.lineSequence().filter { it.startsWith("Inst ") }.map { line ->
        val match = checkNotNull(install.matchEntire(line)) { "Could not read Debian's update plan" }
        val (name, old, new) = match.destructured
        require(Regex("[a-z0-9][a-z0-9+.:~-]*").matches(name)) { "Invalid Debian package name" }
        DebianPackageUpdate(name, old.ifBlank { null }, new)
    }.toList()
}

internal fun releaseVersion(text: String): String? =
    Regex("(?<![0-9.])v?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)(?![0-9.])")
        .find(text)?.groupValues?.get(1)

internal fun isNewerRelease(candidate: String, installed: String): Boolean {
    val old = releaseVersion(installed) ?: return true
    val new = releaseVersion(candidate) ?: return false
    val oldParts = old.substringBefore('-').split('.').map(String::toLong)
    val newParts = new.substringBefore('-').split('.').map(String::toLong)
    for (index in 0..2) {
        if (newParts[index] != oldParts[index]) return newParts[index] > oldParts[index]
    }
    return '-' in old && '-' !in new
}
