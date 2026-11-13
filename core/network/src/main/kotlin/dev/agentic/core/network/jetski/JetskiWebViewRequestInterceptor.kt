package dev.agentic.core.network.jetski

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.Base64

/**
 * Pure-JVM and Android WebView request interceptor that bridges virtual HTTPS requests
 * (`https://appassets.androidplatform.net/...`) to:
 * 1. [JetskiBundleLoader.LoadedJetskiBundle.serveAsset] for HTML, JS, CSS, font, and Web Worker assets
 *    (`diff_worker.js`, `workers/shiki_worker.js`).
 * 2. [LanguageServerGrpcWebHandler.handleRequest] for Connect-RPC / gRPC-Web requests
 *    (`/exa.language_server_pb.LanguageServerService/<Method>`).
 *
 * Because Android's `WebViewClient.shouldInterceptRequest` does not expose `POST` request bodies on
 * `WebResourceRequest`, `JetskiBundleLoader.injectBootConfig` attaches the base64-encoded gRPC-Web
 * frame payload in the `x-antigravity-post-body-b64` request header, which this interceptor decodes
 * transparently in-process.
 */
class JetskiWebViewRequestInterceptor(
    private val bundleProvider: () -> JetskiBundleLoader.LoadedJetskiBundle?,
    private val rpcHandler: LanguageServerGrpcWebHandler,
    private val appConfig: JetskiAppConfig,
    val virtualOrigin: String = DEFAULT_VIRTUAL_ORIGIN
) {
    companion object {
        const val DEFAULT_VIRTUAL_ORIGIN = "https://appassets.androidplatform.net"
        const val HEADER_POST_BODY_B64 = "x-antigravity-post-body-b64"
        const val HEADER_GRPC_WEB_B64 = "x-antigravity-grpc-web-b64"
        private val CRITICAL_ASSET_PATHS = setOf(
            "/",
            "/index.html",
            "/main.js",
            "/jetbox.css",
            "/compiled_tailwind.css"
        )
    }

    data class InterceptedWebResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val mimeType: String,
        val encoding: String?,
        val headers: Map<String, String>,
        val body: ByteArray = ByteArray(0),
        val dataStream: java.io.InputStream? = null
    ) {
        fun openStream(): java.io.InputStream = dataStream ?: java.io.ByteArrayInputStream(body)
    }

    data class WebViewHostDiagnostics(
        val servedAssetCount: Int = 0,
        val servedWorkerCount: Int = 0,
        val handledRpcCount: Int = 0,
        val lastAssetPath: String? = null,
        val lastRpcMethod: String? = null,
        val lastError: String? = null
    )

    private val _diagnostics = MutableStateFlow(WebViewHostDiagnostics())
    val diagnostics: StateFlow<WebViewHostDiagnostics> = _diagnostics.asStateFlow()

    val hubConnectionStateFlow: StateFlow<dev.agentic.core.network.hub.HubConnectionState>?
        get() = rpcHandler.hubProxyHandler?.connectionManager?.connectionState

    fun requiresConnectedHub(): Boolean =
        rpcHandler.backendMode == dev.agentic.core.network.hub.BackendMode.ORIGINAL_DESKTOP_HUB ||
            rpcHandler.backendMode == dev.agentic.core.network.hub.BackendMode.STANDALONE_IN_APP_BACKEND

    fun isHubConnected(): Boolean =
        !requiresConnectedHub() || rpcHandler.hubProxyHandler?.connectionManager?.getActiveConfig() != null

    /**
     * Intercepts a request from Android WebView (which invokes `shouldInterceptRequest` on a
     * background WebView worker thread) or from a unit test.
     * Returns `null` if the request URL does not target [virtualOrigin].
     */
    fun intercept(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        rawBody: ByteArray? = null
    ): InterceptedWebResponse? {
        val parsedUri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }

        val origin = "${parsedUri.scheme ?: ""}://${parsedUri.authority ?: ""}".lowercase()
        if (origin != virtualOrigin.lowercase()) {
            return null
        }

        val path = parsedUri.path?.takeIf { it.isNotEmpty() } ?: "/"
        val upperMethod = method.uppercase()

        // 1. Handle CORS / preflight OPTIONS requests
        if (upperMethod == "OPTIONS") {
            return InterceptedWebResponse(
                statusCode = 204,
                reasonPhrase = "No Content",
                mimeType = "text/plain",
                encoding = "utf-8",
                headers = defaultSecurityHeaders(),
                body = ByteArray(0)
            )
        }

        // 2. Route Connect-RPC / gRPC-Web requests to LanguageServerGrpcWebHandler
        if (path.startsWith(LanguageServerGrpcWebHandler.SERVICE_PREFIX)) {
            val resolvedBody = resolveRequestBody(headers, rawBody)
            val rpcResponse = runBlocking {
                rpcHandler.handleRequest(
                    GrpcWebHttpRequest(
                        path = path,
                        method = upperMethod,
                        headers = headers,
                        body = resolvedBody
                    )
                )
            }
            val methodName = path.removePrefix(LanguageServerGrpcWebHandler.SERVICE_PREFIX)
            _diagnostics.update { current ->
                current.copy(
                    handledRpcCount = current.handledRpcCount + 1,
                    lastRpcMethod = methodName
                )
            }
            val isStreaming = methodName.contains("Subscribe", ignoreCase = true) ||
                              methodName.contains("Stream", ignoreCase = true) ||
                              methodName.equals("GetSidecarLogs", ignoreCase = true) ||
                              methodName.equals("WatchDirectory", ignoreCase = true)

            val extraHeaders = if (!isStreaming && rpcResponse.body.isNotEmpty()) {
                val responseB64 = Base64.getEncoder().encodeToString(rpcResponse.body)
                mapOf(HEADER_GRPC_WEB_B64 to responseB64)
            } else {
                emptyMap()
            }

            return InterceptedWebResponse(
                statusCode = rpcResponse.statusCode,
                reasonPhrase = httpReasonPhrase(rpcResponse.statusCode),
                mimeType = rpcResponse.contentType.substringBefore(';').trim(),
                // Binary gRPC-Web frames contain 0x00 data-frame and 0x80 trailer-frame headers;
                // encoding must be null so Android WebView never applies UTF-8 text transcoding.
                encoding = null,
                headers = defaultSecurityHeaders() + rpcResponse.headers + extraHeaders,
                body = rpcResponse.body,
                dataStream = rpcResponse.openStream()
            )
        }

        // 3. Route static asset and Web Worker requests to LoadedJetskiBundle
        val activeBundle = bundleProvider()
        if (activeBundle == null) {
            val errMsg = "No validated Jetski Web bundle is currently loaded."
            _diagnostics.update { it.copy(lastError = errMsg) }
            return InterceptedWebResponse(
                statusCode = 503,
                reasonPhrase = "Service Unavailable",
                mimeType = "text/plain",
                encoding = "utf-8",
                headers = defaultSecurityHeaders(),
                body = errMsg.toByteArray(Charsets.UTF_8)
            )
        }

        val asset = activeBundle.serveAsset(path, appConfig)
        if (asset == null) {
            val errMsg = "Asset not found: $path"
            if (path in CRITICAL_ASSET_PATHS) {
                _diagnostics.update { it.copy(lastError = errMsg) }
            }
            return InterceptedWebResponse(
                statusCode = 404,
                reasonPhrase = "Not Found",
                mimeType = "text/plain",
                encoding = "utf-8",
                headers = defaultSecurityHeaders(),
                body = errMsg.toByteArray(Charsets.UTF_8)
            )
        }

        val cleanMime = asset.mimeType.substringBefore(';').trim()
        val charsetEncoding = if (asset.mimeType.contains("charset=", ignoreCase = true)) {
            asset.mimeType.substringAfter("charset=", "utf-8").trim()
        } else {
            null
        }

        val isWorker = path.endsWith("diff_worker.js") || path.endsWith("shiki_worker.js")
        _diagnostics.update { current ->
            current.copy(
                servedAssetCount = current.servedAssetCount + 1,
                servedWorkerCount = current.servedWorkerCount + (if (isWorker) 1 else 0),
                lastAssetPath = path
            )
        }

        return InterceptedWebResponse(
            statusCode = asset.statusCode,
            reasonPhrase = httpReasonPhrase(asset.statusCode),
            mimeType = cleanMime,
            encoding = charsetEncoding,
            headers = defaultSecurityHeaders() + mapOf(
                "Cache-Control" to if (cleanMime == "text/html") "no-store" else "public, max-age=3600"
            ),
            body = asset.bytes
        )
    }

    private fun resolveRequestBody(headers: Map<String, String>, rawBody: ByteArray?): ByteArray {
        if (rawBody != null && rawBody.isNotEmpty()) {
            return rawBody
        }
        val b64Header = headers.entries.firstOrNull {
            it.key.equals(HEADER_POST_BODY_B64, ignoreCase = true)
        }?.value

        if (!b64Header.isNullOrBlank()) {
            return try {
                Base64.getDecoder().decode(b64Header.trim())
            } catch (_: Exception) {
                ByteArray(0)
            }
        }
        return ByteArray(0)
    }

    private fun defaultSecurityHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to virtualOrigin,
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type, x-codeium-csrf-token, x-grpc-web, connect-protocol-version, grpc-timeout, $HEADER_POST_BODY_B64",
        "Access-Control-Expose-Headers" to "grpc-status, grpc-message, $HEADER_GRPC_WEB_B64",
        "Cross-Origin-Resource-Policy" to "same-origin"
    )

    private fun httpReasonPhrase(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }
}
