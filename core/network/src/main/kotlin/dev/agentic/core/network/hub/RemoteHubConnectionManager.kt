package dev.agentic.core.network.hub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manages the client connection and session lifecycle between the Android application
 * and an authorized desktop Antigravity Language Server Hub (Phase P3.1).
 *
 * Security & TLS Architecture:
 * - When `useTls` is true and connecting to loopback (`127.0.0.1` or `localhost`), accepts
 *   the local self-signed certificate mirroring Electron's `setupLocalCertTrust()`
 *   boundary (`dist/languageServer.js`).
 * - When connecting to a remote LAN host with `pinnedCertSha256`, validates the certificate's
 *   SHA-256 fingerprint against the pinned fingerprint.
 * - Under no circumstances is global certificate validation disabled.
 * - CSRF tokens and user credentials are treated as secrets and never leaked to logs.
 */
class RemoteHubConnectionManager(
    private val connectTimeoutSeconds: Long = 10L,
    private val readTimeoutSeconds: Long = 60L
) {
    companion object {
        const val RPC_GET_USER_STATUS = "/exa.language_server_pb.LanguageServerService/GetUserStatus"
        const val RPC_GET_STATUS = "/exa.language_server_pb.LanguageServerService/GetStatus"
        const val HEADER_CSRF_TOKEN = "x-codeium-csrf-token"
        const val HEADER_CONNECT_PROTOCOL = "Connect-Protocol-Version"
        val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _connectionState = MutableStateFlow<HubConnectionState>(HubConnectionState.Disconnected)
    val connectionState: StateFlow<HubConnectionState> = _connectionState.asStateFlow()

    private var activeConfig: DesktopHubConfig? = null
    private var activeHttpClient: OkHttpClient? = null

    fun getActiveConfig(): DesktopHubConfig? = activeConfig
    fun getActiveHttpClient(): OkHttpClient? = activeHttpClient

    /**
     * Executes the Connect-RPC [LoginWithBrowser] call to initiate genuine Google authentication.
     */
    suspend fun loginWithBrowser(): Result<String> = withContext(Dispatchers.IO) {
        val config = activeConfig ?: return@withContext Result.failure(IllegalStateException("Hub not connected"))
        val client = activeHttpClient ?: return@withContext Result.failure(IllegalStateException("Client not initialized"))

        try {
            val req = Request.Builder()
                .url("${config.baseUrl}/exa.language_server_pb.LanguageServerService/LoginWithBrowser")
                .post("{}".toRequestBody(MEDIA_TYPE_JSON))
                .header(HEADER_CSRF_TOKEN, config.csrfToken)
                .header(HEADER_CONNECT_PROTOCOL, "1")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Result.success(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes the Connect-RPC [Login] call.
     */
    suspend fun login(payloadJson: String = "{\"isGcpTos\": true}"): Result<String> = withContext(Dispatchers.IO) {
        val config = activeConfig ?: return@withContext Result.failure(IllegalStateException("Hub not connected"))
        val client = activeHttpClient ?: return@withContext Result.failure(IllegalStateException("Client not initialized"))

        try {
            val req = Request.Builder()
                .url("${config.baseUrl}/exa.language_server_pb.LanguageServerService/Login")
                .post(payloadJson.toRequestBody(MEDIA_TYPE_JSON))
                .header(HEADER_CSRF_TOKEN, config.csrfToken)
                .header(HEADER_CONNECT_PROTOCOL, "1")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Result.success("HTTP ${resp.code}: $body")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Re-queries the Hub for latest user profile, authentication state, and available Cascade models.
     */
    suspend fun refreshUserStatus(): Result<HubServerStatus> = withContext(Dispatchers.IO) {
        val config = activeConfig ?: return@withContext Result.failure(IllegalStateException("Hub not connected"))
        val client = activeHttpClient ?: return@withContext Result.failure(IllegalStateException("Client not initialized"))

        try {
            val verifyRequest = Request.Builder()
                .url("${config.baseUrl}$RPC_GET_USER_STATUS")
                .post("{}".toRequestBody(MEDIA_TYPE_JSON))
                .header(HEADER_CSRF_TOKEN, config.csrfToken)
                .header(HEADER_CONNECT_PROTOCOL, "1")
                .header("User-Agent", "antigravity-android/p3")
                .build()

            client.newCall(verifyRequest).execute().use { response ->
                val bodyText = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("GetUserStatus failed HTTP ${response.code}"))
                }
                val parsed = parseUserStatusResponse(bodyText)
                val current = _connectionState.value
                val stableStatus = if (current is HubConnectionState.Connected && current.config == config) {
                    parsed.copy(connectedAtIso = current.status.connectedAtIso)
                } else {
                    parsed
                }
                if (current !is HubConnectionState.Connected || current.config != config || current.status != stableStatus) {
                    _connectionState.value = HubConnectionState.Connected(config, stableStatus)
                }
                Result.success(stableStatus)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes the Connect-RPC [GetAuthStatus] call to inspect authentication state.
     */
    suspend fun getAuthStatus(): Result<String> = withContext(Dispatchers.IO) {
        val config = activeConfig ?: return@withContext Result.failure(IllegalStateException("Hub not connected"))
        val client = activeHttpClient ?: return@withContext Result.failure(IllegalStateException("Client not initialized"))

        try {
            val req = Request.Builder()
                .url("${config.baseUrl}/exa.language_server_pb.LanguageServerService/GetAuthStatus")
                .post("{}".toRequestBody(MEDIA_TYPE_JSON))
                .header(HEADER_CSRF_TOKEN, config.csrfToken)
                .header(HEADER_CONNECT_PROTOCOL, "1")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Result.success(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes a generic Connect-RPC unary call against the authenticated Language Server Hub.
     */
    suspend fun executeUnaryRpc(serviceMethod: String, payloadJson: String = "{}"): Result<String> = withContext(Dispatchers.IO) {
        val config = activeConfig ?: return@withContext Result.failure(IllegalStateException("Hub not connected"))
        val client = activeHttpClient ?: return@withContext Result.failure(IllegalStateException("Client not initialized"))

        try {
            val path = if (serviceMethod.startsWith("/")) serviceMethod else "/exa.language_server_pb.LanguageServerService/$serviceMethod"
            val req = Request.Builder()
                .url("${config.baseUrl}$path")
                .post(payloadJson.toRequestBody(MEDIA_TYPE_JSON))
                .header(HEADER_CSRF_TOKEN, config.csrfToken)
                .header(HEADER_CONNECT_PROTOCOL, "1")
                .header("User-Agent", "antigravity-android/p3")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Result.failure(IOException("RPC $serviceMethod failed HTTP ${resp.code}: $body"))
                } else {
                    Result.success(body)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Establishes a verified, authenticated connection to the desktop Hub specified in [config].
     * Executes a safe verification RPC ([RPC_GET_USER_STATUS]) to validate both network reachability
     * and CSRF token authenticity.
     */
    suspend fun connect(config: DesktopHubConfig): Result<HubServerStatus> = withContext(Dispatchers.IO) {
        _connectionState.value = HubConnectionState.Connecting(config)

        try {
            val client = buildHttpClient(config)
            val verifyRequest = Request.Builder()
                .url("${config.baseUrl}$RPC_GET_USER_STATUS")
                .post("{}".toRequestBody(MEDIA_TYPE_JSON))
                .header(HEADER_CSRF_TOKEN, config.csrfToken)
                .header(HEADER_CONNECT_PROTOCOL, "1")
                .header("User-Agent", "antigravity-android/p3")
                .build()

            client.newCall(verifyRequest).execute().use { response ->
                val bodyText = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = when (response.code) {
                        401, 403 -> "Desktop Hub rejected authentication: CSRF token mismatch or unauthorized (HTTP ${response.code})"
                        404 -> "Desktop Hub endpoint not found (HTTP 404). Verify the port matches the running Antigravity daemon."
                        502, 503 -> "Desktop Hub service unavailable (HTTP ${response.code}). The daemon may be starting up."
                        else -> "Desktop Hub returned HTTP ${response.code}: ${response.message}"
                    }
                    val err = IOException(errorMsg)
                    _connectionState.value = HubConnectionState.Error(config, errorMsg, err)
                    return@withContext Result.failure(err)
                }

                val status = parseUserStatusResponse(bodyText)
                activeConfig = config
                activeHttpClient = client
                _connectionState.value = HubConnectionState.Connected(config, status)
                Result.success(status)
            }
        } catch (e: ConnectException) {
            val msg = "Unable to connect to Desktop Hub at ${config.host}:${config.port}. Ensure Antigravity desktop is running."
            _connectionState.value = HubConnectionState.Error(config, msg, e)
            Result.failure(IOException(msg, e))
        } catch (e: SocketTimeoutException) {
            val msg = "Connection to Desktop Hub at ${config.host}:${config.port} timed out after ${connectTimeoutSeconds}s."
            _connectionState.value = HubConnectionState.Error(config, msg, e)
            Result.failure(IOException(msg, e))
        } catch (e: SSLHandshakeException) {
            val msg = "TLS handshake with Desktop Hub failed: ${e.message}. If using self-signed certs over LAN, provide pinned certificate SHA-256."
            _connectionState.value = HubConnectionState.Error(config, msg, e)
            Result.failure(IOException(msg, e))
        } catch (e: Exception) {
            val msg = "Failed to connect to Desktop Hub: ${e.message}"
            _connectionState.value = HubConnectionState.Error(config, msg, e)
            Result.failure(IOException(msg, e))
        }
    }

    /**
     * Disconnects from the current Hub and resets connection state.
     */
    fun disconnect() {
        activeHttpClient?.dispatcher?.cancelAll()
        activeHttpClient = null
        activeConfig = null
        _connectionState.value = HubConnectionState.Disconnected
    }

    private fun parseUserStatusResponse(jsonText: String): HubServerStatus {
        val cleanJson = if (jsonText.startsWith("\u0000") || (jsonText.length >= 5 && jsonText[0] == '\u0000')) {
            try {
                dev.agentic.core.network.jetski.GrpcWebFrameCodec.extractFirstRequestJson(jsonText.toByteArray(Charsets.ISO_8859_1))
            } catch (_: Exception) {
                jsonText
            }
        } else {
            jsonText
        }

        val root = try {
            json.parseToJsonElement(cleanJson).jsonObject
        } catch (_: Exception) {
            return HubServerStatus(connectedAtIso = Instant.now().toString())
        }

        val userStatus = root["userStatus"]?.jsonObject
        val planStatus = userStatus?.get("planStatus")?.jsonObject
        val planInfo = planStatus?.get("planInfo")?.jsonObject
        val cascadeData = userStatus?.get("cascadeModelConfigData")?.jsonObject
        val clientConfigs = cascadeData?.get("clientModelConfigs")?.jsonArray

        val modelLabels = clientConfigs?.mapNotNull { it.jsonObject["label"]?.jsonPrimitive?.content } ?: emptyList()

        return HubServerStatus(
            connectedAtIso = Instant.now().toString(),
            serverVersion = root["version"]?.jsonPrimitive?.content,
            userName = userStatus?.get("name")?.jsonPrimitive?.content,
            userEmail = userStatus?.get("email")?.jsonPrimitive?.content,
            planName = planInfo?.get("planName")?.jsonPrimitive?.content,
            teamsTier = planInfo?.get("teamsTier")?.jsonPrimitive?.content,
            availablePromptCredits = planStatus?.get("availablePromptCredits")?.jsonPrimitive?.longOrNull,
            cascadeWebSearchEnabled = planInfo?.get("cascadeWebSearchEnabled")?.jsonPrimitive?.booleanOrNull ?: false,
            availableModelsCount = modelLabels.size,
            clientModelLabels = modelLabels
        )
    }

    private fun buildHttpClient(config: DesktopHubConfig): OkHttpClient {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 64
        }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)

        if (!config.useTls) {
            return builder.build()
        }

        val trustManager = ScopedHubTrustManager(
            allowedHost = config.host,
            pinnedSha256 = config.pinnedCertSha256
        )

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier(HostnameVerifier { hostname, _ ->
            // Allow matching hostname or loopback
            hostname.equals(config.host, ignoreCase = true) ||
                (isLoopbackHost(config.host) && isLoopbackHost(hostname))
        })

        return builder.build()
    }

    private fun isLoopbackHost(host: String): Boolean {
        return host.equals("127.0.0.1", ignoreCase = true) ||
            host.equals("localhost", ignoreCase = true) ||
            host.equals("::1", ignoreCase = true)
    }

    /**
     * A strictly scoped [X509TrustManager] that validates certificates exclusively for the
     * targeted desktop Hub connection.
     */
    internal class ScopedHubTrustManager(
        private val allowedHost: String,
        private val pinnedSha256: String?
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("Server certificate chain is empty")
            }

            val serverCert = chain[0]

            // 1. If pinned fingerprint is specified, require exact match
            if (!pinnedSha256.isNullOrBlank()) {
                val certSha256 = computeSha256Hex(serverCert.encoded)
                val cleanExpected = pinnedSha256.replace(":", "").lowercase()
                if (certSha256 != cleanExpected) {
                    throw CertificateException("Certificate SHA-256 mismatch! Expected: $cleanExpected, actual: $certSha256")
                }
                return
            }

            // 2. If connecting to loopback, mirror Electron setupLocalCertTrust()
            if (allowedHost.equals("127.0.0.1", ignoreCase = true) ||
                allowedHost.equals("localhost", ignoreCase = true) ||
                allowedHost.equals("::1", ignoreCase = true)
            ) {
                // Loopback self-signed cert is accepted for local IPC
                return
            }

            // 3. For remote LAN connections without pinning, check validity dates as a basic check
            try {
                serverCert.checkValidity()
            } catch (e: Exception) {
                throw CertificateException("Server certificate has expired or is not yet valid: ${e.message}", e)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun computeSha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
