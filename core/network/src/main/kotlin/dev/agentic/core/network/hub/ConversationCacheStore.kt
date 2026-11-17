package dev.agentic.core.network.hub

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/**
 * Persistent on-device disk cache for zero-project ("outside-of-project" / Standalone)
 * conversations so they survive app restarts, language_server restarts, and offline transitions.
 */
class ConversationCacheStore(
    private val cacheFile: File
) {
    @Serializable
    data class CachedEntry(
        val cascadeId: String,
        val summary: String = "Untitled Conversation",
        val projectId: String = OUTSIDE_OF_PROJECT_ID,
        val stepCount: Int = 0,
        val createdTimeIso: String = "",
        val lastModifiedTimeIso: String = "",
        val summaryJson: String? = null,
        val trajectoryJson: String? = null,
        val stepsJson: String? = null
    )

    @Serializable
    private data class CacheState(
        val entries: MutableMap<String, CachedEntry> = mutableMapOf(),
        val deletedIds: MutableSet<String> = mutableSetOf(),
        val cachedProjectIds: List<String> = emptyList(),
        val cachedProjectIdsByBackend: MutableMap<String, List<String>> = mutableMapOf(),
        val cachedReadProjectsJson: String? = null,
        val cachedAllSummariesJson: String? = null,
        val cachedAgentStateSnapshots: MutableMap<String, String> = mutableMapOf()
    )

    companion object {
        const val OUTSIDE_OF_PROJECT_ID = "outside-of-project"
    }

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private var state: CacheState = loadFromDisk()

    private fun loadFromDisk(): CacheState {
        return try {
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                val decoded = json.decodeFromString<CacheState>(cacheFile.readText(Charsets.UTF_8))
                val (migrated, changed) = migrateBackendProjectIds(decoded)
                if (changed) {
                    persistStateToDisk(migrated)
                }
                migrated
            } else {
                CacheState()
            }
        } catch (_: Exception) {
            CacheState()
        }
    }

    private fun isRemoteProjectObject(obj: JsonObject): Boolean {
        val bt = obj["backendType"]?.let { el ->
            (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        }.orEmpty()
        return bt.equals("2", ignoreCase = true) ||
            bt.contains("REMOTE", ignoreCase = true) ||
            bt.contains("CLOUD", ignoreCase = true)
    }

    private fun migrateBackendProjectIds(loaded: CacheState): Pair<CacheState, Boolean> {
        val readProjects = loaded.cachedReadProjectsJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).asObjOrNull()?.get("projects").asArrOrNull() }.getOrNull()
        }.orEmpty()
        if (readProjects.isEmpty()) return loaded to false
        val remoteIds = mutableSetOf<String>()
        val sidecarIds = mutableSetOf<String>()
        for (el in readProjects) {
            val obj = el.asObjOrNull() ?: continue
            val id = obj["id"].asStrOrNull() ?: continue
            if (isRemoteProjectObject(obj)) {
                remoteIds.add(id)
            }
            val name = obj["name"].asStrOrNull().orEmpty()
            if (obj.containsKey("sidecar") || name == "swarm/swarm_board") {
                sidecarIds.add(id)
            }
        }
        if (remoteIds.isEmpty() && sidecarIds.isEmpty()) return loaded to false
        var changed = false
        val nextByBackend = loaded.cachedProjectIdsByBackend.toMutableMap()
        if (sidecarIds.isNotEmpty()) {
            for ((k, list) in loaded.cachedProjectIdsByBackend) {
                val withoutSidecars = list.filterNot { it in sidecarIds }
                if (withoutSidecars.size != list.size) {
                    nextByBackend[k] = withoutSidecars
                    changed = true
                }
            }
        }
        val existingLocal = nextByBackend["0"].orEmpty()
        val existingCloud = nextByBackend["2"].orEmpty()
        val movedFromLocal = existingLocal.filter { it in remoteIds }
        val movedFromLegacy = loaded.cachedProjectIds.filter { it in remoteIds }
        if (movedFromLocal.isNotEmpty() || movedFromLegacy.isNotEmpty()) {
            nextByBackend["0"] = existingLocal.filterNot { it in remoteIds || it in sidecarIds }
            nextByBackend["2"] = (existingCloud + movedFromLocal + movedFromLegacy)
                .filterNot { it in sidecarIds }
                .distinct()
            changed = true
        }
        val updatedLegacy = loaded.cachedProjectIds.filterNot { it in remoteIds || it in sidecarIds }
        if (updatedLegacy.size != loaded.cachedProjectIds.size) {
            changed = true
        }
        if (!changed) return loaded to false
        return loaded.copy(
            cachedProjectIds = updatedLegacy,
            cachedProjectIdsByBackend = nextByBackend
        ) to true
    }

    private fun persistToDisk() {
        persistStateToDisk(state)
    }

    private fun persistStateToDisk(targetState: CacheState) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(json.encodeToString(targetState), Charsets.UTF_8)
            if (cacheFile.exists()) cacheFile.delete()
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun JsonElement?.asObjOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArrOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement?.asStrOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
    private fun JsonElement?.asLongOrNull(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    private fun timestampElementToIso(el: JsonElement?, fallbackIso: String): String {
        val str = el.asStrOrNull()
        if (!str.isNullOrBlank()) return str
        val obj = el.asObjOrNull() ?: return fallbackIso
        val seconds = obj["seconds"].asLongOrNull() ?: obj["seconds"].asStrOrNull()?.toLongOrNull() ?: return fallbackIso
        val nanos = obj["nanos"].asLongOrNull() ?: obj["nanos"].asStrOrNull()?.toLongOrNull() ?: 0L
        return runCatching { Instant.ofEpochSecond(seconds, nanos).toString() }.getOrDefault(fallbackIso)
    }

    private fun extractUserTextFromStep(stepObj: JsonObject): String? {
        val directUserInput = stepObj["userInput"].asObjOrNull()
        val oneof = stepObj["step"].asObjOrNull()
        val oneofCase = oneof?.get("case").asStrOrNull()
        val oneofVal = if (oneofCase == "userInput") oneof?.get("value").asObjOrNull() else null
        val ui = directUserInput ?: oneofVal ?: return null
        val firstChunkText = ui["items"].asArrOrNull()?.firstNotNullOfOrNull { itemEl ->
            val itemObj = itemEl.asObjOrNull() ?: return@firstNotNullOfOrNull null
            itemObj["text"].asStrOrNull()?.takeIf { it.isNotBlank() }
                ?: itemObj["chunk"].asObjOrNull()?.let { ch ->
                    if (ch["case"].asStrOrNull() == "text") ch["value"].asStrOrNull()?.takeIf { it.isNotBlank() } else null
                }
        }
        return firstChunkText ?: ui["userResponse"].asStrOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun isUserInputStep(stepObj: JsonObject): Boolean {
        val typeStr = stepObj["type"].asStrOrNull().orEmpty()
        val stepCase = stepObj["step"].asObjOrNull()?.get("case").asStrOrNull()
        return typeStr == "CORTEX_STEP_TYPE_USER_INPUT" ||
            typeStr == "14" ||
            stepObj.containsKey("userInput") ||
            stepCase == "userInput"
    }

    private fun normalizeStepToProtoJson(el: JsonElement, fallbackIso: String): JsonObject? {
        val obj = el.asObjOrNull() ?: return null
        // Already Proto-JSON if it has no Protobuf-ES $typeName / oneof `step` wrapper
        val stepOneof = obj["step"].asObjOrNull()
        val isProtoEs = obj.containsKey("\$typeName") || stepOneof != null
        if (!isProtoEs) return obj

        val stepCase = stepOneof?.get("case").asStrOrNull()
        val stepVal = stepOneof?.get("value").asObjOrNull()
        val rawType = obj["type"].asStrOrNull()
        val resolvedType = when {
            rawType == "CORTEX_STEP_TYPE_USER_INPUT" || rawType == "14" || stepCase == "userInput" -> "CORTEX_STEP_TYPE_USER_INPUT"
            rawType == "CORTEX_STEP_TYPE_PLANNER_RESPONSE" || rawType == "15" || stepCase == "plannerResponse" -> "CORTEX_STEP_TYPE_PLANNER_RESPONSE"
            rawType == "CORTEX_STEP_TYPE_ERROR_MESSAGE" || rawType == "7" || stepCase == "error" -> "CORTEX_STEP_TYPE_ERROR_MESSAGE"
            rawType != null && rawType.startsWith("CORTEX_STEP_TYPE_") -> rawType
            else -> "CORTEX_STEP_TYPE_GENERIC"
        }
        val rawStatus = obj["status"].asStrOrNull()
        val resolvedStatus = when (rawStatus) {
            "1", "CORTEX_STEP_STATUS_PENDING" -> "CORTEX_STEP_STATUS_PENDING"
            "2", "CORTEX_STEP_STATUS_RUNNING" -> "CORTEX_STEP_STATUS_RUNNING"
            "3", "CORTEX_STEP_STATUS_DONE" -> "CORTEX_STEP_STATUS_DONE"
            "4", "CORTEX_STEP_STATUS_ERROR" -> "CORTEX_STEP_STATUS_ERROR"
            else -> if (rawStatus != null && rawStatus.startsWith("CORTEX_STEP_STATUS_")) rawStatus else "CORTEX_STEP_STATUS_DONE"
        }
        val metaObj = obj["metadata"].asObjOrNull()
        val createdIso = timestampElementToIso(metaObj?.get("createdAt"), fallbackIso)
        val completedIso = metaObj?.get("completedAt")?.let { timestampElementToIso(it, createdIso) }

        return buildJsonObject {
            put("type", resolvedType)
            put("status", resolvedStatus)
            put("metadata", buildJsonObject {
                put("createdAt", createdIso)
                if (completedIso != null) put("completedAt", completedIso)
            })
            when (stepCase) {
                "userInput" -> {
                    val userText = extractUserTextFromStep(obj).orEmpty()
                    put("userInput", buildJsonObject {
                        put("items", buildJsonArray {
                            add(buildJsonObject { put("text", userText) })
                        })
                        if (userText.isNotEmpty()) put("userResponse", userText)
                    })
                }
                "plannerResponse" -> if (stepVal != null) {
                    put("plannerResponse", buildJsonObject {
                        stepVal["response"].asStrOrNull()?.let { put("response", it) }
                        stepVal["modifiedResponse"].asStrOrNull()?.takeIf { it.isNotEmpty() }?.let { put("modifiedResponse", it) }
                        stepVal["thinking"].asStrOrNull()?.takeIf { it.isNotEmpty() }?.let { put("thinking", it) }
                        stepVal["rawThinking"].asStrOrNull()?.takeIf { it.isNotEmpty() }?.let { put("rawThinking", it) }
                        stepVal["messageId"].asStrOrNull()?.takeIf { it.isNotEmpty() }?.let { put("messageId", it) }
                    })
                }
                "error" -> if (stepVal != null) {
                    put("error", buildJsonObject {
                        stepVal["shortError"].asStrOrNull()?.let { put("shortError", it) }
                        stepVal["fullError"].asStrOrNull()?.let { put("fullError", it) }
                    })
                }
                else -> {
                    obj["userInput"].asObjOrNull()?.let { put("userInput", it) }
                    obj["plannerResponse"].asObjOrNull()?.let { put("plannerResponse", it) }
                    obj["error"].asObjOrNull()?.let { put("error", it) }
                }
            }
        }
    }

    fun isZeroProjectSummary(summaryObj: JsonObject): Boolean {
        val metaProj = summaryObj["trajectoryMetadata"].asObjOrNull()
            ?.get("projectId").asStrOrNull()
            ?.trim()
        val workspaces = summaryObj["workspaces"].asArrOrNull()
        return (metaProj.isNullOrEmpty() || metaProj == OUTSIDE_OF_PROJECT_ID) &&
            (workspaces == null || workspaces.isEmpty())
    }

    fun isTrackedZeroProject(cascadeId: String): Boolean = synchronized(lock) {
        state.entries.containsKey(cascadeId) && !state.deletedIds.contains(cascadeId)
    }

    fun listCachedEntries(): List<CachedEntry> = synchronized(lock) {
        state.entries.values.filter { it.cascadeId !in state.deletedIds }
    }

    fun markDeleted(cascadeId: String) {
        synchronized(lock) {
            state.entries.remove(cascadeId)
            state.cachedAgentStateSnapshots.remove(cascadeId)
            state.deletedIds.add(cascadeId)
            state.cachedAllSummariesJson?.let { raw ->
                runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()?.let { prev ->
                    if (prev.containsKey(cascadeId)) {
                        val updated = buildJsonObject {
                            prev.forEach { (k, v) -> if (k != cascadeId) put(k, v) }
                        }
                        state = state.copy(cachedAllSummariesJson = updated.toString())
                    }
                }
            }
            persistToDisk()
        }
    }

    fun isSidecarProjectId(projectId: String): Boolean = synchronized(lock) {
        if (projectId.isBlank() || projectId == OUTSIDE_OF_PROJECT_ID) return@synchronized false
        val readProjects = state.cachedReadProjectsJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).asObjOrNull()?.get("projects").asArrOrNull() }.getOrNull()
        } ?: return@synchronized false
        for (el in readProjects) {
            val obj = el.asObjOrNull() ?: continue
            val id = obj["id"].asStrOrNull()
            if (id == projectId) {
                val name = obj["name"].asStrOrNull().orEmpty()
                return@synchronized obj.containsKey("sidecar") || name == "swarm/swarm_board"
            }
        }
        false
    }

    fun cacheProjectListIds(ids: List<String>, backend: Int = 0) {
        val realIds = ids.filter { id ->
            id.isNotBlank() && id != OUTSIDE_OF_PROJECT_ID && !isSidecarProjectId(id)
        }
        if (realIds.isEmpty()) return
        val storableIds = ids.filter { id -> id.isNotBlank() && !isSidecarProjectId(id) }
        val key = backend.toString()
        synchronized(lock) {
            val prevForBackend = state.cachedProjectIdsByBackend[key].orEmpty()
            val merged = (storableIds + prevForBackend).distinct()
            val changedMap = prevForBackend != merged
            val changedLegacy = backend == 0 && state.cachedProjectIds != storableIds
            if (changedMap || changedLegacy) {
                state.cachedProjectIdsByBackend[key] = merged
                if (backend == 0) {
                    state = state.copy(cachedProjectIds = storableIds)
                }
                persistToDisk()
            }
        }
    }

    fun getCachedProjectListIds(backend: Int = 0): List<String> = synchronized(lock) {
        val key = backend.toString()
        state.cachedProjectIdsByBackend[key]?.takeIf { it.isNotEmpty() }
            ?: if (backend == 0) state.cachedProjectIds else emptyList()
    }

    private fun extractProjectCacheKey(projEl: JsonElement): String? {
        val obj = projEl.asObjOrNull() ?: return null
        return obj["id"].asStrOrNull()?.takeIf { it.isNotBlank() }
            ?: obj["projectId"].asStrOrNull()?.takeIf { it.isNotBlank() }
            ?: obj["name"].asStrOrNull()?.takeIf { it.isNotBlank() }
            ?: obj["workspaceUris"].asArrOrNull()?.joinToString(",")
    }

    fun cacheReadProjectsResponse(respObj: JsonObject) {
        val incoming = respObj["projects"].asArrOrNull() ?: return
        if (incoming.isEmpty()) return
        synchronized(lock) {
            val prevObj = state.cachedReadProjectsJson?.let { raw ->
                runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
            }
            val prevArr = prevObj?.get("projects").asArrOrNull().orEmpty()
            val byKey = linkedMapOf<String, JsonElement>()
            for (el in prevArr) {
                val k = extractProjectCacheKey(el) ?: continue
                byKey[k] = el
            }
            for (el in incoming) {
                val k = extractProjectCacheKey(el) ?: continue
                byKey[k] = el
            }
            val mergedResp = if (byKey.isNotEmpty()) {
                buildJsonObject {
                    respObj.forEach { (k, v) ->
                        if (k == "projects") {
                            put("projects", buildJsonArray { byKey.values.forEach { add(it) } })
                        } else if (k != "notFoundIds") {
                            put(k, v)
                        }
                    }
                    if (!respObj.containsKey("projects")) {
                        put("projects", buildJsonArray { byKey.values.forEach { add(it) } })
                    }
                }
            } else {
                respObj
            }
            val encoded = mergedResp.toString()
            if (state.cachedReadProjectsJson != encoded) {
                state = state.copy(cachedReadProjectsJson = encoded)
                persistToDisk()
            }
        }
    }

    fun mergeReadProjectsResponse(upstreamObj: JsonObject, requestedIds: Set<String> = emptySet()): JsonObject {
        cacheReadProjectsResponse(upstreamObj)
        return synchronized(lock) {
            val incoming = upstreamObj["projects"].asArrOrNull().orEmpty()
            val byKey = linkedMapOf<String, JsonElement>()
            for (el in incoming) {
                val k = extractProjectCacheKey(el) ?: continue
                byKey[k] = el
            }
            val cachedArr = state.cachedReadProjectsJson?.let { raw ->
                runCatching { json.parseToJsonElement(raw).asObjOrNull()?.get("projects").asArrOrNull() }.getOrNull()
            }.orEmpty()
            for (el in cachedArr) {
                val k = extractProjectCacheKey(el) ?: continue
                if (!byKey.containsKey(k) && (requestedIds.isEmpty() || k in requestedIds)) {
                    byKey[k] = el
                }
            }
            val upstreamNotFound = upstreamObj["notFoundIds"].asArrOrNull()
                ?.mapNotNull { it.asStrOrNull() }
                ?.filter { it !in byKey.keys }
                .orEmpty()
            buildJsonObject {
                upstreamObj.forEach { (k, v) ->
                    when (k) {
                        "projects" -> put("projects", buildJsonArray { byKey.values.forEach { add(it) } })
                        "notFoundIds" -> {
                            if (upstreamNotFound.isNotEmpty()) {
                                put("notFoundIds", buildJsonArray { upstreamNotFound.forEach { add(JsonPrimitive(it)) } })
                            }
                        }
                        else -> put(k, v)
                    }
                }
                if (!upstreamObj.containsKey("projects")) {
                    put("projects", buildJsonArray { byKey.values.forEach { add(it) } })
                }
            }
        }
    }

    fun getCachedReadProjectsResponse(requestedIds: Set<String> = emptySet()): JsonObject? = synchronized(lock) {
        val cached = state.cachedReadProjectsJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
        } ?: return@synchronized null
        if (requestedIds.isEmpty()) return@synchronized cached
        val projects = cached["projects"].asArrOrNull() ?: return@synchronized cached
        val filtered = projects.filter { el ->
            val key = extractProjectCacheKey(el)
            key != null && key in requestedIds
        }
        if (filtered.isEmpty()) return@synchronized null
        buildJsonObject {
            cached.forEach { (k, v) ->
                if (k == "projects") {
                    put("projects", buildJsonArray { filtered.forEach { add(it) } })
                } else {
                    put(k, v)
                }
            }
        }
    }

    fun cacheAllSummariesMap(summariesMap: JsonObject) {
        if (summariesMap.isEmpty()) return
        synchronized(lock) {
            val prevMap = state.cachedAllSummariesJson?.let { raw ->
                runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
            }
            val merged = buildJsonObject {
                prevMap?.forEach { (k, v) -> if (k !in state.deletedIds && !summariesMap.containsKey(k)) put(k, v) }
                summariesMap.forEach { (k, v) -> if (k !in state.deletedIds) put(k, v) }
            }
            val encoded = merged.toString()
            if (state.cachedAllSummariesJson != encoded) {
                state = state.copy(cachedAllSummariesJson = encoded)
                persistToDisk()
            }
        }
    }

    fun getCachedAllSummariesMap(): JsonObject? = synchronized(lock) {
        state.cachedAllSummariesJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
        }?.let { map ->
            if (state.deletedIds.isEmpty()) map
            else buildJsonObject { map.forEach { (k, v) -> if (k !in state.deletedIds) put(k, v) } }
        }
    }

    fun recordStartedZeroProject(cascadeId: String, initialTitle: String = "New Conversation") {
        if (cascadeId.isBlank()) return
        synchronized(lock) {
            if (cascadeId in state.deletedIds) return
            val existing = state.entries[cascadeId]
            if (existing == null) {
                val now = Instant.now().toString()
                state.entries[cascadeId] = CachedEntry(
                    cascadeId = cascadeId,
                    summary = initialTitle.ifBlank { "New Conversation" },
                    projectId = OUTSIDE_OF_PROJECT_ID,
                    stepCount = 0,
                    createdTimeIso = now,
                    lastModifiedTimeIso = now
                )
                persistToDisk()
            }
        }
    }

    fun updateFromSummaryObject(cascadeId: String, summaryObj: JsonObject) {
        if (cascadeId.isBlank()) return
        synchronized(lock) {
            if (cascadeId in state.deletedIds) return
            if (!isZeroProjectSummary(summaryObj)) {
                // If conversation later got moved into a real project, stop forcing it into zero-project cache
                if (state.entries.remove(cascadeId) != null) {
                    persistToDisk()
                }
                return
            }
            val now = Instant.now().toString()
            val summaryTitle = summaryObj["summary"].asStrOrNull()?.takeIf { it.isNotBlank() }
                ?: state.entries[cascadeId]?.summary
                ?: "Untitled Conversation"
            val stepCount = summaryObj["stepCount"].asIntOrNull()
                ?: state.entries[cascadeId]?.stepCount
                ?: 0
            val createdTime = summaryObj["createdTime"].asStrOrNull()
                ?: state.entries[cascadeId]?.createdTimeIso?.takeIf { it.isNotBlank() }
                ?: now
            val lastMod = summaryObj["lastModifiedTime"].asStrOrNull() ?: now

            val existing = state.entries[cascadeId]
            val normalizedSummary = normalizeIdleSummary(summaryObj, summaryTitle, createdTime, lastMod)
            state.entries[cascadeId] = CachedEntry(
                cascadeId = cascadeId,
                summary = summaryTitle,
                projectId = OUTSIDE_OF_PROJECT_ID,
                stepCount = maxOf(stepCount, existing?.stepCount ?: 0),
                createdTimeIso = createdTime,
                lastModifiedTimeIso = lastMod,
                summaryJson = normalizedSummary.toString(),
                trajectoryJson = existing?.trajectoryJson,
                stepsJson = existing?.stepsJson
            )
            persistToDisk()
        }
    }

    fun updateFromSummariesMap(summariesMap: JsonObject) {
        synchronized(lock) {
            var changed = false
            for ((cascadeId, elem) in summariesMap) {
                val obj = elem.asObjOrNull() ?: continue
                if (cascadeId in state.deletedIds) continue
                if (!isZeroProjectSummary(obj)) {
                    if (state.entries.remove(cascadeId) != null) changed = true
                    continue
                }
                val now = Instant.now().toString()
                val summaryTitle = obj["summary"].asStrOrNull()?.takeIf { it.isNotBlank() }
                    ?: state.entries[cascadeId]?.summary
                    ?: "Untitled Conversation"
                val stepCount = obj["stepCount"].asIntOrNull() ?: 0
                val createdTime = obj["createdTime"].asStrOrNull()
                    ?: state.entries[cascadeId]?.createdTimeIso?.takeIf { it.isNotBlank() }
                    ?: now
                val lastMod = obj["lastModifiedTime"].asStrOrNull() ?: now
                val existing = state.entries[cascadeId]
                val normalizedSummary = normalizeIdleSummary(obj, summaryTitle, createdTime, lastMod)
                state.entries[cascadeId] = CachedEntry(
                    cascadeId = cascadeId,
                    summary = summaryTitle,
                    projectId = OUTSIDE_OF_PROJECT_ID,
                    stepCount = maxOf(stepCount, existing?.stepCount ?: 0),
                    createdTimeIso = createdTime,
                    lastModifiedTimeIso = lastMod,
                    summaryJson = normalizedSummary.toString(),
                    trajectoryJson = existing?.trajectoryJson,
                    stepsJson = existing?.stepsJson
                )
                changed = true
            }
            if (changed) persistToDisk()
        }
    }

    fun updateFromTrajectoryResponse(cascadeId: String, respObj: JsonObject) {
        if (cascadeId.isBlank()) return
        synchronized(lock) {
            if (cascadeId in state.deletedIds) return
            val existing = state.entries[cascadeId]
            val trajObj = respObj["trajectory"].asObjOrNull()
            val metaProj = trajObj?.get("metadata").asObjOrNull()?.get("projectId").asStrOrNull()
            val hasWorkspaces = trajObj?.get("metadata").asObjOrNull()?.get("workspaces").asArrOrNull()?.isNotEmpty() == true
            val isZero = existing != null ||
                ((metaProj.isNullOrBlank() || metaProj == OUTSIDE_OF_PROJECT_ID) && !hasWorkspaces)
            if (!isZero) return

            val stepsCount = respObj["numTotalSteps"].asIntOrNull()
                ?: trajObj?.get("steps").asArrOrNull()?.size
                ?: existing?.stepCount
                ?: 0
            val idleResp = buildJsonObject {
                respObj.forEach { (k, v) ->
                    if (k == "status") put(k, "CASCADE_RUN_STATUS_IDLE") else put(k, v)
                }
                if (!respObj.containsKey("status")) put("status", "CASCADE_RUN_STATUS_IDLE")
            }
            val now = Instant.now().toString()
            state.entries[cascadeId] = (existing ?: CachedEntry(
                cascadeId = cascadeId,
                createdTimeIso = now,
                lastModifiedTimeIso = now
            )).copy(
                stepCount = maxOf(stepsCount, existing?.stepCount ?: 0),
                lastModifiedTimeIso = now,
                trajectoryJson = idleResp.toString()
            )
            persistToDisk()
        }
    }

    fun updateFromStepsResponse(cascadeId: String, respObj: JsonObject) {
        if (cascadeId.isBlank()) return
        synchronized(lock) {
            if (cascadeId in state.deletedIds) return
            val existing = state.entries[cascadeId] ?: return
            val rawStepsArr = respObj["steps"].asArrOrNull() ?: return
            if (rawStepsArr.isEmpty() && existing.stepsJson != null) return
            val now = Instant.now().toString()
            val fallbackIso = existing.createdTimeIso.ifBlank { now }
            val normalizedSteps = JsonArray(rawStepsArr.mapNotNull { normalizeStepToProtoJson(it, fallbackIso) })
            val extractedTitle = if (existing.summary == "New Conversation" || existing.summary == "Untitled Conversation") {
                rawStepsArr.firstNotNullOfOrNull { el ->
                    el.asObjOrNull()?.let { extractUserTextFromStep(it) }
                }?.trim()?.take(60)?.takeIf { it.isNotBlank() } ?: existing.summary
            } else {
                existing.summary
            }
            val normalizedResp = buildJsonObject {
                put("steps", normalizedSteps)
            }
            state.entries[cascadeId] = existing.copy(
                summary = extractedTitle,
                stepCount = maxOf(normalizedSteps.size, existing.stepCount),
                lastModifiedTimeIso = now,
                stepsJson = normalizedResp.toString()
            )
            persistToDisk()
        }
    }

    fun mergeIntoSummariesMap(existingMap: JsonObject): JsonObject = synchronized(lock) {
        val missingEntries = state.entries.values.filter { entry ->
            entry.cascadeId !in state.deletedIds &&
                !existingMap.containsKey(entry.cascadeId) &&
                (entry.stepCount > 0 || entry.stepsJson != null || entry.trajectoryJson != null || entry.summary != "New Conversation")
        }
        if (missingEntries.isEmpty()) return existingMap
        return buildJsonObject {
            existingMap.forEach { (k, v) -> put(k, v) }
            for (entry in missingEntries) {
                val cachedSummaryObj = entry.summaryJson?.let { raw ->
                    runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
                }
                val resolved = if (cachedSummaryObj != null) {
                    normalizeIdleSummary(
                        cachedSummaryObj,
                        entry.summary,
                        entry.createdTimeIso.ifBlank { Instant.now().toString() },
                        entry.lastModifiedTimeIso.ifBlank { Instant.now().toString() }
                    )
                } else {
                    buildSummaryObject(entry)
                }
                put(entry.cascadeId, resolved)
            }
        }
    }

    fun getCachedTrajectoryResponse(cascadeId: String): JsonObject? = synchronized(lock) {
        val entry = state.entries[cascadeId]?.takeIf { it.cascadeId !in state.deletedIds } ?: return null
        entry.trajectoryJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
        }?.let { return it }

        val stepsArr = parseCachedStepsArray(entry) ?: JsonArray(emptyList())
        return buildJsonObject {
            put("trajectory", buildJsonObject {
                put("trajectoryId", "traj-${entry.cascadeId}")
                put("cascadeId", entry.cascadeId)
                put("steps", stepsArr)
                put("metadata", buildJsonObject {
                    put("projectId", OUTSIDE_OF_PROJECT_ID)
                    put("createdAt", entry.createdTimeIso.ifBlank { Instant.now().toString() })
                })
            })
            put("status", "CASCADE_RUN_STATUS_IDLE")
            put("numTotalSteps", stepsArr.size)
        }
    }

    fun getCachedStepsResponse(cascadeId: String, stepOffset: Int = 0): JsonObject? = synchronized(lock) {
        val entry = state.entries[cascadeId]?.takeIf { it.cascadeId !in state.deletedIds } ?: return null
        val stepsArr = parseCachedStepsArray(entry) ?: return null
        val start = stepOffset.coerceAtLeast(0).coerceAtMost(stepsArr.size)
        return buildJsonObject {
            put("steps", JsonArray(stepsArr.subList(start, stepsArr.size)))
        }
    }

    fun cacheAgentStateSnapshot(cascadeId: String, responseObj: JsonObject) {
        if (cascadeId.isBlank()) return
        val updateObj = responseObj["update"].asObjOrNull() ?: return
        val steps = updateObj["mainTrajectoryUpdate"].asObjOrNull()
            ?.get("stepsUpdate").asObjOrNull()
            ?.get("steps").asArrOrNull()
        if (steps == null || steps.isEmpty()) return
        synchronized(lock) {
            if (cascadeId in state.deletedIds) return
            val encoded = responseObj.toString()
            if (encoded.length > 2 * 1024 * 1024) return
            if (state.cachedAgentStateSnapshots[cascadeId] == encoded) return
            if (state.cachedAgentStateSnapshots.size >= 25 && !state.cachedAgentStateSnapshots.containsKey(cascadeId)) {
                val oldestKey = state.cachedAgentStateSnapshots.keys.firstOrNull()
                if (oldestKey != null) state.cachedAgentStateSnapshots.remove(oldestKey)
            }
            state.cachedAgentStateSnapshots[cascadeId] = encoded
            persistToDisk()
        }
    }

    fun getCachedStreamSnapshotResponse(cascadeId: String): JsonObject? = synchronized(lock) {
        if (cascadeId in state.deletedIds) return@synchronized null
        state.cachedAgentStateSnapshots[cascadeId]?.let { raw ->
            runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
        }?.let { return@synchronized it }
        val entry = state.entries[cascadeId] ?: return@synchronized null
        val stepsArr = parseCachedStepsArray(entry) ?: JsonArray(emptyList())
        val turns = buildJsonArray {
            var turnIdx = 0
            stepsArr.forEachIndexed { idx, elem ->
                val stepObj = elem.asObjOrNull() ?: return@forEachIndexed
                if (isUserInputStep(stepObj)) {
                    add(buildJsonObject {
                        put("index", turnIdx++)
                        put("startStepIndex", idx)
                        put("startTime", entry.createdTimeIso.ifBlank { Instant.now().toString() })
                    })
                }
            }
        }
        return buildJsonObject {
            put("update", buildJsonObject {
                put("conversationId", entry.cascadeId)
                put("trajectoryId", "traj-${entry.cascadeId}")
                put("status", "CASCADE_RUN_STATUS_IDLE")
                put("fullyIdle", true)
                put("turnsUpdate", buildJsonObject {
                    put("totalLength", turns.size)
                    put("turns", turns)
                })
                put("mainTrajectoryUpdate", buildJsonObject {
                    put("stepsUpdate", buildJsonObject {
                        put("indices", buildJsonArray {
                            for (i in 0 until stepsArr.size) add(JsonPrimitive(i))
                        })
                        put("steps", stepsArr)
                        put("totalLength", stepsArr.size)
                        put("pageBounds", buildJsonObject {
                            put("startIndex", 0)
                            put("endIndexExclusive", stepsArr.size)
                        })
                    })
                })
            })
        }
    }

    private fun parseCachedStepsArray(entry: CachedEntry): JsonArray? {
        val fallbackIso = entry.createdTimeIso.ifBlank { Instant.now().toString() }
        entry.stepsJson?.let { raw ->
            val obj = runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
            val arr = obj?.get("steps").asArrOrNull()
            if (arr != null) {
                return JsonArray(arr.mapNotNull { normalizeStepToProtoJson(it, fallbackIso) })
            }
        }
        entry.trajectoryJson?.let { raw ->
            val obj = runCatching { json.parseToJsonElement(raw).asObjOrNull() }.getOrNull()
            val arr = obj?.get("trajectory").asObjOrNull()?.get("steps").asArrOrNull()
            if (arr != null) {
                return JsonArray(arr.mapNotNull { normalizeStepToProtoJson(it, fallbackIso) })
            }
        }
        return null
    }

    private fun buildSummaryObject(entry: CachedEntry): JsonObject {
        val now = Instant.now().toString()
        return buildJsonObject {
            put("summary", entry.summary)
            put("stepCount", entry.stepCount)
            put("lastModifiedTime", entry.lastModifiedTimeIso.ifBlank { now })
            put("createdTime", entry.createdTimeIso.ifBlank { now })
            put("trajectoryId", "traj-${entry.cascadeId}")
            put("status", "CASCADE_RUN_STATUS_IDLE")
            put("trajectoryMetadata", buildJsonObject {
                put("projectId", OUTSIDE_OF_PROJECT_ID)
                put("workspaces", buildJsonArray {})
            })
            put("workspaces", buildJsonArray {})
        }
    }

    private fun normalizeIdleSummary(
        raw: JsonObject,
        fallbackTitle: String,
        createdTime: String,
        lastMod: String
    ): JsonObject = buildJsonObject {
        raw.forEach { (k, v) ->
            when (k) {
                "status" -> put(k, "CASCADE_RUN_STATUS_IDLE")
                else -> put(k, v)
            }
        }
        if (!raw.containsKey("summary")) put("summary", fallbackTitle)
        if (!raw.containsKey("status")) put("status", "CASCADE_RUN_STATUS_IDLE")
        if (!raw.containsKey("createdTime")) put("createdTime", createdTime)
        if (!raw.containsKey("lastModifiedTime")) put("lastModifiedTime", lastMod)
        if (!raw.containsKey("trajectoryMetadata")) {
            put("trajectoryMetadata", buildJsonObject {
                put("projectId", OUTSIDE_OF_PROJECT_ID)
            })
        }
    }
}
