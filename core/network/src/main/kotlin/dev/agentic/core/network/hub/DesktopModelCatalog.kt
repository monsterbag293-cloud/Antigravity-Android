package dev.agentic.core.network.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Canonical desktop model and usage-quota catalog verified against the Google Antigravity desktop
 * `language_server` (`GetUserStatus` and `RetrieveUserQuotaSummary`).
 *
 * Ensures that both genuine Hub responses (which may omit free-tier or cross-provider entries on
 * staging/daily endpoints) and local Gemini fallback mode expose the exact 6 desktop models and
 * 3 usage quota groups expected by the Jetski frontend.
 */
object DesktopModelCatalog {

    data class DesktopModelSpec(
        val label: String,
        val modelEnum: String,
        val disabled: Boolean = false,
        val supportsImages: Boolean = true,
        val isRecommended: Boolean = false,
        val tagTitle: String? = null,
        val tagDescription: String? = null,
        val modelId: String? = null,
        val quotaGroupName: String? = null
    )

    data class ModelQuotaSnapshot(
        val remainingFraction: Double?,
        val resetTime: String?
    )

    val DESKTOP_MODELS: List<DesktopModelSpec> = listOf(
        // 1. Gemini 4 Argon (Low / Medium [New] / High)
        DesktopModelSpec(
            label = "Gemini 4 Argon (Low)",
            modelEnum = "MODEL_PLACEHOLDER_M419",
            supportsImages = true,
            quotaGroupName = "Gemini 4 Argon"
        ),
        DesktopModelSpec(
            label = "Gemini 4 Argon (Medium)",
            modelEnum = "MODEL_PLACEHOLDER_M418",
            supportsImages = true,
            tagTitle = "New",
            quotaGroupName = "Gemini 4 Argon"
        ),
        DesktopModelSpec(
            label = "Gemini 4 Argon (High)",
            modelEnum = "MODEL_PLACEHOLDER_M417",
            supportsImages = true,
            quotaGroupName = "Gemini 4 Argon"
        ),
        // 2. Gemini 3.8 Flash (Low / Medium / High)
        DesktopModelSpec(
            label = "Gemini 3.8 Flash (Low)",
            modelEnum = "MODEL_PLACEHOLDER_M320",
            supportsImages = true,
            quotaGroupName = "Gemini Models"
        ),
        DesktopModelSpec(
            label = "Gemini 3.8 Flash (Medium)",
            modelEnum = "MODEL_PLACEHOLDER_M319",
            supportsImages = true,
            quotaGroupName = "Gemini Models"
        ),
        DesktopModelSpec(
            label = "Gemini 3.8 Flash (High)",
            modelEnum = "MODEL_PLACEHOLDER_M318",
            supportsImages = true,
            isRecommended = true,
            quotaGroupName = "Gemini Models"
        ),
        // 3. Claude Opus 5.5 (Low / Medium [New] / High)
        DesktopModelSpec(
            label = "Claude Opus 5.5 (Low)",
            modelEnum = "MODEL_PLACEHOLDER_M400",
            supportsImages = true,
            quotaGroupName = "Claude and GPT models"
        ),
        DesktopModelSpec(
            label = "Claude Opus 5.5 (Medium)",
            modelEnum = "MODEL_PLACEHOLDER_M401",
            supportsImages = true,
            tagTitle = "New",
            quotaGroupName = "Claude and GPT models"
        ),
        DesktopModelSpec(
            label = "Claude Opus 5.5 (High)",
            modelEnum = "MODEL_PLACEHOLDER_M402",
            supportsImages = true,
            quotaGroupName = "Claude and GPT models"
        ),
        // 4. Claude Sonnet 5.5 (Low / Medium [New] / High)
        DesktopModelSpec(
            label = "Claude Sonnet 5.5 (Low)",
            modelEnum = "MODEL_PLACEHOLDER_M403",
            supportsImages = true,
            quotaGroupName = "Claude and GPT models"
        ),
        DesktopModelSpec(
            label = "Claude Sonnet 5.5 (Medium)",
            modelEnum = "MODEL_PLACEHOLDER_M404",
            supportsImages = true,
            tagTitle = "New",
            quotaGroupName = "Claude and GPT models"
        ),
        DesktopModelSpec(
            label = "Claude Sonnet 5.5 (High)",
            modelEnum = "MODEL_PLACEHOLDER_M405",
            supportsImages = true,
            quotaGroupName = "Claude and GPT models"
        ),
        // 5. GPT-OSS 120B (Medium) [Notice]
        DesktopModelSpec(
            label = "GPT-OSS 120B (Medium)",
            modelEnum = "MODEL_OPENAI_GPT_OSS_120B_MEDIUM",
            supportsImages = false,
            tagTitle = "Notice",
            tagDescription = "Open-weight model hosted by Antigravity.",
            quotaGroupName = "Claude and GPT models"
        ),
        // 6. Gemini 4 Carbon [Unavailable - Ultra plan subscribers]
        DesktopModelSpec(
            label = "Gemini 4 Carbon",
            modelEnum = "MODEL_PLACEHOLDER_M670",
            disabled = true,
            supportsImages = true,
            tagTitle = "Unavailable",
            tagDescription = "Gemini 4 Carbon is currently available to Ultra plan subscribers.",
            modelId = "gemini-4-carbon-disabled",
            quotaGroupName = null
        )
    )

