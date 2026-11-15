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
import kotlinx.serialization.json.jsonObject
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
    val connectionManager: RemoteHubConnectionManager
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

        try {
            val isAudioStream = request.path.contains("StreamAudioTranscription", ignoreCase = true)
            val isStreaming = isAudioStream || isStreamingRpc(request.path)
            if (isAudioStream) {
                // Persistent audio transcription stream: must NOT cancel call or apply short read timeouts
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

                GrpcWebHttpResponse(
                    statusCode = response.code,
                    contentType = respContentType,
                    headers = responseHeaders,
                    body = snapshotBytes,
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
                        response.isSuccessful && request.path.endsWith("/RetrieveUserQuotaSummary") ->
                            transformUnaryJsonResponse(rawBytes, respContentType) { root ->
                                DesktopModelCatalog.augmentQuotaSummaryJson(root, quotaSnapshotByGroup)
                            }
                        else -> rawBytes
                    }

                    GrpcWebHttpResponse(
                        statusCode = response.code,
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
