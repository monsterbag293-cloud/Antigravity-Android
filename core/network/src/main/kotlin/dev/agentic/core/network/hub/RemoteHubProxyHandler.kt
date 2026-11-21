package dev.agentic.core.network.hub

import dev.agentic.core.network.jetski.GrpcStatus
import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import dev.agentic.core.network.jetski.GrpcWebHttpRequest
import dev.agentic.core.network.jetski.GrpcWebHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * Android-side RPC Relay that forwards Connect-RPC and gRPC-Web requests from the
 * original Jetski Web frontend to the authenticated desktop Antigravity Hub (Phase P3.2).
 *
 * Protocol Preservation Rules:
 * 1. Preserves original request payloads (whether binary protobuf or JSON) without transcoding.
 * 2. Preserves incoming Connect-RPC and gRPC-Web headers, injecting the verified `x-codeium-csrf-token`.
 * 3. Preserves upstream gRPC-Web framing (data frames `0x00`, trailer frames `0x80`).
 * 4. Preserves status codes and response headers while adding required WebView CORS headers.
 * 5. Supports streaming responses without buffering where required.
 */
class RemoteHubProxyHandler(
    val connectionManager: RemoteHubConnectionManager,
    val conversationCache: ConversationCacheStore? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val quotaSnapshotByGroup = ConcurrentHashMap<String, DesktopModelCatalog.ModelQuotaSnapshot>()

    @Volatile
    private var streamProxyServer: LocalStreamProxyServer? = null

    /**
     * Returns the port of a lazy-started loopback HTTP/1.1 chunked streaming proxy for
     * `StreamAgentStateUpdates`, bypassing WebView's `shouldInterceptRequest` stream buffering.
     */
    @Synchronized
    fun getOrStartStreamProxyPort(): Int {
        val existing = streamProxyServer
        if (existing != null) return existing.port
        val created = LocalStreamProxyServer(connectionManager)
        streamProxyServer = created
        return created.port
    }

    companion object {
        private val EXCLUDED_REQUEST_HEADERS = setOf(
            "host",
            "connection",
            "content-length",
            "transfer-encoding",
            "content-type",
            "x-antigravity-post-body-b64"
        )
    }

    /**
     * Relays a unary or buffered server response from the desktop Hub.
     */
    suspend fun relayRequest(request: GrpcWebHttpRequest): GrpcWebHttpResponse = withContext(Dispatchers.IO) {
        val config = connectionManager.getActiveConfig()
            ?: return@withContext createErrorResponse(
                GrpcStatus.UNAVAILABLE,
                "Desktop Hub is not connected. Configure Desktop Hub connection or explicitly switch to Gemini Fallback mode."
            )

        val client = connectionManager.getActiveHttpClient()
            ?: return@withContext createErrorResponse(
                GrpcStatus.UNAVAILABLE,
                "Desktop Hub HTTP client is unavailable. Reconnect to Desktop Hub."
            )

        val targetUrl = "${config.baseUrl}${request.path}"
        val contentType = request.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON

        val requestBody = if (request.body.isNotEmpty() || request.method.equals("POST", ignoreCase = true)) {
            request.body.toRequestBody(contentType.toMediaTypeOrNull())
        } else {
            null
        }

        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .method(request.method, requestBody)

        // Forward frontend headers, skipping hop-by-hop headers
        for ((key, value) in request.headers) {
            if (!EXCLUDED_REQUEST_HEADERS.contains(key.lowercase())) {
                requestBuilder.header(key, value)
            }
        }

        // Inject desktop Hub authentication and protocol markers
        requestBuilder.header(RemoteHubConnectionManager.HEADER_CSRF_TOKEN, config.csrfToken)
        if (!request.headers.keys.any { it.equals(RemoteHubConnectionManager.HEADER_CONNECT_PROTOCOL, ignoreCase = true) }) {
            requestBuilder.header(RemoteHubConnectionManager.HEADER_CONNECT_PROTOCOL, "1")
        }

        val reqJson = parseRequestJsonOrNull(request.body)
        val requestedCascadeId = reqJson?.get("cascadeId").asStrOrNull()
            ?: reqJson?.get("conversationId").asStrOrNull()
        val backendId = parseBackendId(reqJson)
        val requestedProjectIds = (reqJson?.get("ids") ?: reqJson?.get("projectIds"))
            .asArrOrNull()
            ?.mapNotNull { it.asStrOrNull() }
            ?.toSet()
            .orEmpty()

        try {
            if (request.path.endsWith("/DeleteCascadeTrajectory") || request.path.endsWith("/DeleteCascade")) {
                requestedCascadeId?.takeIf { it.isNotBlank() }?.let { conversationCache?.markDeleted(it) }
            }

            val isAudioStream = request.path.contains("StreamAudioTranscription", ignoreCase = true)
            val isAgentStateStream = request.path.endsWith("/StreamAgentStateUpdates")
            val isStreaming = isAudioStream || isAgentStateStream || isStreamingRpc(request.path)
            if (isAudioStream) {
                val liveStreamingClient = client.newBuilder()
                    .readTimeout(12, TimeUnit.SECONDS)
                    .build()
                val call = liveStreamingClient.newCall(requestBuilder.build())
                val response = call.execute()
                val responseHeaders = mutableMapOf<String, String>()

                for ((name, value) in response.headers) {
                    if (!name.equals("content-type", ignoreCase = true)) {
                        responseHeaders[name] = value
                    }
                }

                responseHeaders["Access-Control-Allow-Origin"] = "*"
                responseHeaders["Cache-Control"] = "no-cache"

                val respContentType = response.header("content-type") ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON
                val rawStream = response.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
                val finalStream = object : java.io.FilterInputStream(rawStream) {
                    override fun close() {
                        try {
                            runCatching { call.cancel() }
                            runCatching { response.close() }
                        } finally {
                            runCatching { super.close() }
                        }
                    }
                }

                GrpcWebHttpResponse(
                    statusCode = response.code,
                    contentType = respContentType,
                    headers = responseHeaders,
                    body = ByteArray(0),
                    bodyStream = finalStream
                )
            } else if (isAgentStateStream) {
                val agentStreamingClient = client.newBuilder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
                val call = agentStreamingClient.newCall(requestBuilder.build())
                val response = call.execute()
                val responseHeaders = mutableMapOf<String, String>()

                for ((name, value) in response.headers) {
                    if (!name.equals("content-type", ignoreCase = true)) {
                        responseHeaders[name] = value
                    }
                }

                responseHeaders["Access-Control-Allow-Origin"] = "*"
                responseHeaders["Cache-Control"] = "no-cache"

                val respContentType = response.header("content-type") ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON
                if (!response.isSuccessful) {
                    val errBody = response.use { it.body?.bytes() ?: ByteArray(0) }
                    val cachedSnap = requestedCascadeId?.let { conversationCache?.getCachedStreamSnapshotResponse(it) }
                    if (cachedSnap != null) {
                        val isConnectStream = respContentType.contains("connect+", ignoreCase = true)
                        GrpcWebHttpResponse(
                            statusCode = 200,
                            contentType = respContentType,
                            headers = responseHeaders,
                            body = encodeStreamSingleMessage(cachedSnap, isConnectStream),
                            bodyStream = null
                        )
                    } else {
                        GrpcWebHttpResponse(
                            statusCode = response.code,
                            contentType = respContentType,
                            headers = responseHeaders,
                            body = errBody,
                            bodyStream = null
                        )
                    }
                } else {
                    val rawStream = response.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
                    val finalStream = object : java.io.FilterInputStream(rawStream) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            if (n > 0) {
                                val text = String(b, off, minOf(n, 300), Charsets.UTF_8)
                                if (text.contains("CASCADE_RUN_STATUS") || text.contains("fullyIdle") || text.contains("grpc-status")) {
                                    runCatching { android.util.Log.d("RemoteHubProxy", "AgentState chunk len=$n preview=${text.replace("\n", " ").take(160)}") }
                                }
                            }
                            return n
                        }
                        override fun close() {
                            runCatching { android.util.Log.d("RemoteHubProxy", "AgentState stream closed") }
                            try {
                                runCatching { call.cancel() }
                                runCatching { response.close() }
                            } finally {
                                runCatching { super.close() }
                            }
                        }
                    }

                    GrpcWebHttpResponse(
                        statusCode = response.code,
                        contentType = respContentType,
                        headers = responseHeaders,
                        body = ByteArray(0),
                        bodyStream = finalStream
                    )
                }
            } else if (isStreaming) {
                val isProjectOrSummaryStream =
                    request.path.endsWith("/ProjectUpdatesStream") ||
                        request.path.endsWith("/JetboxSubscribeToSummaries")
                val (readTimeoutMs, initialTimeoutMs, maxTimeoutMs) = when {
                    isProjectOrSummaryStream && backendId != 0 -> Triple(4500L, 3200L, 4000L)
                    isProjectOrSummaryStream -> Triple(1800L, 500L, 900L)
                    else -> Triple(1200L, 120L, 300L)
                }
                val streamingClient = client.newBuilder()
                    .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                val call = streamingClient.newCall(requestBuilder.build())
                val response = call.execute()
                val responseHeaders = mutableMapOf<String, String>()

                for ((name, value) in response.headers) {
                    if (!name.equals("content-type", ignoreCase = true)) {
                        responseHeaders[name] = value
                    }
                }

                responseHeaders["Access-Control-Allow-Origin"] = "*"
                responseHeaders["Cache-Control"] = "no-cache"

                val respContentType = response.header("content-type") ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON
                val snapshotBytes = try {
                    response.use { resp ->
                        val rawStream = resp.body?.byteStream()
                        if (rawStream == null) {
                            ByteArray(0)
                        } else {
                            readStreamSnapshot(
                                input = rawStream,
                                call = call,
                                contentType = respContentType,
                                httpSuccess = resp.isSuccessful,
                                initialTimeoutMs = initialTimeoutMs,
                                maxTimeoutMs = maxTimeoutMs
                            )
                        }
                    }
                } finally {
                    call.cancel()
                }

                val isLocalBackendReq = backendId == 0
                val finalSnapshotBytes = when {
                    response.isSuccessful && request.path.endsWith("/JetboxSubscribeToSummaries") && conversationCache != null -> {
                        transformSummariesStreamWithCache(snapshotBytes, respContentType, includeZeroProjectCache = isLocalBackendReq)
                    }
                    response.isSuccessful && request.path.endsWith("/ProjectUpdatesStream") && conversationCache != null -> {
                        transformProjectUpdatesStreamWithCache(snapshotBytes, respContentType, backendId)
                    }
                    else -> snapshotBytes
                }

                GrpcWebHttpResponse(
                    statusCode = response.code,
                    contentType = respContentType,
                    headers = responseHeaders,
                    body = finalSnapshotBytes,
                    bodyStream = null
                )
            } else {
                val isQuotaSummary = request.path.endsWith("/RetrieveUserQuotaSummary")
                val unaryTimeoutSec = when {
                    isQuotaSummary -> 5L
                    request.path.endsWith("/GetLoadCodeAssist") -> 6L
                    request.path.endsWith("/GetTokenBase") -> 8L
                    request.path.endsWith("/GetAllPlugins") ||
                        request.path.endsWith("/GetAllSkills") ||
                        request.path.endsWith("/GetAllRules") ||
                        request.path.endsWith("/GetCustomizationStates") -> 10L
                    else -> null
                }
                val unaryClient = if (unaryTimeoutSec != null) {
                    client.newBuilder()
                        .readTimeout(unaryTimeoutSec, TimeUnit.SECONDS)
                        .callTimeout(unaryTimeoutSec + 1L, TimeUnit.SECONDS)
                        .build()
                } else {
                    client
                }
                if (isQuotaSummary) {
                    try {
                        unaryClient.newCall(requestBuilder.build()).execute().use { response ->
                            val rawBytes = response.body?.bytes() ?: ByteArray(0)
                            val responseHeaders = mutableMapOf<String, String>()
                            for ((name, value) in response.headers) {
                                if (!name.equals("content-type", ignoreCase = true)) {
                                    responseHeaders[name] = value
                                }
                            }
                            responseHeaders["Access-Control-Allow-Origin"] = "*"
                            responseHeaders["Cache-Control"] = "no-cache"
                            val respContentType = response.header("content-type") ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON
                            val responseBytes = resolveQuotaSummaryResponse(rawBytes, respContentType, response.isSuccessful)
                            return@withContext GrpcWebHttpResponse(
                                statusCode = 200,
                                contentType = respContentType,
                                headers = responseHeaders,
                                body = responseBytes
                            )
                        }
                    } catch (_: IOException) {
                        val fallbackContentType = if (contentType.contains("grpc-web", ignoreCase = true)) {
                            GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON
                        } else {
                            "application/json"
                        }
                        val fallbackBytes = resolveQuotaSummaryResponse(ByteArray(0), fallbackContentType, false)
                        return@withContext GrpcWebHttpResponse(
                            statusCode = 200,
                            contentType = fallbackContentType,
                            headers = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Cache-Control" to "no-cache"
                            ),
                            body = fallbackBytes
                        )
                    }
                }
                unaryClient.newCall(requestBuilder.build()).execute().use { response ->
                    val rawBytes = response.body?.bytes() ?: ByteArray(0)
                    val responseHeaders = mutableMapOf<String, String>()

                    for ((name, value) in response.headers) {
                        responseHeaders[name] = value
                    }

                    // Add CORS and caching headers for WebView
                    responseHeaders["Access-Control-Allow-Origin"] = "*"
                    responseHeaders["Cache-Control"] = "no-cache"

                    val respContentType = response.header("content-type") ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON

                    val responseBytes = when {
                        response.isSuccessful && request.path.endsWith("/GetUserStatus") ->
                            transformUnaryJsonResponse(rawBytes, respContentType) { root ->
                                DesktopModelCatalog.augmentUserStatusJson(root, quotaSnapshotByGroup)
                            }

                        request.path.endsWith("/RetrieveUserQuotaSummary") ->
                            resolveQuotaSummaryResponse(rawBytes, respContentType, response.isSuccessful)

                        request.path.endsWith("/GetAllCascadeTrajectories") ->
                            transformAllTrajectoriesWithCache(rawBytes, respContentType, response.isSuccessful)

                        request.path.endsWith("/ReadProjects") ->
                            resolveReadProjectsWithCache(rawBytes, respContentType, response.isSuccessful, requestedProjectIds)

                        request.path.endsWith("/ListProjects") ->
                            resolveListProjectsWithCache(rawBytes, respContentType, response.isSuccessful, backendId)

                        request.path.endsWith("/GetMendelFlags") && response.isSuccessful ->
                            augmentMendelFlagsResponse(rawBytes, respContentType)

                        request.path.endsWith("/GetCascadeTrajectory") ->
                            resolveTrajectoryWithCache(rawBytes, respContentType, response.isSuccessful, requestedCascadeId)

                        request.path.endsWith("/GetCascadeTrajectorySteps") -> {
                            val stepOffset = reqJson?.get("stepOffset").asIntOrNull() ?: 0
                            resolveStepsWithCache(rawBytes, respContentType, response.isSuccessful, requestedCascadeId, stepOffset)
                        }

                        else -> rawBytes
                    }

                    if (response.isSuccessful && request.path.endsWith("/SendUserCascadeMessage") && requestedCascadeId != null && conversationCache != null) {
                        val hasWorkspace = reqJson?.get("workspaceUris").asArrOrNull()?.isNotEmpty() == true ||
                            reqJson?.get("cascadeConfig").asObjOrNull()?.get("workspaceUris").asArrOrNull()?.isNotEmpty() == true ||
                            reqJson?.get("metadata").asObjOrNull()?.get("workspaces").asArrOrNull()?.isNotEmpty() == true
                        val metaProj = reqJson?.get("projectId").asStrOrNull()
                        if (!hasWorkspace && (metaProj.isNullOrBlank() || metaProj == ConversationCacheStore.OUTSIDE_OF_PROJECT_ID)) {
                            val snippet = reqJson?.get("items").asArrOrNull()?.firstOrNull()?.asObjOrNull()
                                ?.get("text").asStrOrNull()?.trim()?.take(60)
                                ?: "New Conversation"
                            conversationCache.recordStartedZeroProject(requestedCascadeId, snippet)
                        }
                    }

                    val finalStatusCode = if (
                        request.path.endsWith("/RetrieveUserQuotaSummary") ||
                        (requestedCascadeId != null && conversationCache?.isTrackedZeroProject(requestedCascadeId) == true &&
                            (request.path.endsWith("/GetCascadeTrajectory") || request.path.endsWith("/GetCascadeTrajectorySteps")))
                    ) {
                        200
                    } else {
                        response.code
                    }

                    GrpcWebHttpResponse(
                        statusCode = finalStatusCode,
                        contentType = respContentType,
                        headers = responseHeaders,
                        body = responseBytes
                    )
                }
            }
        } catch (e: ConnectException) {
            createErrorResponse(
                GrpcStatus.UNAVAILABLE,
                "Lost connection to Desktop Hub at ${config.host}:${config.port}. Ensure Antigravity desktop remains running."
            )
        } catch (e: SocketTimeoutException) {
            createErrorResponse(
                GrpcStatus.DEADLINE_EXCEEDED,
                "Desktop Hub request to ${request.path} timed out."
            )
        } catch (e: SSLHandshakeException) {
            createErrorResponse(
                GrpcStatus.UNAUTHENTICATED,
                "TLS handshake failure with Desktop Hub: ${e.message}"
            )
        } catch (e: CancellationException) {
            createErrorResponse(
                GrpcStatus.CANCELED,
                "Relayed RPC request was canceled by client."
            )
        } catch (e: IOException) {
            createErrorResponse(
                GrpcStatus.INTERNAL,
                "I/O error communicating with Desktop Hub: ${e.message}"
            )
        } catch (e: Exception) {
            createErrorResponse(
                GrpcStatus.UNKNOWN,
                "Unexpected relay error: ${e.message}"
            )
        }
    }

    private fun JsonElement?.asObjOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArrOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement?.asStrOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun parseBackendId(reqJson: JsonObject?): Int {
        val el = reqJson?.get("backend") ?: return 0
        el.asIntOrNull()?.let { return it }
        val str = el.asStrOrNull().orEmpty()
        return when {
            str.contains("ENTERPRISE", ignoreCase = true) || str == "3" -> 3
            str.contains("REMOTE", ignoreCase = true) || str.contains("CLOUD", ignoreCase = true) || str == "2" -> 2
            else -> 0
        }
    }

    private fun parseRequestJsonOrNull(body: ByteArray): JsonObject? {
        if (body.isEmpty()) return null
        return runCatching {
            val text = if (body[0] == 0.toByte() && body.size >= 5) {
                GrpcWebFrameCodec.extractFirstRequestJson(body)
            } else {
                body.toString(Charsets.UTF_8)
            }
            json.parseToJsonElement(text).asObjOrNull()
        }.getOrNull()
    }

    private fun extractUnaryJsonOrNull(rawBytes: ByteArray, contentType: String): Pair<JsonObject, Boolean>? {
        if (rawBytes.isEmpty()) return null
        val isGrpcWeb = contentType.contains("grpc-web", ignoreCase = true) || (rawBytes[0] == 0.toByte())
        val jsonText = if (isGrpcWeb) {
            val frames = runCatching { GrpcWebFrameCodec.decodeFrames(rawBytes) }.getOrNull() ?: return null
            val dataFrame = frames.firstOrNull { !it.isTrailer } ?: return null
            dataFrame.utf8Text
        } else {
            rawBytes.toString(Charsets.UTF_8)
        }
        val parsed = runCatching { json.parseToJsonElement(jsonText).asObjOrNull() }.getOrNull() ?: return null
        return parsed to isGrpcWeb
    }

    private fun encodeUnaryLike(obj: JsonObject, isGrpcWeb: Boolean): ByteArray {
        val text = obj.toString()
        return if (isGrpcWeb) GrpcWebFrameCodec.encodeUnaryResponse(text) else text.toByteArray(Charsets.UTF_8)
    }

    private fun resolveQuotaSummaryResponse(rawBytes: ByteArray, contentType: String, httpSuccess: Boolean): ByteArray {
        val isGrpcWeb = contentType.contains("grpc-web", ignoreCase = true) || (rawBytes.isNotEmpty() && rawBytes[0] == 0.toByte())
        val extracted = if (httpSuccess) extractUnaryJsonOrNull(rawBytes, contentType) else null
        if (extracted != null) {
            val updated = DesktopModelCatalog.augmentQuotaSummaryJson(extracted.first, quotaSnapshotByGroup)
            return encodeUnaryLike(updated, extracted.second)
        }
        val fallback = buildJsonObject {
            put("response", buildJsonObject {
                put("groups", DesktopModelCatalog.buildDefaultQuotaGroups(quotaSnapshotByGroup))
            })
        }
        return encodeUnaryLike(fallback, isGrpcWeb)
    }

    private fun augmentMendelFlagsResponse(rawBytes: ByteArray, contentType: String): ByteArray {
        return transformUnaryJsonResponse(rawBytes, contentType) { root ->
            val expConfig = root["experimentConfig"].asObjOrNull() ?: JsonObject(emptyMap())
            val experiments = expConfig["experiments"].asArrOrNull() ?: buildJsonArray {}
            var hasRemoteAiEnabled = false
            val updatedExperiments = buildJsonArray {
                for (el in experiments) {
                    val obj = el.asObjOrNull()
                    val key = obj?.get("keyString").asStrOrNull()
                    if (key == "enable-remote-ai" && obj != null) {
                        hasRemoteAiEnabled = true
                        val patched = buildJsonObject {
                            obj.forEach { (k, v) -> if (k != "disabled") put(k, v) }
                            put("disabled", false)
                            if (!obj.containsKey("source")) {
                                put("source", "EXPERIMENT_SOURCE_LANGUAGE_SERVER")
                            }
                        }
                        add(patched)
                    } else {
                        add(el)
                    }
                }
                if (!hasRemoteAiEnabled) {
                    add(buildJsonObject {
                        put("keyString", "enable-remote-ai")
                        put("disabled", false)
                        put("source", "EXPERIMENT_SOURCE_LANGUAGE_SERVER")
                    })
                }
            }
            val updatedConfig = buildJsonObject {
                expConfig.forEach { (k, v) ->
                    if (k != "experiments") put(k, v)
                }
                put("experiments", updatedExperiments)
            }
            buildJsonObject {
                root.forEach { (k, v) ->
                    if (k != "experimentConfig") put(k, v)
                }
                put("experimentConfig", updatedConfig)
            }
        }
    }

    private fun transformSummariesStreamWithCache(
        rawBytes: ByteArray,
        contentType: String,
        includeZeroProjectCache: Boolean = true
    ): ByteArray {
        val cache = conversationCache ?: return rawBytes
        return transformUnaryJsonResponse(rawBytes, contentType, synthesizeIfEmpty = true) { root ->
            val updates = root["updates"].asObjOrNull()
            val effective = if (updates == null || updates.isEmpty()) {
                cache.getCachedAllSummariesMap() ?: (updates ?: JsonObject(emptyMap()))
            } else {
                cache.updateFromSummariesMap(updates)
                cache.cacheAllSummariesMap(updates)
                updates
            }
            val merged = if (includeZeroProjectCache) cache.mergeIntoSummariesMap(effective) else effective
            buildJsonObject {
                root.forEach { (k, v) ->
                    if (k == "updates") put(k, merged) else put(k, v)
                }
                if (!root.containsKey("updates")) put("updates", merged)
            }
        }
    }

    private fun transformProjectUpdatesStreamWithCache(
        rawBytes: ByteArray,
        contentType: String,
        backendId: Int = 0
    ): ByteArray {
        val cache = conversationCache ?: return rawBytes
        return transformUnaryJsonResponse(rawBytes, contentType, synthesizeIfEmpty = true) { root ->
            val updateObj = root["update"].asObjOrNull()
            val projectListObj = root["projectList"].asObjOrNull()
                ?: updateObj?.get("projectList").asObjOrNull()
            val ids = projectListObj?.get("projectIds").asArrOrNull()?.mapNotNull { it.asStrOrNull() }.orEmpty()
            val cleanIds = ids.filter { id -> !cache.isSidecarProjectId(id) }
            val nonOutsideIds = cleanIds.filter { id -> id != ConversationCacheStore.OUTSIDE_OF_PROJECT_ID }
            if (nonOutsideIds.isNotEmpty()) {
                cache.cacheProjectListIds(ids, backendId)
            }
            val cachedIds = (cache.getCachedProjectListIds(backendId) + cache.getCachedProjectListIds(2))
                .distinct()
                .filter { !cache.isSidecarProjectId(it) }
            val mergedIds = (cachedIds + cleanIds).distinct()
            if (mergedIds.isEmpty()) return@transformUnaryJsonResponse null
            if (projectListObj != null && mergedIds == ids) return@transformUnaryJsonResponse null
            val updatedListObj = buildJsonObject {
                projectListObj?.forEach { (pk, pv) ->
                    if (pk == "projectIds") {
                        put(pk, buildJsonArray { mergedIds.forEach { add(JsonPrimitive(it)) } })
                    } else put(pk, pv)
                }
                if (projectListObj == null || !projectListObj.containsKey("projectIds")) {
                    put("projectIds", buildJsonArray { mergedIds.forEach { add(JsonPrimitive(it)) } })
                }
            }
            if (root.isEmpty()) {
                buildJsonObject {
                    put("projectList", updatedListObj)
                }
            } else if (updateObj != null) {
                buildJsonObject {
                    root.forEach { (k, v) ->
                        if (k == "update") {
                            put(k, buildJsonObject {
                                updateObj.forEach { (uk, uv) ->
                                    if (uk == "projectList") put(uk, updatedListObj) else put(uk, uv)
                                }
                            })
                        } else put(k, v)
                    }
                }
            } else {
                buildJsonObject {
                    root.forEach { (k, v) ->
                        if (k == "projectList") put(k, updatedListObj) else put(k, v)
                    }
                    if (!root.containsKey("projectList")) put("projectList", updatedListObj)
                }
            }
        }
    }

    private fun resolveListProjectsWithCache(
        rawBytes: ByteArray,
        contentType: String,
        httpSuccess: Boolean,
        backendId: Int = 0
    ): ByteArray {
        val cache = conversationCache ?: return rawBytes
        val extracted = if (httpSuccess) extractUnaryJsonOrNull(rawBytes, contentType) else null
        val ids = extracted?.first?.get("projectIds").asArrOrNull()?.mapNotNull { it.asStrOrNull() }.orEmpty()
        if (ids.isNotEmpty()) {
            cache.cacheProjectListIds(ids, backendId)
            return rawBytes
        }
        val cachedIds = cache.getCachedProjectListIds(backendId)
        if (cachedIds.isNotEmpty()) {
            val isGrpcWeb = extracted?.second ?: (contentType.contains("grpc-web", ignoreCase = true) || (rawBytes.isNotEmpty() && rawBytes[0] == 0.toByte()))
            val fallback = buildJsonObject {
                put("projectIds", buildJsonArray { cachedIds.forEach { add(JsonPrimitive(it)) } })
            }
            return encodeUnaryLike(fallback, isGrpcWeb)
        }
        return rawBytes
    }

    private fun resolveReadProjectsWithCache(
        rawBytes: ByteArray,
        contentType: String,
        httpSuccess: Boolean,
        requestedIds: Set<String> = emptySet()
    ): ByteArray {
        val cache = conversationCache ?: return rawBytes
        val extracted = if (httpSuccess) extractUnaryJsonOrNull(rawBytes, contentType) else null
        if (extracted != null) {
            val merged = cache.mergeReadProjectsResponse(extracted.first, requestedIds)
            val isConnectStream = contentType.contains("connect+", ignoreCase = true)
            return if (isConnectStream) {
                encodeStreamSingleMessage(merged, isConnectStream = true)
            } else {
                encodeUnaryLike(merged, extracted.second)
            }
        }
        val cached = cache.getCachedReadProjectsResponse(requestedIds)
        if (cached != null) {
            val isGrpcWeb = contentType.contains("grpc-web", ignoreCase = true) || (rawBytes.isNotEmpty() && rawBytes[0] == 0.toByte())
            return encodeUnaryLike(cached, isGrpcWeb)
        }
        return rawBytes
    }

    private fun transformAllTrajectoriesWithCache(rawBytes: ByteArray, contentType: String, httpSuccess: Boolean): ByteArray {
        val cache = conversationCache ?: return rawBytes
        val extracted = if (httpSuccess) extractUnaryJsonOrNull(rawBytes, contentType) else null
        if (extracted != null) {
            val summaries = extracted.first["trajectorySummaries"].asObjOrNull() ?: JsonObject(emptyMap())
            val effective = if (summaries.isEmpty()) {
                cache.getCachedAllSummariesMap() ?: summaries
            } else {
                cache.updateFromSummariesMap(summaries)
                cache.cacheAllSummariesMap(summaries)
                summaries
            }
            val merged = cache.mergeIntoSummariesMap(effective)
            val updated = buildJsonObject {
                extracted.first.forEach { (k, v) ->
                    if (k == "trajectorySummaries") put(k, merged) else put(k, v)
                }
                if (!extracted.first.containsKey("trajectorySummaries")) put("trajectorySummaries", merged)
            }
            return encodeUnaryLike(updated, extracted.second)
        }
        val cachedAll = cache.mergeIntoSummariesMap(cache.getCachedAllSummariesMap() ?: JsonObject(emptyMap()))
        if (cachedAll.isNotEmpty()) {
            val isGrpcWeb = contentType.contains("grpc-web", ignoreCase = true) || (rawBytes.isNotEmpty() && rawBytes[0] == 0.toByte())
            return encodeUnaryLike(buildJsonObject { put("trajectorySummaries", cachedAll) }, isGrpcWeb)
        }
        return rawBytes
    }

    private fun resolveTrajectoryWithCache(
        rawBytes: ByteArray,
        contentType: String,
        httpSuccess: Boolean,
        cascadeId: String?
    ): ByteArray {
        val cache = conversationCache ?: return rawBytes
        val extracted = if (httpSuccess) extractUnaryJsonOrNull(rawBytes, contentType) else null
        if (extracted != null && cascadeId != null && extracted.first.containsKey("trajectory")) {
            cache.updateFromTrajectoryResponse(cascadeId, extracted.first)
            return rawBytes
        }
        if (cascadeId != null) {
            val cached = cache.getCachedTrajectoryResponse(cascadeId)
            if (cached != null) {
                val isGrpcWeb = contentType.contains("grpc-web", ignoreCase = true) || (rawBytes.isNotEmpty() && rawBytes[0] == 0.toByte())
                return encodeUnaryLike(cached, isGrpcWeb)
            }
        }
        return rawBytes
    }

    private fun resolveStepsWithCache(
        rawBytes: ByteArray,
        contentType: String,
        httpSuccess: Boolean,
        cascadeId: String?,
        stepOffset: Int
    ): ByteArray {
        val cache = conversationCache ?: return rawBytes
        val extracted = if (httpSuccess) extractUnaryJsonOrNull(rawBytes, contentType) else null
        if (extracted != null && cascadeId != null && extracted.first.containsKey("steps")) {
            if (stepOffset == 0) {
                cache.updateFromStepsResponse(cascadeId, extracted.first)
            }
            return rawBytes
        }
        if (cascadeId != null) {
            val cached = cache.getCachedStepsResponse(cascadeId, stepOffset)
            if (cached != null) {
                val isGrpcWeb = contentType.contains("grpc-web", ignoreCase = true) || (rawBytes.isNotEmpty() && rawBytes[0] == 0.toByte())
                return encodeUnaryLike(cached, isGrpcWeb)
            }
        }
        return rawBytes
    }

    /**
     * Relays a server-streaming RPC from the desktop Hub, emitting response byte chunks
     * as they arrive over the network stream.
     */
    suspend fun relayStreamingRequest(
        request: GrpcWebHttpRequest,
        onChunkReceived: suspend (ByteArray) -> Unit
    ): GrpcWebHttpResponse = withContext(Dispatchers.IO) {
        val config = connectionManager.getActiveConfig()
            ?: return@withContext createErrorResponse(
                GrpcStatus.UNAVAILABLE,
                "Desktop Hub is not connected."
            )

        val client = connectionManager.getActiveHttpClient()
            ?: return@withContext createErrorResponse(
                GrpcStatus.UNAVAILABLE,
                "Desktop Hub HTTP client is unavailable."
            )

        val targetUrl = "${config.baseUrl}${request.path}"
        val contentType = request.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON

        val requestBody = request.body.toRequestBody(contentType.toMediaTypeOrNull())

        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .method(request.method, requestBody)
            .header(RemoteHubConnectionManager.HEADER_CSRF_TOKEN, config.csrfToken)
            .header(RemoteHubConnectionManager.HEADER_CONNECT_PROTOCOL, "1")

        for ((key, value) in request.headers) {
            if (!EXCLUDED_REQUEST_HEADERS.contains(key.lowercase())) {
                requestBuilder.header(key, value)
            }
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val fullBaos = ByteArrayOutputStream()
                val responseHeaders = mutableMapOf<String, String>()
                for ((name, value) in response.headers) {
                    if (!name.equals("content-type", ignoreCase = true)) {
                        responseHeaders[name] = value
                    }
                }
                responseHeaders["Access-Control-Allow-Origin"] = "*"
                responseHeaders["Cache-Control"] = "no-cache"

                val inputStream = response.body?.byteStream()
                if (inputStream != null) {
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val chunk = buffer.copyOf(bytesRead)
                        fullBaos.write(chunk)
                        onChunkReceived(chunk)
                    }
                }

                GrpcWebHttpResponse(
                    statusCode = response.code,
                    contentType = response.header("content-type") ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                    headers = responseHeaders,
                    body = fullBaos.toByteArray()
                )
            }
        } catch (e: Exception) {
            val errorResp = createErrorResponse(GrpcStatus.UNAVAILABLE, "Streaming error: ${e.message}")
            onChunkReceived(errorResp.body)
            errorResp
        }
    }

    private fun createErrorResponse(status: GrpcStatus, message: String): GrpcWebHttpResponse {
        val trailerBytes = GrpcWebFrameCodec.encodeTrailerFrame(status, message)
        return GrpcWebHttpResponse(
            statusCode = 200,
            contentType = GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
            headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache",
                "grpc-status" to status.code.toString(),
                "grpc-message" to message
            ),
            body = trailerBytes
        )
    }

    private suspend fun readStreamSnapshot(
        input: java.io.InputStream,
        call: okhttp3.Call,
        contentType: String,
        httpSuccess: Boolean,
        initialTimeoutMs: Long = 120L,
        maxTimeoutMs: Long = 300L
    ): ByteArray = readFramedSnapshotInternal(
        input = input,
        call = call,
        contentType = contentType,
        httpSuccess = httpSuccess,
        initialTimeoutMs = initialTimeoutMs,
        maxTimeoutMs = maxTimeoutMs,
        agentStateMode = false
    )

    private suspend fun readAgentStateStreamSnapshot(
        input: java.io.InputStream,
        call: okhttp3.Call,
        contentType: String,
        httpSuccess: Boolean
    ): ByteArray = readFramedSnapshotInternal(
        input = input,
        call = call,
        contentType = contentType,
        httpSuccess = httpSuccess,
        initialTimeoutMs = 2000L,
        maxTimeoutMs = 2200L,
        agentStateMode = true
    )

    private suspend fun readFramedSnapshotInternal(
        input: java.io.InputStream,
        call: okhttp3.Call,
        contentType: String,
        httpSuccess: Boolean,
        initialTimeoutMs: Long,
        maxTimeoutMs: Long,
        agentStateMode: Boolean
    ): ByteArray = coroutineScope {
        val isGrpcWeb = contentType.contains("grpc-web", ignoreCase = true)
        val isConnectStream = contentType.contains("connect+", ignoreCase = true)
        val isFramedStream = isGrpcWeb || isConnectStream
        val out = ByteArrayOutputStream()
        var sawTrailer = false
        val hasFirstFrame = AtomicBoolean(false)
        val sawIdleFrame = AtomicBoolean(false)
        val startMs = System.currentTimeMillis()
        val firstFrameAtMs = AtomicLong(0L)
        val lastFrameAtMs = AtomicLong(startMs)
        val readerDone = AtomicBoolean(false)

        val watchdog = launch(Dispatchers.IO) {
            while (!readerDone.get()) {
                delay(10)
                val now = System.currentTimeMillis()
                val shouldCancel = if (!hasFirstFrame.get()) {
                    (now - startMs >= initialTimeoutMs) || (now - startMs >= maxTimeoutMs)
                } else if (agentStateMode) {
                    val firstAt = firstFrameAtMs.get()
                    if (sawIdleFrame.get()) {
                        (now - lastFrameAtMs.get() >= 120L) || (now - firstAt >= 600L)
                    } else {
                        (now - lastFrameAtMs.get() >= 200L) || (now - firstAt >= 1800L)
                    }
                } else {
                    (now - lastFrameAtMs.get() >= 20L) || (now - startMs >= maxTimeoutMs)
                }
                if (shouldCancel) {
                    call.cancel()
                    break
                }
            }
        }

        try {
            withContext(Dispatchers.IO) {
                if (isFramedStream) {
                    val header = ByteArray(5)
                    while (true) {
                        if (!readExact(input, header, 5)) break
                        val flags = header[0].toInt() and 0xFF
                        val length = ((header[1].toInt() and 0xFF) shl 24) or
                            ((header[2].toInt() and 0xFF) shl 16) or
                            ((header[3].toInt() and 0xFF) shl 8) or
                            (header[4].toInt() and 0xFF)
                        if (length < 0 || length > 16 * 1024 * 1024) break
                        val payload = ByteArray(length)
                        if (!readExact(input, payload, length)) break
                        out.write(header)
                        out.write(payload)
                        val now = System.currentTimeMillis()
                        if (hasFirstFrame.compareAndSet(false, true)) {
                            firstFrameAtMs.set(now)
                        }
                        lastFrameAtMs.set(now)
                        val isFrameTrailer = (isGrpcWeb && (flags and 0x80) != 0) ||
                            (isConnectStream && (flags and 0x02) != 0)
                        if (isFrameTrailer) {
                            sawTrailer = true
                            break
                        }
                        if (agentStateMode) {
                            val text = payload.toString(Charsets.UTF_8)
                            if (text.contains("\"CASCADE_RUN_STATUS_IDLE\"") || text.contains("\"fullyIdle\":true")) {
                                sawIdleFrame.set(true)
                            }
                        }
                    }
                } else {
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = try {
                            input.read(buf)
                        } catch (_: IOException) {
                            -1
                        }
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        val now = System.currentTimeMillis()
                        if (hasFirstFrame.compareAndSet(false, true)) {
                            firstFrameAtMs.set(now)
                        }
                        lastFrameAtMs.set(now)
                    }
                }
            }
        } finally {
            readerDone.set(true)
            watchdog.cancel()
        }

        if (isFramedStream && httpSuccess && !sawTrailer) {
            if (isConnectStream) {
                out.write(byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x02, '{'.code.toByte(), '}'.code.toByte()))
            } else {
                out.write(GrpcWebFrameCodec.encodeTrailerFrame(GrpcStatus.OK))
            }
        }
        out.toByteArray()
    }

    private fun readExact(input: java.io.InputStream, buf: ByteArray, len: Int): Boolean {
        var offset = 0
        while (offset < len) {
            val read = try {
                input.read(buf, offset, len - offset)
            } catch (_: IOException) {
                return false
            }
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun isStreamingRpc(path: String): Boolean {
        val method = path.substringAfterLast('/')
        return method.contains("Subscribe", ignoreCase = true) ||
               method.contains("Stream", ignoreCase = true) ||
               method.startsWith("Watch", ignoreCase = true) ||
               method.equals("GetSidecarLogs", ignoreCase = true)
    }

    private fun extractFirstDataFrameJson(rawBytes: ByteArray, contentType: String): JsonObject? {
        if (rawBytes.isEmpty()) return null
        val isConnectStream = contentType.contains("connect+", ignoreCase = true)
        val isGrpcWeb = !isConnectStream && (contentType.contains("grpc-web", ignoreCase = true) || rawBytes[0] == 0.toByte())
        val jsonText = if (isConnectStream || isGrpcWeb) {
            val frames = runCatching { GrpcWebFrameCodec.decodeFrames(rawBytes) }.getOrNull() ?: return null
            val dataFrame = frames.firstOrNull { frame ->
                if (isConnectStream) (frame.flags and 0x02) == 0 else !frame.isTrailer
            } ?: return null
            dataFrame.utf8Text
        } else {
            rawBytes.toString(Charsets.UTF_8)
        }
        return runCatching { json.parseToJsonElement(jsonText).asObjOrNull() }.getOrNull()
    }

    private fun encodeStreamSingleMessage(obj: JsonObject, isConnectStream: Boolean): ByteArray {
        val jsonBytes = obj.toString().toByteArray(Charsets.UTF_8)
        return if (isConnectStream) {
            val out = ByteArrayOutputStream(12 + jsonBytes.size)
            out.write(0x00)
            out.write((jsonBytes.size ushr 24) and 0xFF)
            out.write((jsonBytes.size ushr 16) and 0xFF)
            out.write((jsonBytes.size ushr 8) and 0xFF)
            out.write(jsonBytes.size and 0xFF)
            out.write(jsonBytes)
            out.write(byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x02, '{'.code.toByte(), '}'.code.toByte()))
            out.toByteArray()
        } else {
            GrpcWebFrameCodec.encodeUnaryResponse(obj.toString())
        }
    }

    /**
     * Decodes a unary JSON, gRPC-Web JSON, or Connect+ JSON Hub response, applies [transform],
     * and re-encodes using the original wire framing. When [synthesizeIfEmpty] is true and a
     * framed stream timed out with no data frame, invokes [transform] with `{}`.
     */
    private inline fun transformUnaryJsonResponse(
        rawBytes: ByteArray,
        contentType: String,
        synthesizeIfEmpty: Boolean = false,
        transform: (JsonObject) -> JsonObject?
    ): ByteArray {
        if (rawBytes.isEmpty() && !synthesizeIfEmpty) return rawBytes
        return try {
            val isConnectStream = contentType.contains("connect+", ignoreCase = true)
            val isGrpcWeb = !isConnectStream &&
                (contentType.contains("grpc-web", ignoreCase = true) || (rawBytes.isNotEmpty() && rawBytes[0] == 0.toByte()))
            val jsonText = if (isConnectStream || isGrpcWeb) {
                val frames = if (rawBytes.isNotEmpty()) GrpcWebFrameCodec.decodeFrames(rawBytes) else emptyList()
                val dataFrame = frames.firstOrNull { frame ->
                    if (isConnectStream) (frame.flags and 0x02) == 0 else !frame.isTrailer
                }
                if (dataFrame == null) {
                    if (!synthesizeIfEmpty) return rawBytes
                    "{}"
                } else {
                    dataFrame.utf8Text
                }
            } else {
                if (rawBytes.isEmpty()) "{}" else rawBytes.toString(Charsets.UTF_8)
            }
            val root = json.parseToJsonElement(jsonText).asObjOrNull() ?: return rawBytes
            val updatedRoot = transform(root) ?: return rawBytes
            when {
                isConnectStream -> encodeStreamSingleMessage(updatedRoot, isConnectStream = true)
                isGrpcWeb -> GrpcWebFrameCodec.encodeUnaryResponse(updatedRoot.toString())
                else -> updatedRoot.toString().toByteArray(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            rawBytes
        }
    }
}
