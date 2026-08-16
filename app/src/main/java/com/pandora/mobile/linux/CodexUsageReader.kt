package com.pandora.mobile.linux

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class CodexLimitWindow(
    val remainingPercent: Int,
    val durationMinutes: Int,
    val resetsAt: Long,
)

sealed interface CodexLimitsState {
    data object Loading : CodexLimitsState
    data object SignedOut : CodexLimitsState
    data object Unavailable : CodexLimitsState
    data class Available(
        val primary: CodexLimitWindow,
        val secondary: CodexLimitWindow?,
    ) : CodexLimitsState
}

/** Reads ChatGPT-backed Codex allowance through the documented app-server JSON-RPC API. */
class CodexUsageReader(context: Context) {
    private val installer = RootfsInstaller(context.applicationContext)
    private val client = CodexAppServerClient(context.applicationContext)
    private val codex = File(installer.workspace, ".local/bin/codex")
    private val auth = File(installer.workspace, ".codex/auth.json")

    fun read(): CodexLimitsState {
        if (!auth.exists()) return CodexLimitsState.SignedOut
        if (!codex.exists() || !installer.rootfs.exists() || !installer.proot.exists()) {
            return CodexLimitsState.Unavailable
        }
        return runCatching { readFromAppServer() }.getOrDefault(CodexLimitsState.Unavailable)
    }

    private fun readFromAppServer(): CodexLimitsState.Available {
        val response = client.request(
            method = "account/rateLimits/read",
            timeoutSeconds = 20,
            prepareInstallation = false,
            fallbackError = "Codex usage failed",
        )
        val rateLimits = response.getJSONObject("result").getJSONObject("rateLimits")
        return CodexLimitsState.Available(
            primary = parseWindow(rateLimits.getJSONObject("primary")),
            secondary = rateLimits.optJSONObject("secondary")?.let(::parseWindow),
        )
    }

    private fun parseWindow(json: JSONObject): CodexLimitWindow = CodexLimitWindow(
        remainingPercent = (100.0 - json.getDouble("usedPercent")).roundToInt().coerceIn(0, 100),
        durationMinutes = json.getInt("windowDurationMins"),
        resetsAt = json.getLong("resetsAt"),
    )
}