    val DESKTOP_QUOTA_GROUP_ORDER = listOf(
        "Gemini Models",
        "Claude and GPT models",
        "Gemini 4 Argon"
    )

    private val ARGON_MODEL_ENUMS = setOf(
        "MODEL_PLACEHOLDER_M417",
        "MODEL_PLACEHOLDER_M418",
        "MODEL_PLACEHOLDER_M419"
    )

    private const val DEFAULT_FREE_MODEL_ENUM = "MODEL_PLACEHOLDER_M318" // Gemini 3.8 Flash (High)

    fun toClientModelConfigJson(spec: DesktopModelSpec): JsonObject = buildJsonObject {
        put("label", spec.label)
        put("modelOrAlias", buildJsonObject {
            put("model", spec.modelEnum)
        })
        if (spec.disabled) put("disabled", true)
        put("supportsImages", spec.supportsImages)
        put("isRecommended", spec.isRecommended)
        spec.tagTitle?.let { put("tagTitle", it) }
        spec.tagDescription?.let { put("tagDescription", it) }
        spec.modelId?.let { put("modelId", it) }
    }

    fun buildDefaultCascadeModelConfigData(): JsonObject = buildJsonObject {
        put("clientModelConfigs", buildJsonArray {
            DESKTOP_MODELS.forEach { add(toClientModelConfigJson(it)) }
        })
        put("clientModelSorts", buildRecommendedModelSorts(DESKTOP_MODELS.map { it.label }))
        put("defaultOverrideModelConfig", buildJsonObject {
            put("modelOrAlias", buildJsonObject {
                put("model", DEFAULT_FREE_MODEL_ENUM)
            })
        })
    }

