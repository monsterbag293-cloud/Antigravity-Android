package dev.agentic.core.network.jetski

import dev.agentic.agent.runtime.AgentExecutionEvent
import dev.agentic.agent.runtime.AgentRuntime
import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import dev.agentic.core.model.ToolCall
import dev.agentic.core.network.GeminiModelProvider
import dev.agentic.core.network.hub.BackendMode
import dev.agentic.core.network.hub.DesktopModelCatalog
import dev.agentic.core.network.hub.RemoteHubProxyHandler
import dev.agentic.model.ModelProvider
import dev.agentic.tools.local.LocalFileTools
import dev.agentic.tools.local.WorkspacePathResolver
import dev.agentic.tools.spi.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class GrpcWebHttpRequest(
    val path: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0)
)

data class GrpcWebHttpResponse(
    val statusCode: Int = 200,
    val contentType: String = GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
    val headers: Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-cache"
    ),
    val body: ByteArray = ByteArray(0),
    val bodyStream: java.io.InputStream? = null
) {
    fun openStream(): java.io.InputStream = bodyStream ?: java.io.ByteArrayInputStream(body)
}

data class TurnFileDiffEntry(
    val fileUri: String,
    val originalContents: String,
    var modifiedContents: String,
    var additions: Int,
    var deletions: Int,
    val isArtifactFile: Boolean = false
)

data class CascadeStepRecord(
    val stepIndex: Int,
    val type: String,
    val status: String,
    val createdAtIso: String,
    val completedAtIso: String? = null,
    val userText: String? = null,
    val plannerResponseText: String? = null,
    val plannerThinkingText: String? = null,
    val plannerToolCalls: List<ToolCall> = emptyList(),
    val toolCall: ToolCall? = null,
    val toolSummary: String? = null,
    val toolAction: String? = null,
    val structuredData: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)

data class CascadeTrajectoryRecord(
    val cascadeId: String,
    val trajectoryId: String,
    val projectId: String,
    var summary: String,
    var status: String = "CASCADE_RUN_STATUS_IDLE",
    var fullyIdle: Boolean = true,
    val createdTimeIso: String = Instant.now().toString(),
    var lastModifiedTimeIso: String = Instant.now().toString(),
    val steps: CopyOnWriteArrayList<CascadeStepRecord> = CopyOnWriteArrayList(),
    val conversationHistory: CopyOnWriteArrayList<Message> = CopyOnWriteArrayList(),
    val turnFileDiffs: ConcurrentHashMap<Int, ConcurrentHashMap<String, TurnFileDiffEntry>> = ConcurrentHashMap(),
    val incrementalUpdates: CopyOnWriteArrayList<JsonObject> = CopyOnWriteArrayList()
)

data class CascadeProjectRecord(
    val id: String,
    val name: String,
    val workspaceUri: String,
    val updatedAtIso: String = Instant.now().toString(),
    val conversationIds: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
)

/**
 * In-process Connect-RPC / gRPC-Web handler for `exa.language_server_pb.LanguageServerService`
 * (Stage P0.5-B & P1.2 Multi-Step Agent Execution Engine).
 *
 * Uses the 96 decoded `FileDescriptorProto` definitions from `main.js` as its schema source of truth.
 * Keeps authentication credentials strictly in native Android storage (`isAuthenticatedProvider` / `ModelProvider`).
 */
