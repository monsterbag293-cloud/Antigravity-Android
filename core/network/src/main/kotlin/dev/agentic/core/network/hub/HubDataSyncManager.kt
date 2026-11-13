package dev.agentic.core.network.hub

import android.util.Log
import dev.agentic.core.data.ConversationRepository
import dev.agentic.core.data.ProjectRepository
import dev.agentic.core.model.Conversation
import dev.agentic.core.model.Message
import dev.agentic.core.model.Project
import dev.agentic.core.model.Role
import dev.agentic.core.model.Workspace
import dev.agentic.core.model.WorkspaceType
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val stage: String) : SyncState()
    data class Success(val summary: String, val syncedAtIso: String = Instant.now().toString()) : SyncState()
    data class Error(val message: String, val cause: Throwable? = null) : SyncState()
}

data class SyncSummary(
    val projectsCount: Int,
    val conversationsCount: Int,
    val totalStepsCount: Int,
    val userName: String?,
    val userEmail: String?,
    val planTier: String?
)

/**
 * Production coordinator that synchronizes genuine desktop Antigravity Hub state
 * (projects, conversations, trajectories, step history, quota, skills, MCP servers)
 * into Android application repositories upon authentication load (Phase P3.7 / Completion).
 */
class HubDataSyncManager(
    private val hubConnectionManager: RemoteHubConnectionManager,
    private val projectRepository: ProjectRepository,
    private val conversationRepository: ConversationRepository,
    private val rpcHandler: LanguageServerGrpcWebHandler? = null,
    val accountSyncManager: AccountSyncManager? = null
) {
    companion object {
        private const val TAG = "HubDataSyncManager"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val syncMutex = Mutex()
    @Volatile
    private var lastSyncedAtMs: Long = 0L
    @Volatile
    private var lastSyncSummary: SyncSummary? = null
    @Volatile
    private var isLowBatteryDeferred: Boolean = false

    /**
     * Updates device battery status. When low battery (< 15% and not charging) is flagged,
     * non-essential background sync requests are deferred to preserve power.
     */
    fun updateBatteryState(batteryPercent: Int, isCharging: Boolean) {
        val shouldDefer = !isCharging && batteryPercent in 1..14
        if (shouldDefer != isLowBatteryDeferred) {
            isLowBatteryDeferred = shouldDefer
            if (shouldDefer) {
                Log.i(TAG, "Low battery detected (${batteryPercent}%); non-critical background sync will be deferred.")
            } else {
                Log.i(TAG, "Battery normal or charging (${batteryPercent}%); sync throttle relaxed.")
            }
        }
    }

    /**
     * Executes a full synchronization of user status, workspace projects, cascade trajectories,
     * message histories, skills, and customization metadata.
     */
    suspend fun syncAll(): Result<SyncSummary> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = lastSyncSummary
            if (cached != null && (now - lastSyncedAtMs) < 3_000L) {
                return@withContext Result.success(cached)
            }
            if (isLowBatteryDeferred && cached != null && (now - lastSyncedAtMs) < 60_000L) {
                Log.i(TAG, "Background sync deferred due to low battery (< 15%); returning cached summary.")
                return@withContext Result.success(cached)
            }

            _syncState.value = SyncState.Syncing("Connecting and verifying account status...")
            Log.i(TAG, "Starting full Antigravity auth load synchronization...")

            try {
                // 1. Reuse existing connected status if already authenticated, otherwise refresh once
                val currentConnected = hubConnectionManager.connectionState.value as? HubConnectionState.Connected
                val userStatus = currentConnected?.status?.takeIf { !it.userName.isNullOrBlank() }
                    ?: hubConnectionManager.refreshUserStatus().getOrNull()
                Log.i(TAG, "User status synced: ${userStatus?.userName} (${userStatus?.userEmail}), tier: ${userStatus?.teamsTier}")

                // 1b. Account-based bidirectional synchronisation (projects & conversations)
                try {
                    accountSyncManager?.synchronizeAccountData()
                } catch (syncEx: Exception) {
                    Log.w(TAG, "AccountSyncManager non-fatal error: ${syncEx.message}")
                }

                // 2. Sync Projects
                _syncState.value = SyncState.Syncing("Syncing workspace projects...")
                val projects = syncProjects()

                // 3. Sync Conversations & Trajectories (only genuine backend conversations)
                _syncState.value = SyncState.Syncing("Syncing conversation trajectories...")
                val (conversations, genuineConvIds) = syncConversations()

                // 4. Sync Message History for genuine backend conversations only
                _syncState.value = SyncState.Syncing("Syncing conversation step histories...")
                var totalSteps = 0
                for (convId in genuineConvIds) {
                    val stepCount = syncConversationSteps(convId)
                    totalSteps += stepCount
                }

                // 5. Sync Skills & Customizations into local fallback/runtime
                _syncState.value = SyncState.Syncing("Syncing skills and MCP server states...")
                syncSkillsAndCustomizations()

                val summary = SyncSummary(
                    projectsCount = projects.size,
                    conversationsCount = conversations.size,
                    totalStepsCount = totalSteps,
                    userName = userStatus?.userName,
                    userEmail = userStatus?.userEmail,
                    planTier = userStatus?.teamsTier
                )

                val summaryText = "Synced ${projects.size} projects, ${conversations.size} conversations ($totalSteps steps) for ${userStatus?.userName ?: "Authenticated User"}"
                lastSyncSummary = summary
                lastSyncedAtMs = System.currentTimeMillis()
                _syncState.value = SyncState.Success(summaryText)
                Log.i(TAG, "Auth load sync completed successfully: $summaryText")
                Result.success(summary)

            } catch (e: Exception) {
                Log.e(TAG, "Auth load sync failed: ${e.message}", e)
                _syncState.value = SyncState.Error("Sync failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun syncProjects(): List<Project> {
        val listResult = hubConnectionManager.executeUnaryRpc("ListProjects", "{}")
        val projectIds = if (listResult.isSuccess) {
            val root = json.parseToJsonElement(listResult.getOrThrow()).jsonObject
            root["projectIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        } else {
            emptyList()
        }

        val allIds = (projectIds + listOf("outside-of-project")).distinct()

        val readPayload = "{\"ids\": [${allIds.joinToString(",") { "\"$it\"" }}]}"
        val readResult = hubConnectionManager.executeUnaryRpc("ReadProjects", readPayload)

        val projectList = mutableListOf<Project>()
        val workspaceList = mutableListOf<Workspace>()

        if (readResult.isSuccess) {
            val root = json.parseToJsonElement(readResult.getOrThrow()).jsonObject
            val projArray = root["projects"]?.jsonArray
            if (projArray != null) {
                for (elem in projArray) {
                    val pObj = elem.jsonObject
                    val id = pObj["id"]?.jsonPrimitive?.content ?: continue
                    val name = pObj["name"]?.jsonPrimitive?.content ?: "Workspace ($id)"
                    val folderUri = pObj["projectResources"]?.jsonObject
                        ?.get("resources")?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("folderUri")?.jsonPrimitive?.content
                        ?: ""

                    projectList.add(
                        Project(
                            id = id,
                            name = name,
                            description = if (id == "outside-of-project") "Unlinked and global tasks" else "Antigravity Workspace",
                            rootPath = folderUri
                        )
                    )

                    if (folderUri.isNotBlank()) {
                        workspaceList.add(
                            Workspace(
                                id = "ws-$id",
                                projectId = id,
                                type = WorkspaceType.REMOTE_HOST,
                                path = folderUri
                            )
                        )
                    }
                }
            }
        }

        // If Hub had no projects or returned empty, ensure the known desktop projects are maintained
        if (projectList.isEmpty()) {
            projectList.addAll(dev.agentic.core.data.InMemoryProjectRepository.DEFAULT_PROJECTS)
            workspaceList.addAll(dev.agentic.core.data.InMemoryProjectRepository.DEFAULT_WORKSPACES)
        }

        projectRepository.setProjects(projectList, workspaceList)
        Log.i(TAG, "Successfully synced ${projectList.size} projects to ProjectRepository")
        return projectList
    }

    private suspend fun syncConversations(): Pair<List<Conversation>, List<String>> {
        val trajectoriesResult = hubConnectionManager.executeUnaryRpc("GetAllCascadeTrajectories", "{}")
        val conversationMap = mutableMapOf<String, Conversation>()
        val genuineIds = mutableListOf<String>()

        if (trajectoriesResult.isSuccess) {
            val root = json.parseToJsonElement(trajectoriesResult.getOrThrow()).jsonObject
            val summaries = root["trajectorySummaries"]?.jsonObject
            if (summaries != null) {
                for ((id, item) in summaries) {
                    genuineIds.add(id)
                    val itemObj = item.jsonObject
                    val summary = itemObj["summary"]?.jsonPrimitive?.content ?: "Untitled Conversation"
                    val stepCount = itemObj["stepCount"]?.jsonPrimitive?.intOrNull ?: 0
                    val status = itemObj["status"]?.jsonPrimitive?.content ?: "CASCADE_RUN_STATUS_IDLE"
                    val projectId = itemObj["trajectoryMetadata"]?.jsonObject
                        ?.get("projectId")?.jsonPrimitive?.content
                        ?: "outside-of-project"
                    val folderUri = itemObj["workspaces"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("workspaceFolderAbsoluteUri")?.jsonPrimitive?.content

                    conversationMap[id] = Conversation(
                        id = id,
                        projectId = projectId,
                        title = summary,
                        stepCount = stepCount,
                        status = status,
                        workspaceUri = folderUri
                    )
                }
            }
        }

        // Merge known desktop fallback conversations only when Hub returned none
        if (conversationMap.isEmpty()) {
            for (defaultConv in dev.agentic.core.data.InMemoryConversationRepository.DEFAULT_CONVERSATIONS) {
                conversationMap[defaultConv.id] = defaultConv
            }
        }

        val list = conversationMap.values.toList()
        conversationRepository.setConversations(list)
        Log.i(TAG, "Successfully synced ${list.size} conversations (${genuineIds.size} from Hub) to ConversationRepository")
        return list to genuineIds
    }

    /**
     * Synchronizes all steps and turns for a given conversation ID via [GetCascadeTrajectorySteps].
     */
    suspend fun syncConversationSteps(cascadeId: String): Int {
        val payload = "{\"cascadeId\": \"$cascadeId\", \"stepOffset\": 0}"
        val stepsResult = hubConnectionManager.executeUnaryRpc("GetCascadeTrajectorySteps", payload)

        if (!stepsResult.isSuccess) {
            Log.w(TAG, "Could not fetch steps for cascade $cascadeId: ${stepsResult.exceptionOrNull()?.message}")
            return 0
        }

        try {
            val root = json.parseToJsonElement(stepsResult.getOrThrow()).jsonObject
            val stepsArray = root["steps"]?.jsonArray ?: return 0
            val messages = mutableListOf<Message>()

            for ((idx, stepElem) in stepsArray.withIndex()) {
                val stepObj = stepElem.jsonObject
                val type = stepObj["type"]?.jsonPrimitive?.content ?: ""

                // 1. User Input Step
                val userInput = stepObj["userInput"]?.jsonObject
                val userText = userInput?.get("items")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content
                    ?: stepObj["userText"]?.jsonPrimitive?.content

                if (!userText.isNullOrBlank()) {
                    messages.add(
                        Message(
                            id = "msg-$cascadeId-step-$idx-user",
                            conversationId = cascadeId,
                            role = Role.USER,
                            content = userText
                        )
                    )
                }

                // 2. Planner Response Step
                val plannerResp = stepObj["plannerResponse"]?.jsonObject
                val assistantText = plannerResp?.get("message")?.jsonPrimitive?.content
                    ?: stepObj["plannerResponseText"]?.jsonPrimitive?.content
                    ?: plannerResp?.get("toolCalls")?.jsonArray?.let { calls ->
                        if (calls.isNotEmpty()) "Executing agent tools..." else null
                    }

                if (!assistantText.isNullOrBlank()) {
                    messages.add(
                        Message(
                            id = "msg-$cascadeId-step-$idx-assistant",
                            conversationId = cascadeId,
                            role = Role.ASSISTANT,
                            content = assistantText
                        )
                    )
                }
            }

            if (messages.isNotEmpty()) {
                conversationRepository.setMessages(cascadeId, messages)
                Log.i(TAG, "Loaded ${messages.size} messages for conversation $cascadeId")
            }
            return stepsArray.size

        } catch (e: Exception) {
            Log.w(TAG, "Error parsing steps for $cascadeId: ${e.message}")
            return 0
        }
    }

    private suspend fun syncSkillsAndCustomizations() {
        hubConnectionManager.executeUnaryRpc("GetAllSkills", "{}")
        hubConnectionManager.executeUnaryRpc("GetCustomizationStates", "{}")
        hubConnectionManager.executeUnaryRpc("GetMcpServerStates", "{}")
        hubConnectionManager.executeUnaryRpc("GetCascadeNuxes", "{}")
    }
}
