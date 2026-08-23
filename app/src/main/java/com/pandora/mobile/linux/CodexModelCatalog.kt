package com.pandora.mobile.linux

import org.json.JSONArray

data class CodexReasoningEffort(
    val value: String,
    val description: String,
)

data class CodexServiceTier(
    val id: String,
    val name: String,
    val description: String,
)

data class CodexModel(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val supportedReasoningEfforts: List<CodexReasoningEffort> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val serviceTiers: List<CodexServiceTier> = emptyList(),
    val defaultServiceTier: String? = null,
    val serviceTiersAdvertised: Boolean = false,
) {
    fun resolveReasoningEffort(current: String?): String? {
        if (current != null && supportedReasoningEfforts.any { it.value == current }) return current
        if (
            defaultReasoningEffort != null &&
            supportedReasoningEfforts.any { it.value == defaultReasoningEffort }
        ) {
            return defaultReasoningEffort
        }
        return supportedReasoningEfforts.firstOrNull()?.value
    }

    fun resolveServiceTier(current: String?): String? {
        if (current == null) return null
        if (serviceTiers.any { it.id == current }) return current
        if (defaultServiceTier != null && serviceTiers.any { it.id == defaultServiceTier }) {
            return defaultServiceTier
        }
        return null
    }
}

internal fun parseCodexModels(data: JSONArray): List<CodexModel> = buildList {
    for (index in 0 until data.length()) {
        val item = data.optJSONObject(index) ?: continue
        if (item.optBoolean("hidden")) continue
        val modelId = item.optString("model")
        if (modelId.isBlank()) continue

        val efforts = item.optJSONArray("supportedReasoningEfforts")?.let { options ->
            buildList {
                for (optionIndex in 0 until options.length()) {
                    val option = options.optJSONObject(optionIndex) ?: continue
                    val value = option.optString("reasoningEffort")
                    if (value.isBlank()) continue
                    add(
                        CodexReasoningEffort(
                            value = value,
                            description = option.optString("description"),
                        ),
                    )
                }
            }.distinctBy(CodexReasoningEffort::value)
        }.orEmpty()

        val advertisedServiceTiers = item.optJSONArray("serviceTiers")
        val serviceTiers = advertisedServiceTiers?.let { options ->
            buildList {
                for (optionIndex in 0 until options.length()) {
                    val option = options.optJSONObject(optionIndex) ?: continue
                    val id = option.optString("id")
                    if (id.isBlank()) continue
                    add(
                        CodexServiceTier(
                            id = id,
                            name = option.optString("name", id),
                            description = option.optString("description"),
                        ),
                    )
                }
            }.distinctBy(CodexServiceTier::id)
        } ?: item.optJSONArray("additionalSpeedTiers")?.let { options ->
            buildList {
                for (optionIndex in 0 until options.length()) {
                    val id = options.optString(optionIndex)
                    if (id.isBlank()) continue
                    add(
                        CodexServiceTier(
                            id = id,
                            name = id.replace('-', ' ').replaceFirstChar(Char::uppercase),
                            description = "",
                        ),
                    )
                }
            }.distinctBy(CodexServiceTier::id)
        }.orEmpty()

        add(
            CodexModel(
                id = item.optString("id", modelId),
                model = modelId,
                displayName = item.optString("displayName", modelId),
                description = item.optString("description"),
                isDefault = item.optBoolean("isDefault"),
                supportedReasoningEfforts = efforts,
                defaultReasoningEffort = item.optionalString("defaultReasoningEffort"),
                serviceTiers = serviceTiers,
                defaultServiceTier = item.optionalString("defaultServiceTier"),
                serviceTiersAdvertised = item.has("serviceTiers") || item.has("additionalSpeedTiers"),
            ),
        )
    }
}

private fun org.json.JSONObject.optionalString(key: String): String? =
    opt(key)?.takeUnless { it == org.json.JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)
