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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val requestedCascadeId = reqJson?.get("cascadeId")?.jsonPrimitive?.content
            ?: reqJson?.get("conversationId")?.jsonPrimitive?.content

        try {
            if (request.path.endsWith("/DeleteCascadeTrajectory") || request.path.endsWith("/DeleteCascade")) {
                requestedCascadeId?.takeIf { it.isNotBlank() }?.let { conversationCache?.markDeleted(it) }
            }

            val isAudioStream = request.path.contains("StreamAudioTranscription", ignoreCase = true)
            val isAgentStateStream = request.path.endsWith("/StreamAgentStateUpdates")
            val isStreaming = isAudioStream || isAgentStateStream || isStreamingRpc(request.path)
            if (isAudioStream || isAgentStateStream) {
                // Persistent live stream for audio transcription & real-time Cascade token streaming:
                // do not truncate with snapshot cancellation so thinking/text chunks appear live.
                val liveStreamingClient = client.newBuilder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
                val call = liveStreamingClient.newCall(requestBuilder.build())
                val response = call.execute()
                val responseHeaders = mutableMapOf<String, String>()

                for ((name, value) in response.headers) {
                    responseHeaders[name] = value
                }

                responseHeaders["Access-Control-Allow-Origin"] = "*"
                responseHeaders["Cache-Control"] = "no-cache"

                val respContentType = response.header("content-type") ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON
                val rawStream = response.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))

                if (isAgentStateStream && requestedCascadeId != null && conversationCache?.isTrackedZeroProject(requestedCascadeId) == true) {
                    val isFramed = respContentType.contains("grpc-web", ignoreCase = true) ||
                        respContentType.contains("connect+", ignoreCase = true)
                    if (isFramed) {
                        val header = ByteArray(5)
                        if (readExact(rawStream, header, 5)) {
                            val flags = header[0].toInt() and 0xFF
                            val len = ((header[1].toInt() and 0xFF) shl 24) or
                                ((header[2].toInt() and 0xFF) shl 16) or
                                ((header[3].toInt() and 0xFF) shl 8) or
                                (header[4].toInt() and 0xFF)
                            val isTrailerFrame = (respContentType.contains("grpc-web", ignoreCase = true) && (flags and 0x80) != 0) ||
                                (respContentType.contains("connect+", ignoreCase = true) && (flags and 0x02) != 0)
                            if (len in 0..(16 * 1024 * 1024)) {
                                val payload = ByteArray(len)
                                if (readExact(rawStream, payload, len)) {
                                    if (isTrailerFrame) {
                                        val cachedSnapshot = conversationCache.getCachedStreamSnapshotResponse(requestedCascadeId)
                                        if (cachedSnapshot != null) {
                                            runCatching { response.close() }
                                            runCatching { call.cancel() }
                                            return@withContext GrpcWebHttpResponse(
                                                statusCode = 200,
                                                contentType = GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                                                headers = mapOf(
                                                    "Access-Control-Allow-Origin" to "*",
                                                    "Cache-Control" to "no-cache"
                                                ),
                                                body = GrpcWebFrameCodec.encodeUnaryResponse(cachedSnapshot.toString())
                                            )
                                        }
                                    }
                                    val prefixBytes = ByteArray(5 + len)
                                    System.arraycopy(header, 0, prefixBytes, 0, 5)
                                    System.arraycopy(payload, 0, prefixBytes, 5, len)
                                    val combined = java.io.SequenceInputStream(
                                        java.io.ByteArrayInputStream(prefixBytes),
                                        rawStream
                                    )
                                    val wrappedCombined = object : java.io.FilterInputStream(combined) {
                                        override fun close() {
                                            try {
                                                super.close()
                                            } finally {
                                                response.close()
                                                call.cancel()
                                            }
                                        }
                                    }
                                    return@withContext GrpcWebHttpResponse(
                                        statusCode = response.code,
                                        contentType = respContentType,
                                        headers = responseHeaders,
                                        body = ByteArray(0),
                                        bodyStream = wrappedCombined
                                    )
                                }
                            }
                        }
                    }
                }

                val wrappedStream = object : java.io.FilterInputStream(rawStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            response.close()
                            call.cancel()
                        }
                    }
                }

                GrpcWebHttpResponse(
                    statusCode = response.code,
                    contentType = respContentType,
                    headers = responseHeaders,
                    body = ByteArray(0),
                    bodyStream = wrappedStream
                )
            } else if (isStreaming) {
                val streamingClient = client.newBuilder()
                    .readTimeout(4500, TimeUnit.MILLISECONDS)
                    .build()
                val call = streamingClient.newCall(requestBuilder.build())
                val response = call.execute()
                val responseHeaders = mutableMapOf<String, String>()

                for ((name, value) in response.headers) {
                    responseHeaders[name] = value
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
                                httpSuccess = resp.isSuccessful
                            )
                        }
                    }
                } finally {
                    call.cancel()
                }

                val finalSnapshotBytes = if (
                    response.isSuccessful &&
                    request.path.endsWith("/JetboxSubscribeToSummaries") &&
                    conversationCache != null
                ) {
                    transformUnaryJsonResponse(snapshotBytes, respContentType) { root ->
                        val updates = root["updates"]?.jsonObject ?: return@transformUnaryJsonResponse null
                        conversationCache.updateFromSummariesMap(updates)
                        val merged = conversationCache.mergeIntoSummariesMap(updates)
                        buildJsonObject {
                            root.forEach { (k, v) ->
                                if (k == "updates") put(k, merged) else put(k, v)
                            }
                        }
                    }
                } else {
                    snapshotBytes
                }

                GrpcWebHttpResponse(
                    statusCode = response.code,
                    contentType = respContentType,
                    headers = responseHeaders,
                    body = finalSnapshotBytes,
                    bodyStream = null
                )
            } else {
                client.newCall(requestBuilder.build()).execute().use { response ->
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

                        response.isSuccessful && request.path.endsWith("/GetAllCascadeTrajectories") ->
                            transformAllTrajectoriesWithCache(rawBytes, respContentType)

                        request.path.endsWith("/GetCascadeTrajectory") ->
                            resolveTrajectoryWithCache(rawBytes, respContentType, response.isSuccessful, requestedCascadeId)

                        request.path.endsWith("/GetCascadeTrajectorySteps") -> {
                            val stepOffset = reqJson?.get("stepOffset")?.jsonPrimitive?.intOrNull ?: 0
                            resolveStepsWithCache(rawBytes, respContentType, response.isSuccessful, requestedCascadeId, stepOffset)
                        }

                        else -> rawBytes
                    }

                    if (response.isSuccessful && request.path.endsWith("/SendUserCascadeMessage") && requestedCascadeId != null && conversationCache != null) {
                        val hasWorkspace = reqJson?.get("workspaceUris")?.jsonArray?.isNotEmpty() == true ||
                            reqJson?.get("cascadeConfig")?.jsonObject?.get("workspaceUris")?.jsonArray?.isNotEmpty() == true ||
                            reqJson?.get("metadata")?.jsonObject?.get("workspaces")?.jsonArray?.isNotEmpty() == true
                        val metaProj = reqJson?.get("projectId")?.jsonPrimitive?.content
                        if (!hasWorkspace && (metaProj.isNullOrBlank() || metaProj == ConversationCacheStore.OUTSIDE_OF_PROJECT_ID)) {
                            val snippet = reqJson?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
                                ?.get("text")?.jsonPrimitive?.content?.trim()?.take(60)
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

    private fun parseRequestJsonOrNull(body: ByteArray): JsonObject? {
        if (body.isEmpty()) return null
        return runCatching {
            val text = if (body[0] == 0.toByte() && body.size >= 5) {
                GrpcWebFrameCodec.extractFirstRequestJson(body)
            } else {
                body.toString(Charsets.UTF_8)
            }
            json.parseToJsonElement(text).jsonObject
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
        val parsed = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
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

    private fun transformAllTrajectoriesWithCache(rawBytes: ByteArray, contentType: String): ByteArray {
        val cache = conversationCache ?: return rawBytes
        return transformUnaryJsonResponse(rawBytes, contentType) { root ->
            val summaries = root["trajectorySummaries"]?.jsonObject ?: return@transformUnaryJsonResponse null
            cache.updateFromSummariesMap(summaries)
            val merged = cache.mergeIntoSummariesMap(summaries)
            buildJsonObject {
                root.forEach { (k, v) ->
                    if (k == "trajectorySummaries") put(k, merged) else put(k, v)
                }
            }
        }
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
                    responseHeaders[name] = value
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
        httpSuccess: Boolean
    ): ByteArray = coroutineScope {
        val isGrpcWeb = contentType.contains("grpc-web", ignoreCase = true)
        val isConnectStream = contentType.contains("connect+", ignoreCase = true)
        val isFramedStream = isGrpcWeb || isConnectStream
        val out = ByteArrayOutputStream()
        var sawTrailer = false
        val hasFirstFrame = AtomicBoolean(false)
        val startMs = System.currentTimeMillis()
        val lastFrameAtMs = AtomicLong(startMs)
        val readerDone = AtomicBoolean(false)

        val watchdog = launch(Dispatchers.IO) {
            while (!readerDone.get()) {
                delay(15)
                val now = System.currentTimeMillis()
                val quietExceeded = hasFirstFrame.get() && (now - lastFrameAtMs.get() >= 80L)
                val initialExceeded = !hasFirstFrame.get() && (now - startMs >= 900L)
                val maxExceeded = (now - startMs >= 2200L)
                if (quietExceeded || initialExceeded || maxExceeded) {
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
                        hasFirstFrame.set(true)
                        lastFrameAtMs.set(System.currentTimeMillis())
                        if ((isGrpcWeb && (flags and 0x80) != 0) || (isConnectStream && (flags and 0x02) != 0)) {
                            sawTrailer = true
                            break
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
                        hasFirstFrame.set(true)
                        lastFrameAtMs.set(System.currentTimeMillis())
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
               method.equals("GetSidecarLogs", ignoreCase = true) ||
               method.equals("WatchDirectory", ignoreCase = true)
    }

    /**
     * Decodes a unary JSON or gRPC-Web JSON Hub response, applies [transform], and re-encodes
     * using the original wire framing. Falls back to [rawBytes] untouched on any mismatch.
     */
    private inline fun transformUnaryJsonResponse(
        rawBytes: ByteArray,
        contentType: String,
        transform: (JsonObject) -> JsonObject?
    ): ByteArray {
        if (rawBytes.isEmpty()) return rawBytes
        return try {
            val isGrpcWeb = contentType.contains("grpc-web") || (rawBytes[0] == 0.toByte())
            val jsonText = if (isGrpcWeb) {
                val frames = GrpcWebFrameCodec.decodeFrames(rawBytes)
                val dataFrame = frames.firstOrNull { !it.isTrailer } ?: return rawBytes
                dataFrame.utf8Text
            } else {
                rawBytes.toString(Charsets.UTF_8)
            }
            val root = json.parseToJsonElement(jsonText).jsonObject
            val updatedRoot = transform(root) ?: return rawBytes
            val updatedJsonText = updatedRoot.toString()
            if (isGrpcWeb) {
                GrpcWebFrameCodec.encodeUnaryResponse(updatedJsonText)
            } else {
                updatedJsonText.toByteArray(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            rawBytes
        }
    }
}
