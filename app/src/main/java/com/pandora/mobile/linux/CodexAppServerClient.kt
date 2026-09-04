package com.pandora.mobile.linux

import android.content.Context
import com.pandora.mobile.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

internal class CodexAppServerException(
    val code: Int?,
    message: String,
) : IllegalStateException(message)

/** Owns the lifecycle and wire protocol for one short-lived app-server RPC. */
internal class CodexAppServerClient(context: Context) {
    private val installer = RootfsInstaller(context.applicationContext)

    fun request(
        method: String,
        params: JSONObject? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        prepareInstallation: Boolean = true,
        executablePath: String = "/root/.local/bin/codex",
        isolatedHome: String? = null,
        fallbackError: String,
    ): JSONObject {
        if (prepareInstallation) installer.installIfNeeded { }
        val process = installer.startContainerProcess(
            installer.containerCommand(*(if (isolatedHome == null) emptyList() else
                listOf("/usr/bin/env", "CODEX_HOME=$isolatedHome"))
                .plus(listOf(executablePath, "app-server")).toTypedArray()),
            mergeError = false,
        )
        val stderrDrainer = thread(name = "codex-rpc-stderr", isDaemon = true) {
            runCatching { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { } } }
        }
        val responseReader = Executors.newSingleThreadExecutor()
        try {
            process.outputStream.bufferedWriter().use { writer ->
                writer.appendLine(initializeRequest().toString())
                writer.appendLine(JSONObject().put("method", "initialized").put("params", JSONObject()).toString())
                val request = JSONObject().put("method", method).put("id", REQUEST_ID)
                params?.let { request.put("params", it) }
                writer.appendLine(request.toString())
                writer.flush()

                val response = responseReader.submit<JSONObject> {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                            .onEach { response ->
                                if (response.optInt("id", -1) == INITIALIZE_ID) {
                                    check(!response.has("error")) { "Codex app-server initialization failed" }
                                }
                            }
                            .first { it.optInt("id", -1) == REQUEST_ID }
                    }
                }.get(timeoutSeconds, TimeUnit.SECONDS)
                response.optJSONObject("error")?.let { error ->
                    throw CodexAppServerException(
                        code = error.optInt("code").takeIf { error.has("code") },
                        message = error.optString("message", fallbackError),
                    )
                }
                return response
            }
        } finally {
            installer.terminateProcessTree(process)
            responseReader.shutdownNow()
            runCatching { stderrDrainer.join(500) }
        }
    }

    private fun initializeRequest(): JSONObject = JSONObject()
        .put("method", "initialize")
        .put("id", INITIALIZE_ID)
        .put(
            "params",
            JSONObject().put(
                "clientInfo",
                JSONObject()
                    .put("name", "pandora_android")
                    .put("title", "Pandora Android")
                    .put("version", BuildConfig.VERSION_NAME),
            ),
        )

    private companion object {
        const val INITIALIZE_ID = 1
        const val REQUEST_ID = 2
        const val DEFAULT_TIMEOUT_SECONDS = 30L
    }
}