    fun buildRecommendedModelSorts(orderedLabels: List<String>): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("name", "Recommended")
            put("groups", buildJsonArray {
                add(buildJsonObject {
                    put("groupName", "Recommended Models")
                    put("modelLabels", buildJsonArray {
                        orderedLabels.forEach { add(JsonPrimitive(it)) }
                    })
                })
            })
        })
    }

    /**
     * Merges missing desktop models into a Hub `GetUserStatus` JSON object while preserving all
     * live Hub model metadata (`quotaInfo`, `disabled`, `supportedMimeTypes`, `contextWindowOptions`).
     */
    fun augmentUserStatusJson(
        root: JsonObject,
        quotaCacheOut: MutableMap<String, ModelQuotaSnapshot>? = null
    ): JsonObject? {
        val userStatus = root["userStatus"]?.jsonObject ?: return null
        val cascadeConfig = userStatus["cascadeModelConfigData"]?.jsonObject ?: buildJsonObject {}
        val existingConfigs = cascadeConfig["clientModelConfigs"]?.jsonArray?.toList().orEmpty()

        val existingByLabel = mutableMapOf<String, JsonObject>()
        val existingByEnum = mutableMapOf<String, JsonObject>()
        val extraConfigs = mutableListOf<JsonObject>()
        val desktopLabels = DESKTOP_MODELS.map { it.label }.toSet()
        val desktopEnums = DESKTOP_MODELS.map { it.modelEnum }.toSet()

        for (el in existingConfigs) {
            val obj = el.jsonObject
            val label = obj["label"]?.jsonPrimitive?.content
            val modelEnum = obj["modelOrAlias"]?.jsonObject?.get("model")?.jsonPrimitive?.content
            if (!label.isNullOrBlank()) existingByLabel[label] = obj
            if (!modelEnum.isNullOrBlank()) existingByEnum[modelEnum] = obj

            // Cache live quotaInfo by group for RetrieveUserQuotaSummary synthesis if needed
            val quotaObj = obj["quotaInfo"]?.jsonObject
            if (quotaCacheOut != null && quotaObj != null && !label.isNullOrBlank()) {
                val spec = DESKTOP_MODELS.firstOrNull {
                    it.label.equals(label, ignoreCase = true) || it.modelEnum.equals(modelEnum, ignoreCase = true)
                }
                val quotaGroupName = spec?.quotaGroupName
                if (quotaGroupName != null) {
                    val frac = quotaObj["remainingFraction"]?.jsonPrimitive?.doubleOrNull
                    val reset = quotaObj["resetTime"]?.jsonPrimitive?.content
                    if (frac != null || reset != null) {
                        quotaCacheOut[quotaGroupName] = ModelQuotaSnapshot(frac, reset)
                    }
                }
            }

            if (label != null && label !in desktopLabels && (modelEnum == null || modelEnum !in desktopEnums)) {
                extraConfigs.add(obj)
            }
        }

        val hasLiveCarbonVariant = existingConfigs.any { el ->
            el.jsonObject["label"]?.jsonPrimitive?.content?.startsWith("Gemini 4 Carbon", ignoreCase = true) == true
        }

        val mergedConfigs = mutableListOf<JsonObject>()
        val orderedLabels = mutableListOf<String>()

        for (spec in DESKTOP_MODELS) {
            val matched = existingByLabel[spec.label] ?: existingByEnum[spec.modelEnum]
            if (matched == null && spec.disabled && spec.label == "Gemini 4 Carbon" && hasLiveCarbonVariant) {
                continue
            }
            val finalConfig = if (matched != null) {
                enrichDesktopModelMetadata(matched, spec)
            } else {
                toClientModelConfigJson(spec)
            }
            mergedConfigs.add(finalConfig)
            val resolvedLabel = finalConfig["label"]?.jsonPrimitive?.content ?: spec.label
            if (resolvedLabel !in orderedLabels) orderedLabels.add(resolvedLabel)
        }

        for (extra in extraConfigs) {
            mergedConfigs.add(extra)
            val extraLabel = extra["label"]?.jsonPrimitive?.content
            if (!extraLabel.isNullOrBlank() && extraLabel !in orderedLabels) {
                orderedLabels.add(extraLabel)
            }
        }

        val existingSorts = cascadeConfig["clientModelSorts"]?.jsonArray
        val updatedSorts = mergeRecommendedSorts(existingSorts, orderedLabels)

        val planStatus = userStatus["planStatus"]?.jsonObject
        val planInfo = planStatus?.get("planInfo")?.jsonObject ?: userStatus["planInfo"]?.jsonObject
        val availableCredits = planStatus?.get("availablePromptCredits")?.jsonPrimitive?.longOrNull ?: 0L
        val teamsTier = planInfo?.get("teamsTier")?.jsonPrimitive?.content
        val isFreeTier = teamsTier != "TEAMS_TIER_PRO" || availableCredits <= 0L

        val currentDefaultModel = cascadeConfig["defaultOverrideModelConfig"]
            ?.jsonObject?.get("modelOrAlias")
            ?.jsonObject?.get("model")
            ?.jsonPrimitive?.content
        val argonQuotaFraction = quotaCacheOut?.get("Gemini 4 Argon")?.remainingFraction
        val shouldOverrideToFlash = cascadeConfig["defaultOverrideModelConfig"] == null ||
            (isFreeTier && (currentDefaultModel == null || currentDefaultModel in ARGON_MODEL_ENUMS)) ||
            (currentDefaultModel in ARGON_MODEL_ENUMS && argonQuotaFraction != null && argonQuotaFraction <= 0.0 && availableCredits <= 0L)

        val updatedCascadeConfig = buildJsonObject {
            cascadeConfig.forEach { (k, v) ->
                if (k != "clientModelConfigs" && k != "clientModelSorts" && k != "defaultOverrideModelConfig") {
                    put(k, v)
                }
            }
            put("clientModelConfigs", JsonArray(mergedConfigs))
            put("clientModelSorts", updatedSorts)
            if (shouldOverrideToFlash) {
                put("defaultOverrideModelConfig", buildJsonObject {
                    put("modelOrAlias", buildJsonObject {
                        put("model", DEFAULT_FREE_MODEL_ENUM)
                    })
                })
            } else {
                cascadeConfig["defaultOverrideModelConfig"]?.let { put("defaultOverrideModelConfig", it) }
            }
        }

        val updatedUserStatus = buildJsonObject {
            userStatus.forEach { (k, v) ->
                if (k != "cascadeModelConfigData") put(k, v)
            }
            put("cascadeModelConfigData", updatedCascadeConfig)
        }

        return buildJsonObject {
            root.forEach { (k, v) ->
                if (k != "userStatus") put(k, v)
            }
            put("userStatus", updatedUserStatus)
        }
    }

    private fun enrichDesktopModelMetadata(existing: JsonObject, spec: DesktopModelSpec): JsonObject {
        val existingDisabled = existing["disabled"]?.jsonPrimitive?.booleanOrNull == true
        if (spec.disabled && !existingDisabled) {
            return existing
        }
        val hasTag = existing["tagTitle"] != null
        val hasTagDesc = existing["tagDescription"] != null
        return buildJsonObject {
            existing.forEach { (k, v) -> put(k, v) }
            if (spec.disabled && existing["disabled"] == null) put("disabled", true)
            if (!hasTag && spec.tagTitle != null) put("tagTitle", spec.tagTitle)
            if (!hasTagDesc && spec.tagDescription != null) put("tagDescription", spec.tagDescription)
            if (existing["modelId"] == null && spec.modelId != null) put("modelId", spec.modelId)
            if (existing["isRecommended"] == null && spec.isRecommended) put("isRecommended", true)
        }
    }

    private fun mergeRecommendedSorts(existingSorts: JsonArray?, orderedLabels: List<String>): JsonArray {
        if (existingSorts == null || existingSorts.isEmpty()) {
            return buildRecommendedModelSorts(orderedLabels)
        }
        val hasRecommended = existingSorts.any {
            it.jsonObject["name"]?.jsonPrimitive?.content.equals("Recommended", ignoreCase = true)
        }
        val mapped = existingSorts.map { sortEl ->
            val sortObj = sortEl.jsonObject
            val isRecommended = sortObj["name"]?.jsonPrimitive?.content.equals("Recommended", ignoreCase = true)
            if (!isRecommended) {
                sortObj
            } else {
                val existingGroups = sortObj["groups"]?.jsonArray?.toList().orEmpty()
                val newGroups = if (existingGroups.isEmpty()) {
                    listOf(buildJsonObject {
                        put("groupName", "Recommended Models")
                        put("modelLabels", buildJsonArray { orderedLabels.forEach { add(JsonPrimitive(it)) } })
                    })
                } else {
                    existingGroups.mapIndexed { idx, grpEl ->
                        val grpObj = grpEl.jsonObject
                        if (idx == 0) {
                            val current = grpObj["modelLabels"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                            val combined = orderedLabels.toMutableList()
                            for (lbl in current) {
                                if (lbl !in combined) combined.add(lbl)
                            }
                            buildJsonObject {
                                grpObj.forEach { (k, v) ->
                                    if (k == "modelLabels") {
                                        put(k, buildJsonArray { combined.forEach { add(JsonPrimitive(it)) } })
                                    } else {
                                        put(k, v)
                                    }
                                }
                            }
                        } else {
                            grpObj
                        }
                    }
                }
                buildJsonObject {
                    sortObj.forEach { (k, v) ->
                        if (k == "groups") put(k, JsonArray(newGroups)) else put(k, v)
                    }
                }
            }
        }
        return if (hasRecommended) {
            JsonArray(mapped)
        } else {
            val out = mutableListOf<JsonElement>()
            out.addAll(buildRecommendedModelSorts(orderedLabels))
            out.addAll(mapped)
            JsonArray(out)
        }
    }

    fun buildDefaultQuotaGroups(
        quotaCache: Map<String, ModelQuotaSnapshot> = emptyMap(),
        now: Instant = Instant.now()
    ): JsonArray {
        return buildJsonArray {
            for (groupName in DESKTOP_QUOTA_GROUP_ORDER) {
                add(buildDefaultQuotaGroup(groupName, quotaCache[groupName], now))
            }
        }
    }

    private fun buildDefaultQuotaGroup(
        groupName: String,
        cached: ModelQuotaSnapshot?,
        now: Instant
    ): JsonObject {
        val defaultWeeklyReset = cached?.resetTime ?: now.plus(7, ChronoUnit.DAYS).toString()
        val default5hReset = now.plus(5, ChronoUnit.HOURS).toString()
        val weeklyFraction = cached?.remainingFraction ?: 1.0
        val fiveHourFraction = cached?.remainingFraction ?: 1.0

        return when (groupName) {
            "Gemini Models" -> buildJsonObject {
                put("displayName", "Gemini Models")
                put("description", "Quota limits for Gemini Flash models")
                put("buckets", buildJsonArray {
                    add(buildBucket(
                        bucketId = "gemini-models-weekly",
                        displayName = "Weekly limit",
                        window = "weekly",
                        description = "Weekly usage limit",
                        remainingFraction = weeklyFraction,
                        resetTime = defaultWeeklyReset
                    ))
                    add(buildBucket(
                        bucketId = "gemini-models-5h",
                        displayName = "5h limit",
                        window = "5h",
                        description = "5h rolling limit",
                        remainingFraction = fiveHourFraction,
                        resetTime = default5hReset
                    ))
                })
            }

            "Claude and GPT models" -> buildJsonObject {
                put("displayName", "Claude and GPT models")
                put("description", "Quota limits for Claude 5.5 and GPT-OSS models")
                put("buckets", buildJsonArray {
                    add(buildBucket(
                        bucketId = "claude-gpt-weekly",
                        displayName = "Weekly limit",
                        window = "weekly",
                        description = "Weekly usage limit",
                        remainingFraction = weeklyFraction,
                        resetTime = defaultWeeklyReset
                    ))
                    add(buildBucket(
                        bucketId = "claude-gpt-5h",
                        displayName = "5h limit",
                        window = "5h",
                        description = "5h rolling limit",
                        remainingFraction = fiveHourFraction,
                        resetTime = default5hReset
                    ))
                })
            }

            else -> buildJsonObject {
                put("displayName", "Gemini 4 Argon")
                put("description", "Quota limits for Gemini 4 Argon")
                put("buckets", buildJsonArray {
                    add(buildBucket(
                        bucketId = "gemini-4-argon-weekly",
                        displayName = "Weekly limit",
                        window = "weekly",
                        description = "Weekly usage limit",
                        remainingFraction = weeklyFraction,
                        resetTime = defaultWeeklyReset
                    ))
                })
            }
        }
    }

    private fun buildBucket(
        bucketId: String,
        displayName: String,
        window: String,
        description: String,
        remainingFraction: Double,
        resetTime: String
    ): JsonObject = buildJsonObject {
        put("bucketId", bucketId)
        put("displayName", displayName)
        put("description", description)
        put("window", window)
        put("remainingFraction", remainingFraction.coerceIn(0.0, 1.0))
        put("resetTime", resetTime)
        put("disabled", false)
    }

    /**
     * Preserves all live quota groups/buckets returned by Google Cloud Code via the Hub while:
     * 1. Ensuring `description` is populated whenever `resetTime` is present so `Resets in ...`
     *    renders reliably in Jetski UI.
     * 2. Ensuring all 3 desktop usage groups (`Gemini Models`, `Claude and GPT models`,
     *    `Gemini 4 Argon`) are present and ordered consistently with the desktop app.
     */
    fun augmentQuotaSummaryJson(
        root: JsonObject,
        quotaCache: Map<String, ModelQuotaSnapshot> = emptyMap(),
        now: Instant = Instant.now()
    ): JsonObject {
        val responseObj = root["response"]?.jsonObject ?: buildJsonObject {}
        val existingGroups = responseObj["groups"]?.jsonArray?.toList().orEmpty()

        val byDisplayName = mutableMapOf<String, JsonObject>()
        val extraGroups = mutableListOf<JsonObject>()
        for (el in existingGroups) {
            val grp = normalizeQuotaGroupBuckets(el.jsonObject)
            val name = grp["displayName"]?.jsonPrimitive?.content
            val matchedOrderName = DESKTOP_QUOTA_GROUP_ORDER.firstOrNull { it.equals(name, ignoreCase = true) }
            if (matchedOrderName != null) {
                byDisplayName[matchedOrderName] = grp
            } else {
                extraGroups.add(grp)
            }
        }

        val mergedGroups = mutableListOf<JsonObject>()
        for (groupName in DESKTOP_QUOTA_GROUP_ORDER) {
            val existing = byDisplayName[groupName]
            if (existing != null) {
                val buckets = existing["buckets"]?.jsonArray
                if (buckets == null || buckets.isEmpty()) {
                    mergedGroups.add(buildDefaultQuotaGroup(groupName, quotaCache[groupName], now))
                } else {
                    mergedGroups.add(existing)
                }
            } else {
                mergedGroups.add(buildDefaultQuotaGroup(groupName, quotaCache[groupName], now))
            }
        }
        mergedGroups.addAll(extraGroups)

        val updatedResponse = buildJsonObject {
            responseObj.forEach { (k, v) ->
                if (k != "groups") put(k, v)
            }
            put("groups", JsonArray(mergedGroups))
        }

        return buildJsonObject {
            root.forEach { (k, v) ->
                if (k != "response") put(k, v)
            }
            put("response", updatedResponse)
        }
    }

    private fun normalizeQuotaGroupBuckets(groupObj: JsonObject): JsonObject {
        val buckets = groupObj["buckets"]?.jsonArray ?: return groupObj
        val normalizedBuckets = buckets.map { bEl ->
            val bObj = bEl.jsonObject
            val hasDesc = !bObj["description"]?.jsonPrimitive?.content.isNullOrBlank()
            val hasDisplay = !bObj["displayName"]?.jsonPrimitive?.content.isNullOrBlank()
            val window = bObj["window"]?.jsonPrimitive?.content ?: ""
            buildJsonObject {
                bObj.forEach { (k, v) -> put(k, v) }
                if (!hasDisplay) {
                    val label = when (window.trim().lowercase()) {
                        "weekly" -> "Weekly limit"
                        "daily" -> "Daily limit"
                        else -> if (window.isNotBlank()) "${window.trim()} limit" else "Usage limit"
                    }
                    put("displayName", label)
                }
                if (!hasDesc && bObj["resetTime"] != null) {
                    put("description", "Quota window reset")
                }
            }
        }
        return buildJsonObject {
            groupObj.forEach { (k, v) ->
                if (k == "buckets") put(k, JsonArray(normalizedBuckets)) else put(k, v)
            }
        }
    }

    fun buildDefaultSkills(): JsonArray {
        val skillNames = listOf(
            Triple("agy-customizations", "Comprehensive guide and reference for the Antigravity Customization System.", true),
            Triple("antigravity-guide", "Provides a comprehensive guide, quick reference, and sitemap for Google Antigravity.", true),
            Triple("automation", "Interactive guide to design and create a scheduled background automation.", true),
            Triple("generative_ui", "How to render rich interactive HTML widgets inline in the chat or as standalone artifacts.", true),
            Triple("migrate-workflows", "Automatically migrate legacy workflows to modern skills across global and workspace configurations.", true),
            Triple("permissioned-github", "Guidelines for interacting with GitHub and request permissions from the user when required.", true),
            Triple("plugin", "How to manage and create plugins - namespaced bundles of skills, agents, rules, MCP servers and hooks.", true),
            Triple("reverse-engineer-anything", "Reverse engineer native, managed, Electron/JavaScript, packaged, firmware, and browser targets with REA.", false),
            Triple("run-workflow", "How to write and run a dynamic workflow script with the run_workflow tool.", true),
            Triple("ui-plugin-navigation", "Discover UI plugin panels relevant to the current task and surface a one-click pill in chat.", true)
        )
        return buildJsonArray {
            for ((name, desc, isBuiltin) in skillNames) {
                add(buildJsonObject {
                    put("name", name)
                    put("description", desc)
                    put("isBuiltin", isBuiltin)
                })
            }
        }
    }

    fun buildDefaultCustomizationStates(): JsonArray {
        val skillNames = listOf(
            "agy-customizations" to "SCOPE_BUILTIN",
            "antigravity-guide" to "SCOPE_BUILTIN",
            "automation" to "SCOPE_BUILTIN",
            "generative_ui" to "SCOPE_BUILTIN",
            "migrate-workflows" to "SCOPE_BUILTIN",
            "permissioned-github" to "SCOPE_BUILTIN",
            "plugin" to "SCOPE_BUILTIN",
            "reverse-engineer-anything" to "SCOPE_GLOBAL",
            "run-workflow" to "SCOPE_BUILTIN",
            "ui-plugin-navigation" to "SCOPE_BUILTIN"
        )
        return buildJsonArray {
            for ((name, scope) in skillNames) {
                add(buildJsonObject {
                    put("type", "REFRESH_CUSTOMIZATION_TYPE_SKILL")
                    put("name", name)
                    put("path", "builtin/skills/$name/SKILL.md")
                    put("scope", scope)
                    put("status", "STATUS_ENABLED")
                })
            }
            add(buildJsonObject {
                put("type", "REFRESH_CUSTOMIZATION_TYPE_MCP")
                put("name", "rea")
                put("path", "config/mcp_config.json")
                put("scope", "SCOPE_GLOBAL")
                put("status", "STATUS_ENABLED")
            })
        }
    }

    fun buildDefaultMcpServers(): JsonArray {
        return buildJsonArray {
            add(buildJsonObject {
                put("name", "rea")
                put("command", "reverse-engineer-anything")
                put("status", "CONNECTED")
            })
        }
    }

    fun buildDefaultNuxes(): JsonArray {
        return buildJsonArray {
            add(buildJsonObject {
                put("uid", 66)
                put("location", "CASCADE_NUX_LOCATION_MANAGER_BOTTOM_LEFT")
                put("analyticsEventName", "AGENT_NUX_EVENT_REMOTE_AGENTS_INTRO")
                put("learnMoreUrl", "https://antigravity.google/docs/remote-agents")
                put("priority", 110)
                put("title", "Introducing Remote Agents")
                put("body", "Your agents can now run in the cloud and keep working after you close the app.")
            })
        }
    }
}
