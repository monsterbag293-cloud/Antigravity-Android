package dev.agentic.core.network.hub

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val deletedIds: MutableSet<String> = mutableSetOf()
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
                json.decodeFromString<CacheState>(cacheFile.readText(Charsets.UTF_8))
            } else {
                CacheState()
            }
        } catch (_: Exception) {
            CacheState()
        }
    }

    private fun persistToDisk() {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(json.encodeToString(state), Charsets.UTF_8)
            if (cacheFile.exists()) cacheFile.delete()
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun isZeroProjectSummary(summaryObj: JsonObject): Boolean {
        val metaProj = summaryObj["trajectoryMetadata"]?.jsonObject
            ?.get("projectId")?.jsonPrimitive?.content
            ?.trim()
        val workspaces = summaryObj["workspaces"]?.jsonArray
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
            state.deletedIds.add(cascadeId)
            persistToDisk()
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
            val summaryTitle = summaryObj["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: state.entries[cascadeId]?.summary
                ?: "Untitled Conversation"
            val stepCount = summaryObj["stepCount"]?.jsonPrimitive?.intOrNull
                ?: state.entries[cascadeId]?.stepCount
                ?: 0
            val createdTime = summaryObj["createdTime"]?.jsonPrimitive?.content
                ?: state.entries[cascadeId]?.createdTimeIso?.takeIf { it.isNotBlank() }
                ?: now
            val lastMod = summaryObj["lastModifiedTime"]?.jsonPrimitive?.content ?: now

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
                val obj = runCatching { elem.jsonObject }.getOrNull() ?: continue
                if (cascadeId in state.deletedIds) continue
                if (!isZeroProjectSummary(obj)) {
                    if (state.entries.remove(cascadeId) != null) changed = true
                    continue
                }
                val now = Instant.now().toString()
                val summaryTitle = obj["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: state.entries[cascadeId]?.summary
                    ?: "Untitled Conversation"
                val stepCount = obj["stepCount"]?.jsonPrimitive?.intOrNull ?: 0
                val createdTime = obj["createdTime"]?.jsonPrimitive?.content
                    ?: state.entries[cascadeId]?.createdTimeIso?.takeIf { it.isNotBlank() }
                    ?: now
                val lastMod = obj["lastModifiedTime"]?.jsonPrimitive?.content ?: now
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
            val trajObj = respObj["trajectory"]?.jsonObject
            val metaProj = trajObj?.get("metadata")?.jsonObject?.get("projectId")?.jsonPrimitive?.content
            val hasWorkspaces = trajObj?.get("metadata")?.jsonObject?.get("workspaces")?.jsonArray?.isNotEmpty() == true
            val isZero = existing != null ||
                ((metaProj.isNullOrBlank() || metaProj == OUTSIDE_OF_PROJECT_ID) && !hasWorkspaces)
            if (!isZero) return

            val stepsCount = respObj["numTotalSteps"]?.jsonPrimitive?.intOrNull
                ?: trajObj?.get("steps")?.jsonArray?.size
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
            val stepsArr = respObj["steps"]?.jsonArray ?: return
            if (stepsArr.isEmpty() && existing.stepsJson != null) return
            val extractedTitle = if (existing.summary == "New Conversation" || existing.summary == "Untitled Conversation") {
                stepsArr.firstOrNull()?.jsonObject?.get("userInput")?.jsonObject
                    ?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content?.trim()?.take(60)
                    ?.takeIf { it.isNotBlank() }
                    ?: existing.summary
            } else {
                existing.summary
            }
            state.entries[cascadeId] = existing.copy(
                summary = extractedTitle,
                stepCount = maxOf(stepsArr.size, existing.stepCount),
                lastModifiedTimeIso = Instant.now().toString(),
                stepsJson = respObj.toString()
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
                    runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
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
            runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
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

    fun getCachedStreamSnapshotResponse(cascadeId: String): JsonObject? = synchronized(lock) {
        val entry = state.entries[cascadeId]?.takeIf { it.cascadeId !in state.deletedIds } ?: return null
        val stepsArr = parseCachedStepsArray(entry) ?: JsonArray(emptyList())
        val turns = buildJsonArray {
            var turnIdx = 0
            stepsArr.forEachIndexed { idx, elem ->
                val stepObj = elem.jsonObject
                val type = stepObj["type"]?.jsonPrimitive?.content.orEmpty()
                if (type == "CORTEX_STEP_TYPE_USER_INPUT" || stepObj.containsKey("userInput")) {
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
        entry.stepsJson?.let { raw ->
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val arr = obj?.get("steps")?.jsonArray
            if (arr != null) return arr
        }
        entry.trajectoryJson?.let { raw ->
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val arr = obj?.get("trajectory")?.jsonObject?.get("steps")?.jsonArray
            if (arr != null) return arr
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