class LanguageServerGrpcWebHandler(
    private val modelProvider: ModelProvider,
    private val isAuthenticatedProvider: () -> Boolean,
    private val csrfToken: String,
    private val enforceCsrf: Boolean = true,
    private val defaultTimeoutMs: Long = 120_000L,
    private val workspaceRootDir: File = File(System.getProperty("java.io.tmpdir"), "antigravity-workspace"),
    private val maxAgentIterations: Int = AgentRuntime.DEFAULT_MAX_ITERATIONS,
    private val extraToolProviders: List<ToolProvider> = emptyList(),
    private val agentTurnTimeoutMs: Long = 900_000L,
    private val onConversationUpdated: ((cascadeId: String, title: String, messages: List<Message>) -> Unit)? = null,
    val hubProxyHandler: RemoteHubProxyHandler? = null,
    initialBackendMode: BackendMode = if (hubProxyHandler != null) BackendMode.STANDALONE_IN_APP_BACKEND else BackendMode.LOCAL_GEMINI_FALLBACK
) {
    @Volatile
    var backendMode: BackendMode = initialBackendMode
        private set

    fun setBackendMode(mode: BackendMode) {
        backendMode = mode
    }
    companion object {
        const val SERVICE_PREFIX = "/exa.language_server_pb.LanguageServerService/"
        const val DEFAULT_PROJECT_ID = "proj-antigravity-android"
        const val DEFAULT_CASCADE_ID = "00000000-0000-4000-8000-000000000001"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val localFileTools: LocalFileTools = LocalFileTools(workspaceRootDir)
    val agentRuntime: AgentRuntime = AgentRuntime(
        modelProvider = modelProvider,
        initialTools = listOf(localFileTools) + extraToolProviders,
        defaultMaxIterations = maxAgentIterations,
        defaultModelCallTimeoutMs = defaultTimeoutMs
    )

    private val projects = ConcurrentHashMap<String, CascadeProjectRecord>()
    private val trajectories = ConcurrentHashMap<String, CascadeTrajectoryRecord>()
    private val activeCascadeJobs = ConcurrentHashMap<String, Job>()
    private var themeMode: Int = 1 // 1 = Dark in jetbox_state_pb.UserSettings

    private val _stateUpdateEvents = MutableSharedFlow<Pair<String, JsonObject>>(extraBufferCapacity = 256)
    val stateUpdateEvents: SharedFlow<Pair<String, JsonObject>> = _stateUpdateEvents.asSharedFlow()

    init {
        workspaceRootDir.mkdirs()
        seedDefaultProjectAndWelcomeTrajectory()
    }

    private fun seedDefaultProjectAndWelcomeTrajectory() {
        val now = Instant.now().toString()
        val welcomeUserText = "Verify Antigravity Jetski Web frontend and native Android LanguageServerService connection."
        val welcomeAssistantText = "Connected to the native Android `LanguageServerGrpcWebHandler` backend. Enter any prompt below to start a live AI conversation using your configured API key in Android Keystore."
        val welcomeTrajectory = CascadeTrajectoryRecord(
            cascadeId = DEFAULT_CASCADE_ID,
            trajectoryId = "traj-$DEFAULT_CASCADE_ID",
            projectId = DEFAULT_PROJECT_ID,
            summary = "Antigravity Android Workspace",
            status = "CASCADE_RUN_STATUS_IDLE",
            fullyIdle = true,
            createdTimeIso = now,
            lastModifiedTimeIso = now,
            steps = CopyOnWriteArrayList(
                listOf(
                    CascadeStepRecord(
                        stepIndex = 0,
                        type = "CORTEX_STEP_TYPE_USER_INPUT",
                        status = "CORTEX_STEP_STATUS_DONE",
                        createdAtIso = now,
                        completedAtIso = now,
                        userText = welcomeUserText
                    ),
                    CascadeStepRecord(
                        stepIndex = 1,
                        type = "CORTEX_STEP_TYPE_PLANNER_RESPONSE",
                        status = "CORTEX_STEP_STATUS_DONE",
                        createdAtIso = now,
                        completedAtIso = now,
                        plannerResponseText = welcomeAssistantText,
                        plannerThinkingText = "Verified Connect-RPC gRPC-Web+JSON framing and trajectory slice state."
                    )
                )
            ),
            conversationHistory = CopyOnWriteArrayList(
                listOf(
                    Message(
                        id = "step-0",
                        conversationId = DEFAULT_CASCADE_ID,
                        role = Role.USER,
                        content = welcomeUserText
                    ),
                    Message(
                        id = "step-1",
                        conversationId = DEFAULT_CASCADE_ID,
                        role = Role.ASSISTANT,
                        content = welcomeAssistantText
                    )
                )
            )
        )
        trajectories[DEFAULT_CASCADE_ID] = welcomeTrajectory

        // Seed genuine desktop conversation 1: REA Availability and Capabilities (Antigravity Port)
        val reaTrajectory = CascadeTrajectoryRecord(
            cascadeId = "c367db09-5d84-4822-8946-5d626af85692",
            trajectoryId = "traj-c367db09-5d84-4822-8946-5d626af85692",
            projectId = "f62d4d56-8673-4da6-a2c0-458e5e2eb5bd",
            summary = "REA Availability and Capabilities",
            status = "CASCADE_RUN_STATUS_RUNNING",
            fullyIdle = false,
            createdTimeIso = "2026-11-09T06:25:54.398Z",
            lastModifiedTimeIso = now,
            steps = CopyOnWriteArrayList(
                listOf(
                    CascadeStepRecord(
                        stepIndex = 0,
                        type = "CORTEX_STEP_TYPE_USER_INPUT",
                        status = "CORTEX_STEP_STATUS_DONE",
                        createdAtIso = "2026-11-09T06:25:54.398Z",
                        completedAtIso = "2026-11-09T06:25:55.120Z",
                        userText = "Check whether REA is available.\n\nList its reverse-engineering capabilities.\nDo not investigate or execute any other step yet."
                    ),
                    CascadeStepRecord(
                        stepIndex = 1,
                        type = "CORTEX_STEP_TYPE_PLANNER_RESPONSE",
                        status = "CORTEX_STEP_STATUS_DONE",
                        createdAtIso = "2026-11-09T06:25:55.150Z",
                        completedAtIso = "2026-11-09T06:26:01.400Z",
                        plannerResponseText = "REA (Reverse Engineer Anything) MCP tools are connected and available for native, managed, Android APK, and Web reverse engineering.",
                        plannerThinkingText = "Verified REA toolset and reverse engineering capability profiles."
                    )
                )
            ),
            conversationHistory = CopyOnWriteArrayList(
                listOf(
                    Message(
                        id = "msg-rea-port-1",
                        conversationId = "c367db09-5d84-4822-8946-5d626af85692",
                        role = Role.USER,
                        content = "Check whether REA is available.\n\nList its reverse-engineering capabilities.\nDo not investigate or execute any other step yet."
                    ),
                    Message(
                        id = "msg-rea-port-2",
                        conversationId = "c367db09-5d84-4822-8946-5d626af85692",
                        role = Role.ASSISTANT,
                        content = "REA (Reverse Engineer Anything) MCP tools are connected and available for native, managed, Android APK, and Web reverse engineering."
                    )
                )
            )
        )
        trajectories["c367db09-5d84-4822-8946-5d626af85692"] = reaTrajectory

        // Seed genuine desktop conversation 2: ChaosAI Project Architecture Design
        val chaosTrajectory = CascadeTrajectoryRecord(
            cascadeId = "af4bd875-5f28-4b11-b783-d4954beed236",
            trajectoryId = "traj-af4bd875-5f28-4b11-b783-d4954beed236",
            projectId = "5937e51d-c88e-4862-8f07-3ce0e2625228",
            summary = "ChaosAI Project Architecture Design",
            status = "CASCADE_RUN_STATUS_IDLE",
            fullyIdle = true,
            createdTimeIso = "2026-11-08T22:52:06.476Z",
            lastModifiedTimeIso = "2026-11-08T22:52:41.237Z",
            steps = CopyOnWriteArrayList(
                listOf(
                    CascadeStepRecord(
                        stepIndex = 0,
                        type = "CORTEX_STEP_TYPE_USER_INPUT",
                        status = "CORTEX_STEP_STATUS_DONE",
                        createdAtIso = "2026-11-08T22:52:06.476Z",
                        completedAtIso = "2026-11-08T22:52:07.120Z",
                        userText = "We're starting ChaosAI, a Python-based mathematical chaos research project."
                    ),
                    CascadeStepRecord(
                        stepIndex = 1,
                        type = "CORTEX_STEP_TYPE_PLANNER_RESPONSE",
                        status = "CORTEX_STEP_STATUS_DONE",
                        createdAtIso = "2026-11-08T22:52:07.150Z",
                        completedAtIso = "2026-11-08T22:52:15.000Z",
                        plannerResponseText = "ChaosAI architecture initialized: NumPy/SciPy dynamic systems models and Lyapunov exponent estimators.",
                        plannerThinkingText = "Designed mathematical chaos simulation structure."
                    )
                )
            ),
            conversationHistory = CopyOnWriteArrayList(
                listOf(
                    Message(
                        id = "msg-chaos-1",
                        conversationId = "af4bd875-5f28-4b11-b783-d4954beed236",
                        role = Role.USER,
                        content = "We're starting ChaosAI, a Python-based mathematical chaos research project."
                    ),
                    Message(
                        id = "msg-chaos-2",
                        conversationId = "af4bd875-5f28-4b11-b783-d4954beed236",
                        role = Role.ASSISTANT,
                        content = "ChaosAI architecture initialized: NumPy/SciPy dynamic systems models and Lyapunov exponent estimators."
                    )
                )
            )
        )
        trajectories["af4bd875-5f28-4b11-b783-d4954beed236"] = chaosTrajectory

        // Seed genuine desktop conversation 3: REA Availability and Reverse Engineering Capabilities (ChaosAI)
        val reaChaosTrajectory = CascadeTrajectoryRecord(
            cascadeId = "b213d7c0-9bb6-4229-99be-7b7855c5c195",
            trajectoryId = "traj-b213d7c0-9bb6-4229-99be-7b7855c5c195",
            projectId = "5937e51d-c88e-4862-8f07-3ce0e2625228",
            summary = "REA Availability and Reverse Engineering Capabilities",
            status = "CASCADE_RUN_STATUS_IDLE",
            fullyIdle = true,
            createdTimeIso = "2026-11-09T06:25:25.698Z",
            lastModifiedTimeIso = "2026-11-09T06:25:25.698Z",
            steps = CopyOnWriteArrayList(
                listOf(
                    CascadeStepRecord(
                        stepIndex = 0,
                        type = "CORTEX_STEP_TYPE_USER_INPUT",
                        status = "CORTEX_STEP_STATUS_DONE",
                        createdAtIso = "2026-11-09T06:25:25.698Z",
                        completedAtIso = "2026-11-09T06:25:26.000Z",
                        userText = "Check whether REA is available.\n\nList its reverse-engineering capabilities."
                    )
                )
            ),
            conversationHistory = CopyOnWriteArrayList(
                listOf(
                    Message(
                        id = "msg-rea-chaos-1",
                        conversationId = "b213d7c0-9bb6-4229-99be-7b7855c5c195",
                        role = Role.USER,
                        content = "Check whether REA is available.\n\nList its reverse-engineering capabilities."
                    )
                )
            )
        )
        trajectories["b213d7c0-9bb6-4229-99be-7b7855c5c195"] = reaChaosTrajectory

        // Seed genuine desktop projects
        projects["f62d4d56-8673-4da6-a2c0-458e5e2eb5bd"] = CascadeProjectRecord(
            id = "f62d4d56-8673-4da6-a2c0-458e5e2eb5bd",
            name = "Antigravity Port",
            workspaceUri = "file:///c:/Users/NEW0033/Downloads/Antigravity%20Port",
            updatedAtIso = now,
            conversationIds = CopyOnWriteArrayList(listOf("c367db09-5d84-4822-8946-5d626af85692"))
        )

        projects["5937e51d-c88e-4862-8f07-3ce0e2625228"] = CascadeProjectRecord(
            id = "5937e51d-c88e-4862-8f07-3ce0e2625228",
            name = "ChaosAI",
            workspaceUri = "file:///c:/Users/NEW0033/OneDrive%20-%20St%20Helena%20Secondary%20College/da%20big%202026/Personal%20%5BONLY%20ME%21%21%21%21%5D/ChaosAI",
            updatedAtIso = now,
            conversationIds = CopyOnWriteArrayList(listOf("af4bd875-5f28-4b11-b783-d4954beed236", "b213d7c0-9bb6-4229-99be-7b7855c5c195"))
        )

        projects["outside-of-project"] = CascadeProjectRecord(
            id = "outside-of-project",
            name = "Outside of Project",
            workspaceUri = WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir),
            updatedAtIso = now,
            conversationIds = CopyOnWriteArrayList()
        )

        projects[DEFAULT_PROJECT_ID] = CascadeProjectRecord(
            id = DEFAULT_PROJECT_ID,
            name = "Local Sample Workspace",
            workspaceUri = WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir),
            updatedAtIso = now,
            conversationIds = CopyOnWriteArrayList(listOf(DEFAULT_CASCADE_ID))
        )
    }

    fun getTrajectoriesSnapshot(): Map<String, CascadeTrajectoryRecord> = trajectories.toMap()

    /**
     * Imports or updates a trajectory record into memory so that the Jetski Web frontend can display
     * conversations synchronized from the laptop / genuine Hub.
     */
    fun importTrajectory(record: CascadeTrajectoryRecord) {
        trajectories[record.cascadeId] = record
        val projId = record.projectId
        if (projId.isNotBlank()) {
            val proj = projects[projId]
            if (proj != null && record.cascadeId !in proj.conversationIds) {
                proj.conversationIds.add(record.cascadeId)
            }
        }
    }

    /**
     * Clears all non-essential trajectories and replaces them with the synced set from the Hub.
     */
    fun replaceTrajectories(newTrajectories: List<CascadeTrajectoryRecord>) {
        trajectories.clear()
        seedDefaultProjectAndWelcomeTrajectory()
        for (t in newTrajectories) {
            trajectories[t.cascadeId] = t
            val p = projects[t.projectId]
            if (p != null && t.cascadeId !in p.conversationIds) {
                p.conversationIds.add(t.cascadeId)
            }
        }
    }

    suspend fun handleRequest(request: GrpcWebHttpRequest): GrpcWebHttpResponse {
        val cleanPath = request.path.substringBefore('?')
        if (!cleanPath.startsWith(SERVICE_PREFIX)) {
            return errorResponse(GrpcStatus.NOT_FOUND, "Unknown service path: $cleanPath")
        }
        val rpcMethod = cleanPath.removePrefix(SERVICE_PREFIX)
        if (rpcMethod.isBlank()) {
            return errorResponse(GrpcStatus.INVALID_ARGUMENT, "Missing RPC method name")
        }

        // Verify CSRF token if enabled
        if (enforceCsrf) {
            val clientCsrf = request.headers.entries
                .firstOrNull { it.key.equals(GrpcWebFrameCodec.HEADER_CSRF_TOKEN, ignoreCase = true) }
                ?.value
            if (clientCsrf != csrfToken) {
                return errorResponse(
                    GrpcStatus.UNAUTHENTICATED,
                    "CSRF token mismatch or missing x-codeium-csrf-token header"
                )
            }
        }

        // Phase P3/P3.7: Route to genuine original Antigravity Hub when in ORIGINAL_DESKTOP_HUB or STANDALONE_IN_APP_BACKEND mode
        if (backendMode == BackendMode.ORIGINAL_DESKTOP_HUB || backendMode == BackendMode.STANDALONE_IN_APP_BACKEND) {
            val proxy = hubProxyHandler ?: return errorResponse(
                GrpcStatus.UNAVAILABLE,
                "Hub proxy is not configured for ${backendMode.displayName}. Ensure backend is connected or explicitly switch to Gemini Fallback mode."
            )
            return proxy.relayRequest(request)
        }

        val timeoutHeader = request.headers.entries
            .firstOrNull { it.key.equals(GrpcWebFrameCodec.HEADER_GRPC_TIMEOUT, ignoreCase = true) }
            ?.value
        // A full agent turn (multiple real model calls + tool executions) legitimately outlives an ordinary
        // unary RPC deadline, so SendUserCascadeMessage gets its own budget (P1.3). Per-model-call timeouts
        // still apply via defaultTimeoutMs inside AgentRuntime.
        val effectiveTimeoutMs = if (rpcMethod == "SendUserCascadeMessage") {
            maxOf(agentTurnTimeoutMs, GrpcWebFrameCodec.parseGrpcTimeoutMs(timeoutHeader) ?: 0L)
        } else {
            GrpcWebFrameCodec.parseGrpcTimeoutMs(timeoutHeader) ?: defaultTimeoutMs
        }

        return try {
            withTimeout(effectiveTimeoutMs) {
                val requestJsonText = GrpcWebFrameCodec.extractFirstRequestJson(request.body)
                val requestJson = try {
                    json.parseToJsonElement(requestJsonText).jsonObject
                } catch (e: Exception) {
                    throw GrpcWebProtocolException(
                        GrpcStatus.INVALID_ARGUMENT,
                        "Malformed JSON payload: ${e.message}"
                    )
                }

                // Multi-frame server-streaming mode for StreamAgentStateUpdates when requested
                if (rpcMethod == "StreamAgentStateUpdates" &&
                    requestJson["includeIncrementalHistory"]?.jsonPrimitive?.booleanOrNull == true
                ) {
                    val conversationId = requestJson["conversationId"]?.jsonPrimitive?.content ?: DEFAULT_CASCADE_ID
                    val record = trajectories[conversationId]
                    val payloads = if (record != null && record.incrementalUpdates.isNotEmpty()) {
                        record.incrementalUpdates.map { it.toString() }
                    } else {
                        listOf(dispatchRpc(rpcMethod, requestJson).toString())
                    }
                    return@withTimeout GrpcWebHttpResponse(
                        statusCode = 200,
                        body = GrpcWebFrameCodec.encodeServerStreamResponse(payloads)
                    )
                }

                val responseJson = dispatchRpc(rpcMethod, requestJson)
                GrpcWebHttpResponse(
                    statusCode = 200,
                    body = GrpcWebFrameCodec.encodeUnaryResponse(responseJson.toString())
                )
            }
        } catch (e: GrpcWebProtocolException) {
            errorResponse(e.status, e.message)
        } catch (e: TimeoutCancellationException) {
            errorResponse(GrpcStatus.DEADLINE_EXCEEDED, "the operation timed out after ${effectiveTimeoutMs}ms")
        } catch (e: CancellationException) {
            errorResponse(GrpcStatus.CANCELED, "RPC operation was canceled")
        } catch (e: Exception) {
            errorResponse(GrpcStatus.INTERNAL, e.message ?: "Internal RPC error")
        }
    }

    private suspend fun dispatchRpc(method: String, req: JsonObject): JsonObject {
        return when (method) {
            "GetLocalUserInfo" -> buildJsonObject {
                put("username", "android-user")
                put("homeDirUri", WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir))
                put("machineType", "android-arm64")
            }

            "GetServerConfiguration" -> buildJsonObject {
                put("serverConfig", buildJsonObject {
                    put("baseDirUri", WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir))
                    put("rootDirUri", WorkspacePathResolver.toNormalizedFileUri(File(workspaceRootDir, ".gemini")))
                    put("homeDirUri", WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir))
                    put("conversationsDirUri", WorkspacePathResolver.toNormalizedFileUri(File(workspaceRootDir, ".gemini/conversations")))
                    put("recordingsDirUri", WorkspacePathResolver.toNormalizedFileUri(File(workspaceRootDir, ".gemini/recordings")))
                    put("knowledgeDirUri", WorkspacePathResolver.toNormalizedFileUri(File(workspaceRootDir, ".gemini/knowledge")))
                    put("globalSkillsDirUri", WorkspacePathResolver.toNormalizedFileUri(File(workspaceRootDir, ".gemini/skills")))
                    put("pluginsDirUri", WorkspacePathResolver.toNormalizedFileUri(File(workspaceRootDir, ".gemini/plugins")))
                    put("isJetski", true)
                    put("standalone", true)
                    put("appName", "Antigravity Android")
                    put("uriScheme", "antigravity")
                })
            }

            "GetMendelFlags" -> buildJsonObject {
                put("flags", buildJsonArray {})
            }
            "SetMendelFlagOverrides" -> buildJsonObject {
                put("flags", buildJsonArray {})
            }
            "FetchAdminControls" -> buildJsonObject {}

            "HasAuthToken" -> {
                buildJsonObject {
                    put("hasToken", true)
                    put("isGcpTos", false)
                    put("projectId", "")
                }
            }

            "GetAuthStatus" -> {
                val hasGeminiKey = isAuthenticatedProvider()
                buildJsonObject {
                    put("authResult", buildJsonObject {
                        put("hasValidAuth", true)
                        put("authMode", "AUTH_MODE_GEMINI_API_KEY")
                        put("grantedScopes", buildJsonArray {})
                        put("isGcpTos", false)
                        put(
                            "uiMessage",
                            if (hasGeminiKey) {
                                "Local Android host active. Independent Gemini API key configured in Android Keystore (not signed into a Google account)."
                            } else {
                                "Local Android host active (not signed into a Google account). Configure a Gemini API key in Settings to enable model responses."
                            }
                        )
                    })
                }
            }

            "GetUserStatus" -> {
                val hasGeminiKey = isAuthenticatedProvider()
                buildJsonObject {
                    put("userStatus", buildJsonObject {
                        put("pro", false)
                        put("disableTelemetry", true)
                        put("name", "Android Local Host")
                        put("email", "")
                        put("teamStatus", 0)
                        put("userTier", buildJsonObject {
                            put(
                                "id",
                                if (hasGeminiKey) "independent-gemini-api-key" else "local-host-unconfigured-key"
                            )
                            put(
                                "name",
                                if (hasGeminiKey) {
                                    "Gemini API Key (Independent Auth)"
                                } else {
                                    "Local Android Host (API Key Required for AI)"
                                }
                            )
                            put(
                                "description",
                                if (hasGeminiKey) {
                                    "Authenticated via user-provided Gemini API key in Android Keystore. Separate from Google/Antigravity OAuth."
                                } else {
                                    "Local Android workspace host is ready. Add a Gemini API key in Settings to run Cascade prompts."
                                }
                            )
                        })
                        put("cascadeModelConfigData", DesktopModelCatalog.buildDefaultCascadeModelConfigData())
                    })
                }
            }

            "JetboxSubscribeToState" -> buildJsonObject {
                put("state", buildJsonObject {
                    put("appState", buildJsonObject {
                        put("agentOnboardingCompleted", true)
                        put("welcomeModalDismissed", true)
                        put("projectSectionCollapsed", false)
                    })
                    put("userConfig", buildJsonObject {
                        put("userSettings", buildJsonObject {
                            put("themeMode", themeMode)
                        })
                    })
                })
            }

            "JetboxWriteState" -> {
                val newTheme = req["state"]?.jsonObject
                    ?.get("userConfig")?.jsonObject
                    ?.get("userSettings")?.jsonObject
                    ?.get("themeMode")?.jsonPrimitive?.intOrNull
                if (newTheme != null) {
                    themeMode = newTheme
                }
                buildJsonObject {}
            }

            "JetboxSubscribeToSummaries" -> buildJsonObject {
                put("updates", buildSummariesMapJson())
            }

            "ListProjects" -> buildJsonObject {
                put("projectIds", buildJsonArray {
                    for (id in projects.keys) {
                        add(JsonPrimitive(id))
                    }
                })
            }

            "ProjectUpdatesStream" -> buildJsonObject {
                put("projectList", buildJsonObject {
                    put("projectIds", buildJsonArray {
                        for (id in projects.keys) {
                            add(JsonPrimitive(id))
                        }
                    })
                })
            }

            "ReadProjects" -> {
                val ids = req["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: projects.keys.toList()
                buildJsonObject {
                    put("projects", buildJsonArray {
                        for (id in ids) {
                            val proj = projects[id] ?: CascadeProjectRecord(
                                id = id,
                                name = if (id == "outside-of-project") "Outside of Project" else "Workspace ($id)",
                                workspaceUri = WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir)
                            )
                            add(encodeProjectJson(proj))
                        }
                    })
                }
            }

            "ReadProject" -> {
                val id = req["id"]?.jsonPrimitive?.content ?: DEFAULT_PROJECT_ID
                val proj = projects[id]
                if (proj == null) {
                    buildJsonObject { put("notFoundOnDisk", true) }
                } else {
                    buildJsonObject {
                        put("notFoundOnDisk", false)
                        put("project", encodeProjectJson(proj))
                    }
                }
            }

            "GetAllCascadeTrajectories" -> buildJsonObject {
                put("trajectorySummaries", buildSummariesMapJson())
            }

            "GetCascadeTrajectory" -> {
                val cascadeId = req["cascadeId"]?.jsonPrimitive?.content ?: DEFAULT_CASCADE_ID
                val record = trajectories[cascadeId]
                    ?: throw GrpcWebProtocolException(GrpcStatus.NOT_FOUND, "Cascade not found: $cascadeId")
                buildJsonObject {
                    put("trajectory", encodeTrajectoryJson(record))
                    put("status", record.status)
                    put("numTotalSteps", record.steps.size)
                }
            }

            "GetCascadeTrajectorySteps" -> {
                val cascadeId = req["cascadeId"]?.jsonPrimitive?.content ?: DEFAULT_CASCADE_ID
                val stepOffset = (req["stepOffset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                val record = trajectories[cascadeId]
                    ?: throw GrpcWebProtocolException(GrpcStatus.NOT_FOUND, "Cascade not found: $cascadeId")
                val stepsSnapshot = record.steps.toList()
                val sliced = if (stepOffset < stepsSnapshot.size) {
                    stepsSnapshot.subList(stepOffset, stepsSnapshot.size)
                } else {
                    emptyList()
                }
                buildJsonObject {
                    put("steps", buildJsonArray {
                        for (s in sliced) {
                            add(encodeStepJson(s))
                        }
                    })
                }
            }

            "GetTrajectoryTurns" -> {
                val cascadeId = req["cascadeId"]?.jsonPrimitive?.content ?: DEFAULT_CASCADE_ID
                val record = trajectories[cascadeId]
                buildJsonObject {
                    put("turns", buildJsonArray {
                        if (record != null) {
                            val userSteps = record.steps.filter { it.type == "CORTEX_STEP_TYPE_USER_INPUT" }
                            for (uStep in userSteps) {
                                add(buildJsonObject {
                                    put("userInputStepIndex", uStep.stepIndex)
                                    put("userInputSnippet", uStep.userText ?: "")
                                })
                            }
                        }
                    })
                }
            }

            "StartCascade" -> {
                val requestedId = req["cascadeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val newCascadeId = requestedId ?: UUID.randomUUID().toString()
                val now = Instant.now().toString()
                val record = trajectories.computeIfAbsent(newCascadeId) {
                    CascadeTrajectoryRecord(
                        cascadeId = newCascadeId,
                        trajectoryId = "traj-$newCascadeId",
                        projectId = DEFAULT_PROJECT_ID,
                        summary = "New Conversation",
                        status = "CASCADE_RUN_STATUS_IDLE",
                        fullyIdle = true,
                        createdTimeIso = now,
                        lastModifiedTimeIso = now
                    )
                }
                val defaultProj = projects[DEFAULT_PROJECT_ID]
                if (defaultProj != null && !defaultProj.conversationIds.contains(newCascadeId)) {
                    defaultProj.conversationIds.add(0, newCascadeId)
                }
                emitLiveTrajectoryUpdate(record)
                buildJsonObject {
                    put("cascadeId", newCascadeId)
                }
            }

            "SendUserCascadeMessage" -> handleSendUserCascadeMessage(req)

            "StreamAgentStateUpdates" -> {
                val conversationId = req["conversationId"]?.jsonPrimitive?.content ?: DEFAULT_CASCADE_ID
                val record = trajectories[conversationId]
                    ?: CascadeTrajectoryRecord(
                        cascadeId = conversationId,
                        trajectoryId = "traj-$conversationId",
                        projectId = DEFAULT_PROJECT_ID,
                        summary = "Untitled Conversation"
                    ).also { trajectories[conversationId] = it }

                buildStreamAgentStateUpdateJson(record)
            }

            "RequestAgentStatePageUpdate" -> buildJsonObject {}

            "CancelCascadeInvocation", "ForceStopCascadeTree" -> {
                val cascadeId = req["cascadeId"]?.jsonPrimitive?.content
                localFileTools.commandExecutor.killCommand(null)
                if (cascadeId != null) {
                    activeCascadeJobs.remove(cascadeId)?.cancel(CancellationException("Cancelled by user via $method"))
                    trajectories[cascadeId]?.let { rec ->
                        rec.status = "CASCADE_RUN_STATUS_IDLE"
                        rec.fullyIdle = true
                        rec.lastModifiedTimeIso = Instant.now().toString()
                        for (idx in rec.steps.indices) {
                            val s = rec.steps[idx]
                            if (s.status == "CORTEX_STEP_STATUS_RUNNING" || s.status == "CORTEX_STEP_STATUS_PENDING") {
                                rec.steps[idx] = s.copy(
                                    status = "CORTEX_STEP_STATUS_CANCELED",
                                    completedAtIso = Instant.now().toString()
                                )
                            }
                        }
                        emitLiveTrajectoryUpdate(rec)
                    }
                } else {
                    for ((_, job) in activeCascadeJobs) {
                        job.cancel(CancellationException("Cancelled by user via $method"))
                    }
                    activeCascadeJobs.clear()
                }
                buildJsonObject {}
            }

            "ReadDir" -> handleReadDir(req)
            "ReadFile" -> handleReadFile(req)
            "WriteFile" -> handleWriteFile(req)
            "StatUri" -> handleStatUri(req)
            "RunCommand" -> handleRunCommand(req)
            "KillCommand", "TerminateCommand" -> handleKillCommand(req)
            "GetActiveCommand", "ListCommands" -> handleGetActiveCommand()
            "GetTurnDiff" -> handleGetTurnDiff(req)

            // Verified startup & customization queries matching language_server.proto schemas
            "SubscribeToSidecars" -> buildJsonObject {
                put("sidecars", buildJsonArray {})
            }
            "ListTriggers" -> buildJsonObject {
                put("triggers", buildJsonArray {})
                put("nextPageToken", "")
            }
            "ListTriggerOptions" -> buildJsonObject {
                put("options", buildJsonArray {})
            }
            "GetAgentScripts" -> buildJsonObject {
                put("agentScripts", buildJsonArray {})
                put("agentCustomizations", buildJsonArray {})
            }
            "GetMcpServerStates" -> buildJsonObject {
                put("mcpServers", DesktopModelCatalog.buildDefaultMcpServers())
            }
            "SubscribeToMcpServerStates" -> buildJsonObject {
                put("states", DesktopModelCatalog.buildDefaultMcpServers())
                put("isLoading", false)
            }
            "SubscribeToPlugins", "ListRemotePlugins", "GetAllPlugins" -> buildJsonObject {
                put("plugins", buildJsonArray {})
            }
            "GetAllSkills" -> buildJsonObject {
                put("skills", DesktopModelCatalog.buildDefaultSkills())
            }
            "GetAllWorkflows" -> buildJsonObject {
                put("workflows", buildJsonArray {})
            }
            "GetSlashCommands" -> buildJsonObject {
                put("commands", buildJsonArray {})
            }
            "GetCustomizationStates" -> buildJsonObject {
                put("states", DesktopModelCatalog.buildDefaultCustomizationStates())
            }
            "GetCascadeNuxes" -> buildJsonObject {
                put("nuxes", DesktopModelCatalog.buildDefaultNuxes())
            }
            "ListMcpPrompts",
            "UpdateConversationAnnotations",
            "WarmUpEnvironment",
            "RecordAnalyticsEvent",
            "RecordError" -> buildJsonObject {}

            "GetRemoteControlInfo" -> buildJsonObject {
                put("enabled", false)
                put("instanceId", "")
                put("companionOrigin", "")
            }

            "RetrieveUserQuotaSummary" -> buildJsonObject {
                put("response", buildJsonObject {
                    put("groups", DesktopModelCatalog.buildDefaultQuotaGroups())
                })
            }

            "GetAvailableModels" -> buildJsonObject {
                put("response", buildJsonObject {
                    put("tieredModelIds", buildJsonObject {
                        put("flashLite", buildJsonArray {})
                        put("flash", buildJsonArray {
                            add(JsonPrimitive("gemini-3.8-flash-high"))
                            add(JsonPrimitive("gemini-3.8-flash-medium"))
                            add(JsonPrimitive("gemini-3.8-flash-low"))
                        })
                        put("pro", buildJsonArray {
                            add(JsonPrimitive("gemini-4-argon-medium"))
                            add(JsonPrimitive("claude-opus-5-5-medium"))
                            add(JsonPrimitive("claude-sonnet-5-5-medium"))
                        })
                    })
                })
            }

            "GetLoadCodeAssist" -> buildJsonObject {
                put("response", buildJsonObject {})
            }

            else -> throw GrpcWebProtocolException(
                GrpcStatus.UNIMPLEMENTED,
                "Method $method is not implemented on Android LanguageServerGrpcWebHandler"
            )
        }
    }

    private suspend fun handleSendUserCascadeMessage(req: JsonObject): JsonObject {
        if (!isAuthenticatedProvider()) {
            throw GrpcWebProtocolException(
                GrpcStatus.UNAUTHENTICATED,
                "UNAUTHENTICATED: No API key configured in Android Keystore. Local host session is active, but model calls require an independent Gemini API key in Settings."
            )
        }

        val cascadeId = req["cascadeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val promptText = extractUserPromptFromRequest(req)
        if (promptText.isBlank()) {
            throw GrpcWebProtocolException(GrpcStatus.INVALID_ARGUMENT, "Prompt text must not be empty")
        }

        val requestedModelId = extractRequestedModelId(req)
        val now = Instant.now().toString()
        val record = trajectories.computeIfAbsent(cascadeId) {
            CascadeTrajectoryRecord(
                cascadeId = cascadeId,
                trajectoryId = "traj-$cascadeId",
                projectId = DEFAULT_PROJECT_ID,
                summary = promptText.take(60),
                createdTimeIso = now,
                lastModifiedTimeIso = now
            )
        }
        val defaultProj = projects[DEFAULT_PROJECT_ID]
        if (defaultProj != null && !defaultProj.conversationIds.contains(cascadeId)) {
            defaultProj.conversationIds.add(0, cascadeId)
        }
        if (record.summary == "New Conversation" || record.summary == "Untitled Conversation") {
            record.summary = promptText.take(60)
        }

        val currentJob = currentCoroutineContext()[Job]
        if (currentJob != null) {
            activeCascadeJobs[cascadeId] = currentJob
        }

        // 1. Append USER_INPUT step
        val userStepIndex = record.steps.size
        record.turnFileDiffs.putIfAbsent(userStepIndex, ConcurrentHashMap())
        record.steps.add(
            CascadeStepRecord(
                stepIndex = userStepIndex,
                type = "CORTEX_STEP_TYPE_USER_INPUT",
                status = "CORTEX_STEP_STATUS_DONE",
                createdAtIso = now,
                completedAtIso = now,
                userText = promptText
            )
        )
        record.status = "CASCADE_RUN_STATUS_RUNNING"
        record.fullyIdle = false
        record.lastModifiedTimeIso = Instant.now().toString()
        emitLiveTrajectoryUpdate(record)

        val priorHistory = record.conversationHistory.toList()
        var activePlannerStepIndex: Int? = null
        val activePlannerBuffer = StringBuilder()
        val activeToolStepIndices = ConcurrentHashMap<String, Int>()

        try {
            val outcome = agentRuntime.executeAgentLoop(
                conversationId = cascadeId,
                taskPrompt = promptText,
                history = priorHistory,
                modelId = requestedModelId,
                maxIterations = maxAgentIterations,
                modelCallTimeoutMs = defaultTimeoutMs,
                onTokenDelta = { delta ->
                    val pIdx = activePlannerStepIndex
                    if (pIdx != null && pIdx < record.steps.size) {
                        activePlannerBuffer.append(delta)
                        val existing = record.steps[pIdx]
                        record.steps[pIdx] = existing.copy(
                            plannerResponseText = activePlannerBuffer.toString(),
                            status = "CORTEX_STEP_STATUS_RUNNING"
                        )
                        record.lastModifiedTimeIso = Instant.now().toString()
                        emitLiveTrajectoryUpdate(record)
                    }
                },
                onExecutionEvent = { event ->
                    when (event) {
                        is AgentExecutionEvent.PlannerStarted -> {
                            activePlannerBuffer.clear()
                            val pIdx = record.steps.size
                            activePlannerStepIndex = pIdx
                            record.steps.add(
                                CascadeStepRecord(
                                    stepIndex = pIdx,
                                    type = "CORTEX_STEP_TYPE_PLANNER_RESPONSE",
                                    status = "CORTEX_STEP_STATUS_RUNNING",
                                    createdAtIso = Instant.now().toString(),
                                    plannerResponseText = ""
                                )
                            )
                            record.lastModifiedTimeIso = Instant.now().toString()
                            emitLiveTrajectoryUpdate(record)
                        }

                        is AgentExecutionEvent.PlannerDelta -> {
                            // Handled via onTokenDelta above
                        }

                        is AgentExecutionEvent.PlannerCompleted -> {
                            val pIdx = activePlannerStepIndex
                            if (pIdx != null && pIdx < record.steps.size) {
                                val finalText = activePlannerBuffer.toString().ifBlank { event.responseText }
                                record.steps[pIdx] = record.steps[pIdx].copy(
                                    status = "CORTEX_STEP_STATUS_DONE",
                                    completedAtIso = Instant.now().toString(),
                                    plannerResponseText = finalText,
                                    plannerThinkingText = event.thinkingText.takeIf { it.isNotBlank() },
                                    plannerToolCalls = event.toolCalls
                                )
                                record.lastModifiedTimeIso = Instant.now().toString()
                                emitLiveTrajectoryUpdate(record)
                            }
                        }

                        is AgentExecutionEvent.ToolStarted -> {
                            val call = event.toolCall
                            val stepType = mapToolNameToStepType(call.name)
                            val (summary, action) = buildToolSummaryAndAction(call)
                            val tIdx = record.steps.size
                            activeToolStepIndices[call.id] = tIdx
                            record.steps.add(
                                CascadeStepRecord(
                                    stepIndex = tIdx,
                                    type = stepType,
                                    status = "CORTEX_STEP_STATUS_RUNNING",
                                    createdAtIso = Instant.now().toString(),
                                    toolCall = call,
                                    toolSummary = summary,
                                    toolAction = action
                                )
                            )
                            record.lastModifiedTimeIso = Instant.now().toString()
                            emitLiveTrajectoryUpdate(record)
                        }

                        is AgentExecutionEvent.ToolCompleted -> {
                            val call = event.toolCall
                            val res = event.toolResult
                            val tIdx = activeToolStepIndices[call.id]
                            if (tIdx != null && tIdx < record.steps.size) {
                                val existing = record.steps[tIdx]
                                record.steps[tIdx] = existing.copy(
                                    status = if (res.success) "CORTEX_STEP_STATUS_DONE" else "CORTEX_STEP_STATUS_ERROR",
                                    completedAtIso = Instant.now().toString(),
                                    structuredData = res.structuredData,
                                    errorMessage = if (!res.success) res.output else null
                                )
                            }
                            if (res.success && (call.name == "write_to_file" || call.name == "write_file" || call.name == "edit_file")) {
                                recordTurnFileDiffFromToolResult(record, userStepIndex, res.structuredData)
                            }
                            record.lastModifiedTimeIso = Instant.now().toString()
                            emitLiveTrajectoryUpdate(record)
                        }

                        is AgentExecutionEvent.MaxIterationsReached -> {
                            val pIdx = record.steps.size
                            record.steps.add(
                                CascadeStepRecord(
                                    stepIndex = pIdx,
                                    type = "CORTEX_STEP_TYPE_PLANNER_RESPONSE",
                                    status = "CORTEX_STEP_STATUS_DONE",
                                    createdAtIso = Instant.now().toString(),
                                    completedAtIso = Instant.now().toString(),
                                    plannerResponseText = "Reached maximum agent execution iterations (${event.maxIterations})."
                                )
                            )
                            record.lastModifiedTimeIso = Instant.now().toString()
                            emitLiveTrajectoryUpdate(record)
                        }

                        is AgentExecutionEvent.Error -> {
                            val pIdx = activePlannerStepIndex
                            if (pIdx != null && pIdx < record.steps.size && record.steps[pIdx].status == "CORTEX_STEP_STATUS_RUNNING") {
                                record.steps[pIdx] = record.steps[pIdx].copy(
                                    status = "CORTEX_STEP_STATUS_ERROR",
                                    completedAtIso = Instant.now().toString(),
                                    plannerResponseText = "Error communicating with AI model: ${event.errorMessage}",
                                    errorMessage = event.errorMessage
                                )
                            } else {
                                val errStepIdx = record.steps.size
                                record.steps.add(
                                    CascadeStepRecord(
                                        stepIndex = errStepIdx,
                                        type = "CORTEX_STEP_TYPE_ERROR_MESSAGE",
                                        status = "CORTEX_STEP_STATUS_ERROR",
                                        createdAtIso = Instant.now().toString(),
                                        completedAtIso = Instant.now().toString(),
                                        errorMessage = event.errorMessage
                                    )
                                )
                            }
                            record.lastModifiedTimeIso = Instant.now().toString()
                            emitLiveTrajectoryUpdate(record)
                        }

                        is AgentExecutionEvent.Completed, is AgentExecutionEvent.Cancelled -> {
                            // Handled on loop return
                        }
                    }
                }
            )

            record.status = "CASCADE_RUN_STATUS_IDLE"
            record.fullyIdle = true
            record.lastModifiedTimeIso = Instant.now().toString()

            if (outcome.errorMessage != null) {
                emitLiveTrajectoryUpdate(record)
                throw GrpcWebProtocolException(
                    GrpcStatus.UNAVAILABLE,
                    "Model inference failed: ${outcome.errorMessage}"
                )
            }

            record.conversationHistory.clear()
            record.conversationHistory.addAll(outcome.updatedHistory)
            emitLiveTrajectoryUpdate(record)

            onConversationUpdated?.invoke(cascadeId, record.summary, outcome.updatedHistory)
        } catch (e: GrpcWebProtocolException) {
            record.status = "CASCADE_RUN_STATUS_IDLE"
            record.fullyIdle = true
            emitLiveTrajectoryUpdate(record)
            throw e
        } catch (e: CancellationException) {
            record.status = "CASCADE_RUN_STATUS_IDLE"
            record.fullyIdle = true
            for (idx in record.steps.indices) {
                val s = record.steps[idx]
                if (s.status == "CORTEX_STEP_STATUS_RUNNING") {
                    record.steps[idx] = s.copy(
                        status = "CORTEX_STEP_STATUS_CANCELED",
                        completedAtIso = Instant.now().toString()
                    )
                }
            }
            emitLiveTrajectoryUpdate(record)
            throw e
        } catch (e: Exception) {
            record.status = "CASCADE_RUN_STATUS_IDLE"
            record.fullyIdle = true
            emitLiveTrajectoryUpdate(record)
            throw GrpcWebProtocolException(
                GrpcStatus.UNAVAILABLE,
                "Model inference failed: ${e.message}"
            )
        } finally {
            activeCascadeJobs.remove(cascadeId)
        }

        return buildJsonObject {}
    }

    private fun emitLiveTrajectoryUpdate(record: CascadeTrajectoryRecord) {
        val snapshot = buildStreamAgentStateUpdateJson(record)
        record.incrementalUpdates.add(snapshot)
        _stateUpdateEvents.tryEmit(record.cascadeId to snapshot)
    }

    private fun extractRequestedModelId(req: JsonObject): String {
        val directModel = req["model"]?.jsonPrimitive?.content
        if (!directModel.isNullOrBlank()) {
            return GeminiModelProvider.normalizeGeminiModelId(directModel)
        }
        val plannerConfig = req["cascadeConfig"]?.jsonObject?.get("plannerConfig")?.jsonObject
        val modelFromConfig = plannerConfig?.get("modelName")?.jsonPrimitive?.content
            ?: plannerConfig?.get("requestedModel")?.jsonObject?.get("model")?.jsonPrimitive?.content
            ?: plannerConfig?.get("planModel")?.jsonPrimitive?.content
        return GeminiModelProvider.normalizeGeminiModelId(modelFromConfig)
    }

    private fun mapToolNameToStepType(toolName: String): String = when (toolName) {
        "view_file", "read_file" -> "CORTEX_STEP_TYPE_VIEW_FILE"
        "list_dir", "list_files" -> "CORTEX_STEP_TYPE_LIST_DIRECTORY"
        "write_to_file", "write_file", "edit_file" -> "CORTEX_STEP_TYPE_FILE_CHANGE"
        "grep_search" -> "CORTEX_STEP_TYPE_GREP_SEARCH"
        "find_by_name" -> "CORTEX_STEP_TYPE_FIND"
        "run_command" -> "CORTEX_STEP_TYPE_RUN_COMMAND"
        else -> "CORTEX_STEP_TYPE_GENERIC"
    }

    private fun buildToolSummaryAndAction(call: ToolCall): Pair<String, String> {
        val argsObj = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
        val path = argsObj?.get("path")?.jsonPrimitive?.content
            ?: argsObj?.get("search_path")?.jsonPrimitive?.content
            ?: argsObj?.get("search_directory")?.jsonPrimitive?.content
            ?: "."
        return when (call.name) {
            "view_file", "read_file" -> "Read $path" to "Viewing file $path"
            "list_dir", "list_files" -> "List $path" to "Listing directory $path"
            "write_to_file", "write_file" -> "Write $path" to "Writing file $path"
            "edit_file" -> "Edit $path" to "Editing file $path"
            "grep_search" -> {
                val q = argsObj?.get("query")?.jsonPrimitive?.content.orEmpty()
                "Grep '$q'" to "Searching for '$q' in $path"
            }
            "find_by_name" -> {
                val p = argsObj?.get("pattern")?.jsonPrimitive?.content.orEmpty()
                "Find '$p'" to "Finding files matching '$p' in $path"
            }
            "run_command" -> {
                val cmd = argsObj?.get("command")?.jsonPrimitive?.content.orEmpty()
                "Run command" to "Running `$cmd`"
            }
            else -> call.name to "Executing ${call.name}"
        }
    }

    private fun recordTurnFileDiffFromToolResult(
        record: CascadeTrajectoryRecord,
        turnUserStepIndex: Int,
        structuredData: Map<String, String>
    ) {
        val fileUri = structuredData["absolutePathUri"] ?: return
        val orig = structuredData["originalContents"] ?: ""
        val mod = structuredData["modifiedContents"] ?: ""
        val add = structuredData["additions"]?.toIntOrNull() ?: 0
        val del = structuredData["deletions"]?.toIntOrNull() ?: 0

        val turnMap = record.turnFileDiffs.computeIfAbsent(turnUserStepIndex) { ConcurrentHashMap() }
        val existing = turnMap[fileUri]
        if (existing == null) {
            turnMap[fileUri] = TurnFileDiffEntry(
                fileUri = fileUri,
                originalContents = orig,
                modifiedContents = mod,
                additions = add,
                deletions = del
            )
        } else {
            existing.modifiedContents = mod
            existing.additions += add
            existing.deletions += del
        }
    }

    private fun extractUserPromptFromRequest(req: JsonObject): String {
        val sb = StringBuilder()
        val items = req["items"]?.jsonArray
        if (items != null) {
            for (item in items) {
                val obj = item.jsonObject
                val textElement = obj["text"]
                val text = when {
                    textElement is JsonObject -> textElement["content"]?.jsonPrimitive?.content
                    textElement != null -> runCatching { textElement.jsonPrimitive.content }.getOrNull()
                    else -> null
                }
                if (!text.isNullOrBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(text)
                }
            }
        }
        if (sb.isEmpty()) {
            val fallback = req["userResponse"]?.jsonPrimitive?.content
                ?: req["query"]?.jsonPrimitive?.content
            if (!fallback.isNullOrBlank()) sb.append(fallback)
        }
        return sb.toString().trim()
    }

    private fun buildSummariesMapJson(): JsonObject {
        return buildJsonObject {
            for ((cascadeId, rec) in trajectories) {
                val lastUserStepIdx = rec.steps
                    .lastOrNull { it.type == "CORTEX_STEP_TYPE_USER_INPUT" }
                    ?.stepIndex ?: 0
                put(cascadeId, buildJsonObject {
                    put("summary", rec.summary)
                    put("stepCount", rec.steps.size)
                    put("lastModifiedTime", rec.lastModifiedTimeIso)
                    put("trajectoryId", rec.trajectoryId)
                    put("status", rec.status)
                    put("createdTime", rec.createdTimeIso)
                    put("lastUserInputTime", rec.lastModifiedTimeIso)
                    put("lastUserInputStepIndex", lastUserStepIdx)
                    put("annotations", buildJsonObject {
                        put("title", rec.summary)
                        put("lastUserViewTime", rec.lastModifiedTimeIso)
                    })
                    put("trajectoryMetadata", buildJsonObject {
                        put("rootConversationId", cascadeId)
                        put("projectId", rec.projectId)
                        put("workspaceUris", buildJsonArray {
                            add(JsonPrimitive(WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir)))
                        })
                    })
                })
            }
        }
    }

    private fun encodeProjectJson(proj: CascadeProjectRecord): JsonObject {
        return buildJsonObject {
            put("id", proj.id)
            put("name", proj.name)
            put("updatedAt", proj.updatedAtIso)
            put("isWorkspaceOnly", false)
            put("projectResources", buildJsonObject {
                put("resources", buildJsonArray {
                    add(buildJsonObject {
                        put("folderUri", proj.workspaceUri)
                    })
                })
            })
            put("permissionGrants", buildJsonObject {
                put("v2Migrated", true)
            })
            put("settings", buildJsonObject {})
            put("projectConversations", buildJsonObject {
                put("conversations", buildJsonArray {
                    for (cid in proj.conversationIds) {
                        add(buildJsonObject {
                            put("conversationId", cid)
                        })
                    }
                })
            })
        }
    }

    private fun encodeTrajectoryJson(record: CascadeTrajectoryRecord): JsonObject {
        return buildJsonObject {
            put("id", record.trajectoryId)
            put("cascadeId", record.cascadeId)
            put("trajectoryId", record.trajectoryId)
            put("trajectoryType", "TRAJECTORY_TYPE_CASCADE")
            put("steps", buildJsonArray {
                for (step in record.steps) {
                    add(encodeStepJson(step))
                }
            })
        }
    }

    internal fun encodeStepJson(step: CascadeStepRecord): JsonObject {
        return buildJsonObject {
            put("type", step.type)
            put("status", step.status)
            put("metadata", buildJsonObject {
                put("createdAt", step.createdAtIso)
                if (!step.completedAtIso.isNullOrBlank()) {
                    put("completedAt", step.completedAtIso)
                }
                if (!step.toolSummary.isNullOrBlank()) {
                    put("toolSummary", step.toolSummary)
                }
                if (!step.toolAction.isNullOrBlank()) {
                    put("toolAction", step.toolAction)
                }
                if (step.toolCall != null) {
                    put("toolCall", buildJsonObject {
                        put("id", step.toolCall.id)
                        put("name", step.toolCall.name)
                        put("argumentsJson", step.toolCall.argumentsJson)
                    })
                }
            })
            if (!step.errorMessage.isNullOrBlank()) {
                put("error", buildJsonObject {
                    put("userErrorMessage", step.errorMessage)
                    put("modelErrorMessage", step.errorMessage)
                    put("shortError", step.errorMessage)
                    put("fullError", step.errorMessage)
                })
            }

            when (step.type) {
                "CORTEX_STEP_TYPE_USER_INPUT" -> {
                    val text = step.userText ?: ""
                    put("userInput", buildJsonObject {
                        put("userResponse", text)
                        put("items", buildJsonArray {
                            add(buildJsonObject { put("text", text) })
                        })
                    })
                }

                "CORTEX_STEP_TYPE_PLANNER_RESPONSE" -> {
                    val resp = step.plannerResponseText ?: ""
                    put("plannerResponse", buildJsonObject {
                        put("response", resp)
                        put("modifiedResponse", resp)
                        if (!step.plannerThinkingText.isNullOrBlank()) {
                            put("thinking", step.plannerThinkingText)
                        }
                        if (step.plannerToolCalls.isNotEmpty()) {
                            put("toolCalls", buildJsonArray {
                                for (tc in step.plannerToolCalls) {
                                    add(buildJsonObject {
                                        put("id", tc.id)
                                        put("name", tc.name)
                                        put("argumentsJson", tc.argumentsJson)
                                    })
                                }
                            })
                        }
                    })
                }

                "CORTEX_STEP_TYPE_VIEW_FILE" -> {
                    val sd = step.structuredData
                    val argsObj = step.toolCall?.let {
                        runCatching { json.parseToJsonElement(it.argumentsJson).jsonObject }.getOrNull()
                    }
                    val fallbackPath = argsObj?.get("path")?.jsonPrimitive?.content.orEmpty()
                    val uri = sd["absolutePathUri"]
                        ?: if (fallbackPath.isNotBlank()) WorkspacePathResolver.toNormalizedFileUri(File(workspaceRootDir, fallbackPath)) else ""
                    put("viewFile", buildJsonObject {
                        put("absolutePathUri", uri)
                        put("startLine", sd["startLine"]?.toIntOrNull() ?: 1)
                        put("endLine", sd["endLine"]?.toIntOrNull() ?: 1)
                        put("content", sd["content"] ?: "")
                        put("numLines", sd["numLines"]?.toIntOrNull() ?: 0)
                        put("numBytes", sd["numBytes"]?.toIntOrNull() ?: 0)
                    })
                }

                "CORTEX_STEP_TYPE_LIST_DIRECTORY" -> {
                    val sd = step.structuredData
                    val dirUri = sd["directoryPathUri"] ?: WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir)
                    val entriesArr = sd["entriesJson"]?.let {
                        runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull()
                    } ?: JsonArray(emptyList())
                    put("listDirectory", buildJsonObject {
                        put("directoryPathUri", dirUri)
                        put("dirNotFound", sd["dirNotFound"] == "true")
                        put("results", buildJsonArray {
                            for (el in entriesArr) {
                                val obj = el.jsonObject
                                add(buildJsonObject {
                                    put("name", obj["name"]?.jsonPrimitive?.content ?: "")
                                    put("isDir", obj["isDir"]?.jsonPrimitive?.booleanOrNull ?: false)
                                    put("sizeBytes", obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L)
                                    val numChildren = obj["numChildren"]?.jsonPrimitive?.intOrNull
                                    if (numChildren != null) {
                                        put("numChildren", numChildren)
                                    }
                                })
                            }
                        })
                        put("children", buildJsonArray {
                            for (el in entriesArr) {
                                val name = el.jsonObject["name"]?.jsonPrimitive?.content
                                if (!name.isNullOrBlank()) add(JsonPrimitive(name))
                            }
                        })
                    })
                }

                "CORTEX_STEP_TYPE_FILE_CHANGE" -> {
                    val sd = step.structuredData
                    val argsObj = step.toolCall?.let {
                        runCatching { json.parseToJsonElement(it.argumentsJson).jsonObject }.getOrNull()
                    }
                    val fallbackPath = argsObj?.get("path")?.jsonPrimitive?.content.orEmpty()
                    val uri = sd["absolutePathUri"]
                        ?: if (fallbackPath.isNotBlank()) WorkspacePathResolver.toNormalizedFileUri(File(workspaceRootDir, fallbackPath)) else ""
                    val changeType = sd["fileChangeType"] ?: "FILE_CHANGE_TYPE_EDIT"
                    put("fileChange", buildJsonObject {
                        put("absolutePathUri", uri)
                        put("fileChangeType", changeType)
                        put("instruction", step.toolAction ?: "Updated $fallbackPath")
                        put("overwrite", sd["overwrite"]?.toBooleanStrictOrNull() ?: true)
                        val targetContent = sd["targetContent"] ?: sd["originalContents"]
                        val replacementContent = sd["replacementContent"] ?: sd["modifiedContents"]
                        if (targetContent != null || replacementContent != null) {
                            put("replacementChunks", buildJsonArray {
                                add(buildJsonObject {
                                    put("targetContent", targetContent ?: "")
                                    put("replacementContent", replacementContent ?: "")
                                    put("startLine", sd["startLine"]?.toIntOrNull() ?: 1)
                                    put("endLine", sd["endLine"]?.toIntOrNull() ?: 1)
                                })
                            })
                        }
                    })
                }

                "CORTEX_STEP_TYPE_GREP_SEARCH" -> {
                    val sd = step.structuredData
                    val resultsArr = sd["resultsJson"]?.let {
                        runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull()
                    } ?: JsonArray(emptyList())
                    put("grepSearch", buildJsonObject {
                        put("searchPathUri", sd["searchPathUri"] ?: WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir))
                        put("query", sd["query"] ?: "")
                        put("totalResults", sd["totalResults"]?.toIntOrNull() ?: resultsArr.size)
                        put("rawOutput", sd["rawOutput"] ?: "")
                        put("results", resultsArr)
                        if (!step.errorMessage.isNullOrBlank()) {
                            put("grepError", step.errorMessage)
                        }
                    })
                }

                "CORTEX_STEP_TYPE_FIND" -> {
                    val sd = step.structuredData
                    put("find", buildJsonObject {
                        put("searchDirectory", sd["searchDirectory"] ?: WorkspacePathResolver.toNormalizedFileUri(workspaceRootDir))
                        put("pattern", sd["pattern"] ?: "")
                        put("totalResults", sd["totalResults"]?.toIntOrNull() ?: 0)
                        put("rawOutput", sd["rawOutput"] ?: "")
                        if (!step.errorMessage.isNullOrBlank()) {
                            put("findError", step.errorMessage)
                        }
                    })
                }

                "CORTEX_STEP_TYPE_RUN_COMMAND" -> {
                    val sd = step.structuredData
                    val cmdLine = sd["commandLine"] ?: ""
                    val combined = sd["combinedOutput"] ?: ""
                    put("runCommand", buildJsonObject {
                        put("commandLine", cmdLine)
                        put("command", cmdLine)
                        put("cwd", sd["cwd"] ?: workspaceRootDir.canonicalFile.path)
                        put("exitCode", sd["exitCode"]?.toIntOrNull() ?: 0)
                        put("stdout", sd["stdout"] ?: "")
                        put("stderr", sd["stderr"] ?: "")
                        put("timedOut", sd["timedOut"]?.toBooleanStrictOrNull() ?: false)
                        put("combinedOutput", buildJsonObject {
                            put("full", combined)
                            put("truncated", combined.take(4096))
                        })
                    })
                }

                "CORTEX_STEP_TYPE_ERROR_MESSAGE" -> {
                    val msg = step.errorMessage ?: "Tool or model execution error"
                    put("errorMessage", buildJsonObject {
                        put("shouldShowUser", true)
                        put("shouldShowModel", true)
                        put("error", buildJsonObject {
                            put("userErrorMessage", msg)
                            put("modelErrorMessage", msg)
                            put("shortError", msg)
                            put("fullError", msg)
                        })
                    })
                }
            }
        }
    }

    private fun buildStreamAgentStateUpdateJson(record: CascadeTrajectoryRecord): JsonObject {
        val stepsSnapshot = record.steps.toList()
        val userInputSteps = stepsSnapshot.filter { it.type == "CORTEX_STEP_TYPE_USER_INPUT" }
        return buildJsonObject {
            put("update", buildJsonObject {
                put("conversationId", record.cascadeId)
                put("trajectoryId", record.trajectoryId)
                put("status", record.status)
                put("fullyIdle", record.fullyIdle)
                put("turnsUpdate", buildJsonObject {
                    put("totalLength", userInputSteps.size)
                    put("turns", buildJsonArray {
                        userInputSteps.forEachIndexed { turnIdx, step ->
                            add(buildJsonObject {
                                put("index", turnIdx)
                                put("startStepIndex", step.stepIndex)
                                put("startTime", step.createdAtIso)
                            })
                        }
                    })
                })
                put("mainTrajectoryUpdate", buildJsonObject {
                    put("stepsUpdate", buildJsonObject {
                        put("indices", buildJsonArray {
                            stepsSnapshot.forEach { add(JsonPrimitive(it.stepIndex)) }
                        })
                        put("steps", buildJsonArray {
                            stepsSnapshot.forEach { add(encodeStepJson(it)) }
                        })
                        put("totalLength", stepsSnapshot.size)
                        put("pageBounds", buildJsonObject {
                            put("startIndex", 0)
                            put("endIndexExclusive", stepsSnapshot.size)
                        })
                    })
                })
            })
        }
    }

    internal fun resolveSafeWorkspaceFile(uriOrPath: String?): File {
        return try {
            WorkspacePathResolver.resolveSafeFile(workspaceRootDir, uriOrPath)
        } catch (se: SecurityException) {
            throw GrpcWebProtocolException(
                GrpcStatus.PERMISSION_DENIED,
                se.message ?: "Path escapes workspace root: $uriOrPath"
            )
        }
    }

    /**
     * Schema-compliant `ReadDirResponse` (P1.2 Phase 6):
     * `ReadDirResponse { repeated ReadDirEntry entries = 1; }`
     * `ReadDirEntry { string uri = 1; FileType file_type = 2; }`
     */
    private fun handleReadDir(req: JsonObject): JsonObject {
        val path = req["uri"]?.jsonPrimitive?.content ?: req["path"]?.jsonPrimitive?.content
        val dir = resolveSafeWorkspaceFile(path)
        if (!dir.exists()) {
            throw GrpcWebProtocolException(GrpcStatus.NOT_FOUND, "Directory not found: ${path ?: "."}")
        }
        if (!dir.isDirectory) {
            throw GrpcWebProtocolException(GrpcStatus.INVALID_ARGUMENT, "Not a directory: ${path ?: "."}")
        }
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        return buildJsonObject {
            put("entries", buildJsonArray {
                for (child in children) {
                    val fileType = when {
                        child.isDirectory -> "FILE_TYPE_DIRECTORY"
                        child.isFile -> "FILE_TYPE_FILE"
                        else -> "FILE_TYPE_UNSPECIFIED"
                    }
                    add(buildJsonObject {
                        put("uri", WorkspacePathResolver.toNormalizedFileUri(child))
                        put("fileType", fileType)
                    })
                }
            })
        }
    }

    /**
     * Schema-compliant `ReadFileResponse` (P1.2 Phase 6):
     * `ReadFileResponse { bytes content = 1; exa.codeium_common_pb.Language language = 2; }`
     */
    private fun handleReadFile(req: JsonObject): JsonObject {
        val path = req["uri"]?.jsonPrimitive?.content ?: req["path"]?.jsonPrimitive?.content
        val file = resolveSafeWorkspaceFile(path)
        if (!file.exists() || !file.isFile) {
            throw GrpcWebProtocolException(GrpcStatus.NOT_FOUND, "File not found: ${file.name}")
        }
        val b64 = Base64.getEncoder().encodeToString(file.readBytes())
        return buildJsonObject {
            put("content", b64)
            put("language", inferCodeiumLanguageEnum(file.extension))
        }
    }

    /**
     * Schema-compliant `WriteFileRequest` / `WriteFileResponse` (P1.2 Phase 6):
     * `WriteFileRequest { string uri = 1; bytes content = 2; bool overwrite = 3; }`
     */
    private fun handleWriteFile(req: JsonObject): JsonObject {
        val path = req["uri"]?.jsonPrimitive?.content ?: req["path"]?.jsonPrimitive?.content
        val file = resolveSafeWorkspaceFile(path)
        val overwrite = req["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true
        if (file.exists() && !overwrite) {
            throw GrpcWebProtocolException(
                GrpcStatus.ALREADY_EXISTS,
                "File already exists and overwrite=false: ${file.name}"
            )
        }
        val contentB64 = req["content"]?.jsonPrimitive?.content ?: ""
        file.parentFile?.mkdirs()
        val bytes = try {
            Base64.getDecoder().decode(contentB64)
        } catch (_: Exception) {
            contentB64.toByteArray(Charsets.UTF_8)
        }
        file.writeBytes(bytes)
        return buildJsonObject {}
    }

    /**
     * Schema-compliant `StatUriResponse` (P1.2 Phase 6):
     * `StatUriResponse { FileType file_type = 1; Timestamp mod_time = 2; string normalized_uri = 3; int64 size = 4; bytes content = 5; }`
     */
    private fun handleStatUri(req: JsonObject): JsonObject {
        val path = req["uri"]?.jsonPrimitive?.content ?: req["path"]?.jsonPrimitive?.content
        val file = resolveSafeWorkspaceFile(path)
        val normalizedUri = WorkspacePathResolver.toNormalizedFileUri(file)
        if (!file.exists()) {
            return buildJsonObject {
                put("fileType", "FILE_TYPE_UNSPECIFIED")
                put("normalizedUri", normalizedUri)
                put("size", "0")
            }
        }
        val fileType = when {
            file.isDirectory -> "FILE_TYPE_DIRECTORY"
            file.isFile -> "FILE_TYPE_FILE"
            else -> "FILE_TYPE_UNSPECIFIED"
        }
        val modTimeIso = Instant.ofEpochMilli(file.lastModified()).toString()
        val maxContentBytes = req["maxContentBytes"]?.jsonPrimitive?.longOrNull ?: 0L
        return buildJsonObject {
            put("fileType", fileType)
            put("modTime", modTimeIso)
            put("normalizedUri", normalizedUri)
            put("size", if (file.isFile) file.length().toString() else "0")
            if (file.isFile && maxContentBytes > 0L && file.length() <= maxContentBytes) {
                put("content", Base64.getEncoder().encodeToString(file.readBytes()))
            }
        }
    }

    /**
     * Schema-compliant `GetTurnDiffResponse` (P1.2 Phase 6):
     * `GetTurnDiffResponse { map<string, FileDiffData> file_diffs = 1; int32 total_additions = 2; int32 total_deletions = 3; CortexStepUserInput user_input = 4; int32 turn_start_index = 5; int32 turn_end_index_exclusive = 6; }`
     */
    private fun handleGetTurnDiff(req: JsonObject): JsonObject {
        val conversationId = req["conversationId"]?.jsonPrimitive?.content ?: DEFAULT_CASCADE_ID
        val requestedStepIndex = req["stepIndex"]?.jsonPrimitive?.intOrNull
        val record = trajectories[conversationId] ?: return buildJsonObject {
            put("fileDiffs", buildJsonObject {})
            put("totalAdditions", 0)
            put("totalDeletions", 0)
            put("turnStartIndex", 0)
            put("turnEndIndexExclusive", 0)
        }

        val stepsSnapshot = record.steps.toList()
        val userStepIndices = stepsSnapshot
            .filter { it.type == "CORTEX_STEP_TYPE_USER_INPUT" }
            .map { it.stepIndex }

        val turnStartIndex = if (requestedStepIndex != null) {
            userStepIndices.lastOrNull { it <= requestedStepIndex } ?: userStepIndices.lastOrNull() ?: 0
        } else {
            userStepIndices.lastOrNull() ?: 0
        }

        val nextUserIdxPos = userStepIndices.indexOf(turnStartIndex)
        val turnEndExclusive = if (nextUserIdxPos >= 0 && nextUserIdxPos + 1 < userStepIndices.size) {
            userStepIndices[nextUserIdxPos + 1]
        } else {
            stepsSnapshot.size
        }

        val diffsForTurn = record.turnFileDiffs[turnStartIndex]?.values?.toList().orEmpty()
        var totalAdd = 0
        var totalDel = 0
        for (d in diffsForTurn) {
            totalAdd += d.additions
            totalDel += d.deletions
        }

        val userStep = stepsSnapshot.firstOrNull { it.stepIndex == turnStartIndex }
        return buildJsonObject {
            put("fileDiffs", buildJsonObject {
                for (d in diffsForTurn) {
                    put(d.fileUri, buildJsonObject {
                        put("additions", d.additions)
                        put("deletions", d.deletions)
                        put("originalContents", d.originalContents)
                        put("modifiedContents", d.modifiedContents)
                        put("isArtifactFile", d.isArtifactFile)
                    })
                }
            })
            put("totalAdditions", totalAdd)
            put("totalDeletions", totalDel)
            if (userStep != null) {
                put("userInput", buildJsonObject {
                    put("userResponse", userStep.userText ?: "")
                    put("items", buildJsonArray {
                        add(buildJsonObject { put("text", userStep.userText ?: "") })
                    })
                })
            }
            put("turnStartIndex", turnStartIndex)
            put("turnEndIndexExclusive", turnEndExclusive)
        }
    }

    /**
     * Controlled Android-compatible `RunCommand` handler (P1.2 Phase 4):
     * `RunCommandRequest { string command = 1; repeated string args = 2; string cwd = 3; int64 timeout_ms = 4; }`
     * `RunCommandResponse { string stdout = 1; string stderr = 2; int32 exit_code = 3; bool timed_out = 4; }`
     */
    private suspend fun handleRunCommand(req: JsonObject): JsonObject {
        val cmd = req["command"]?.jsonPrimitive?.content
            ?: req["commandLine"]?.jsonPrimitive?.content
            ?: ""
        if (cmd.isBlank()) {
            throw GrpcWebProtocolException(GrpcStatus.INVALID_ARGUMENT, "Command must not be blank")
        }
        val args = req["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
        val cwd = req["cwd"]?.jsonPrimitive?.content
        val timeoutMs = req["timeoutMs"]?.jsonPrimitive?.longOrNull
            ?: req["timeout_ms"]?.jsonPrimitive?.longOrNull

        val outcome = localFileTools.commandExecutor.executeCommand(
            command = cmd,
            args = args,
            cwdUriOrPath = cwd,
            timeoutMs = timeoutMs
        )
        if (outcome.blockedReason != null) {
            throw GrpcWebProtocolException(
                GrpcStatus.PERMISSION_DENIED,
                outcome.stderr.ifBlank { outcome.blockedReason ?: "Command blocked by workspace sandbox" }
            )
        }
        return buildJsonObject {
            put("stdout", outcome.stdout)
            put("stderr", outcome.stderr)
            put("exitCode", outcome.exitCode)
            put("timedOut", outcome.timedOut)
        }
    }

    private fun handleKillCommand(req: JsonObject): JsonObject {
        val commandId = req["commandId"]?.jsonPrimitive?.content
            ?: req["terminalId"]?.jsonPrimitive?.content
        localFileTools.commandExecutor.killCommand(commandId)
        return buildJsonObject {}
    }

    private fun handleGetActiveCommand(): JsonObject {
        val active = localFileTools.commandExecutor.getActiveCommands()
        return buildJsonObject {
            put("commands", buildJsonArray {
                for (c in active) {
                    add(buildJsonObject {
                        put("commandId", c.commandId)
                        put("commandLine", c.commandLine)
                        put("cwd", c.cwd)
                    })
                }
            })
        }
    }

    private fun inferCodeiumLanguageEnum(extension: String): String = when (extension.lowercase()) {
        "kt", "kts" -> "LANGUAGE_KOTLIN"
        "java" -> "LANGUAGE_JAVA"
        "js", "mjs", "cjs" -> "LANGUAGE_JAVASCRIPT"
        "ts", "tsx" -> "LANGUAGE_TYPESCRIPT"
        "py" -> "LANGUAGE_PYTHON"
        "md", "markdown" -> "LANGUAGE_MARKDOWN"
        "json" -> "LANGUAGE_JSON"
        "html", "htm" -> "LANGUAGE_HTML"
        "css" -> "LANGUAGE_CSS"
        "xml" -> "LANGUAGE_XML"
        "sh", "bash" -> "LANGUAGE_SHELL"
        "yaml", "yml" -> "LANGUAGE_YAML"
        else -> "LANGUAGE_UNSPECIFIED"
    }

    private fun errorResponse(status: GrpcStatus, message: String): GrpcWebHttpResponse {
        return GrpcWebHttpResponse(
            statusCode = 200,
            body = GrpcWebFrameCodec.encodeErrorTrailerOnly(status, message)
        )
    }
}
