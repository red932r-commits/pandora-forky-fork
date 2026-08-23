package com.pandora.mobile

import java.net.URI
import java.util.Locale

enum class ReadinessLevel {
    OFF,
    SETUP_REQUIRED,
    READY,
    ACTIVE,
    WORKING,
    ERROR,
}

data class ReadinessStatus(
    val level: ReadinessLevel,
    val label: String,
    val detail: String,
)

fun adbReadiness(state: AdbPluginState): ReadinessStatus = when {
    state.controlActive -> ReadinessStatus(
        ReadinessLevel.ACTIVE,
        "Control active",
        "Pandora can currently operate this phone",
    )
    !state.enabled || state.stage == AdbPluginStage.DISABLED -> ReadinessStatus(
        ReadinessLevel.OFF,
        "Off",
        "Phone control is disabled",
    )
    state.stage == AdbPluginStage.ERROR -> ReadinessStatus(
        ReadinessLevel.ERROR,
        "Setup needs attention",
        state.detail ?: "Wireless debugging setup did not complete",
    )
    state.stage in setOf(
        AdbPluginStage.PREPARING,
        AdbPluginStage.WAITING_FOR_PAIRING,
        AdbPluginStage.WAITING_FOR_CODE,
        AdbPluginStage.PAIRING,
        AdbPluginStage.CONNECTING,
    ) -> ReadinessStatus(
        ReadinessLevel.WORKING,
        "Setup in progress",
        state.detail ?: "Preparing the device-control connection",
    )
    state.stage in setOf(AdbPluginStage.PAIRED, AdbPluginStage.CONNECTED) -> ReadinessStatus(
        ReadinessLevel.READY,
        "Ready",
        "Paired with Android Wireless debugging",
    )
    else -> ReadinessStatus(
        ReadinessLevel.SETUP_REQUIRED,
        "Setup required",
        "Pair this phone before enabling control",
    )
}

fun speechModelReadiness(state: SpeechModelDownload, selected: Boolean): ReadinessStatus = when (state) {
    SpeechModelDownload.Installed -> if (selected) {
        ReadinessStatus(ReadinessLevel.ACTIVE, "Active", "Installed and selected")
    } else {
        ReadinessStatus(ReadinessLevel.READY, "Ready", "Installed on this device")
    }
    SpeechModelDownload.NotInstalled -> ReadinessStatus(
        ReadinessLevel.SETUP_REQUIRED,
        "Download required",
        "Install this model before selecting it",
    )
    is SpeechModelDownload.Downloading -> ReadinessStatus(
        ReadinessLevel.WORKING,
        "Downloading",
        "${state.bytesRead} of ${state.totalBytes} bytes",
    )
    is SpeechModelDownload.Failed -> ReadinessStatus(
        ReadinessLevel.ERROR,
        "Download failed",
        state.detail,
    )
}

fun modelRouteLabel(
    mode: ModelProviderMode,
    customProvider: CustomModelProvider?,
    modelId: String?,
): String = when (mode) {
    ModelProviderMode.CODEX -> "OpenAI"
    ModelProviderMode.CUSTOM -> {
        val vendor = modelId
            ?.substringBefore('/')
            ?.takeIf { '/' in modelId }
            ?.trim()
            .orEmpty()
        when {
            vendor.isNotBlank() -> vendor.toDisplayName()
            else -> runCatching { URI(customProvider?.baseUrl.orEmpty()).host.orEmpty() }
                .getOrDefault("")
                .substringBefore('.')
                .takeIf(String::isNotBlank)
                ?.toDisplayName()
                ?: "remote provider"
        }
    }
}

private fun String.toDisplayName(): String = when (lowercase(Locale.US)) {
    "deepseek" -> "DeepSeek"
    "openrouter" -> "OpenRouter"
    "openai" -> "OpenAI"
    "anthropic" -> "Anthropic"
    "google" -> "Google"
    else -> replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(Locale.US) else character.toString()
    }
}
