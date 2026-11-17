package dev.agentic.core.network.hub

import dev.agentic.core.network.jetski.GrpcStatus
import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import dev.agentic.core.network.jetski.GrpcWebHttpRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class RemoteHubProxyHandlerTest {

    private var mockServer: SimpleProxyTestServer? = null
    private var mockPort: Int = 0
    private val expectedCsrfToken = "valid-csrf-token-abc-987"

    class SimpleProxyTestServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        var handler: ((path: String, headers: Map<String, String>, body: String, socket: Socket) -> Unit)? = null

        init {
            Thread {
                try {
                    while (!serverSocket.isClosed) {
                        val socket = serverSocket.accept()
                        Thread { handleClient(socket) }.start()
                    }
                } catch (_: Exception) {}
            }.start()
        }

        private fun handleClient(socket: Socket) {
            socket.use { s ->
                val input = s.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                val path = if (parts.size > 1) parts[1] else "/"

                val headers = mutableMapOf<String, String>()
                var line: String?
                var contentLength = 0
                while (input.readLine().also { line = it } != null && line!!.isNotBlank()) {
                    val colon = line!!.indexOf(':')
                    if (colon != -1) {
                        val k = line!!.substring(0, colon).trim().lowercase()
                        val v = line!!.substring(colon + 1).trim()
                        headers[k] = v
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    }
                }

                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = input.read(chars, read, contentLength - read)
                        if (count == -1) break
                        read += count
                    }
                    String(chars, 0, read)
                } else ""

                handler?.invoke(path, headers, body, s)
            }
        }

        override fun close() {
            serverSocket.close()
        }
    }

    @Before
    fun setUp() {
        mockServer = SimpleProxyTestServer().apply {
            mockPort = port
        }
    }

    @After
    fun tearDown() {
        mockServer?.close()
        mockServer = null
    }

    @Test
    fun `relayRequest forwards body and headers and returns genuine Hub response`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        var receivedPath: String? = null
        var receivedCsrf: String? = null
        var receivedConnectProto: String? = null
        var receivedBodyText: String? = null

        server.handler = { path, headers, body, socket ->
            if (path == RemoteHubConnectionManager.RPC_GET_USER_STATUS) {
                val okJson = """{"userStatus":{"planStatus":{"planInfo":{"planName":"Pro"}}}}""".toByteArray(Charsets.UTF_8)
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${okJson.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(okJson)
                out.flush()
            } else if (path == "/exa.language_server_pb.LanguageServerService/GetCascadeModelConfigs") {
                receivedPath = path
                receivedCsrf = headers[RemoteHubConnectionManager.HEADER_CSRF_TOKEN.lowercase()]
                receivedConnectProto = headers[RemoteHubConnectionManager.HEADER_CONNECT_PROTOCOL.lowercase()]
                receivedBodyText = body

                val responseBody = """{"cascadeModelConfigData":{"clientModelConfigs":[{"label":"Gemini 4 Argon"}]}}"""
                val framed = GrpcWebFrameCodec.encodeUnaryResponse(responseBody)
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: ${GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON}\r\nx-custom-hub-header: hub-verified\r\nContent-Length: ${framed.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(framed)
                out.flush()
            }
        }

        // 1. Establish connection
        val connectionManager = RemoteHubConnectionManager()
        val config = DesktopHubConfig(host = "127.0.0.1", port = mockPort, csrfToken = expectedCsrfToken, useTls = false)
        val connectResult = connectionManager.connect(config)
        assertTrue("Connection must succeed", connectResult.isSuccess)

        // 2. Relay RPC
        val relay = RemoteHubProxyHandler(connectionManager)
        val originalPayload = """{"workspaceUri":"file:///test/workspace"}"""
        val request = GrpcWebHttpRequest(
            path = "/exa.language_server_pb.LanguageServerService/GetCascadeModelConfigs",
            method = "POST",
            headers = mapOf(
                "Content-Type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                "x-client-frontend" to "jetski-android"
            ),
            body = originalPayload.toByteArray(Charsets.UTF_8)
        )

        val response = relay.relayRequest(request)
        assertEquals(200, response.statusCode)
        assertEquals("hub-verified", response.headers["x-custom-hub-header"])
        assertEquals("*", response.headers["Access-Control-Allow-Origin"])

        // Check protocol preservation
        assertEquals("/exa.language_server_pb.LanguageServerService/GetCascadeModelConfigs", receivedPath)
        assertEquals(expectedCsrfToken, receivedCsrf)
        assertEquals("1", receivedConnectProto)
        assertEquals(originalPayload, receivedBodyText)

        // Verify gRPC-Web response payload
        val responseJson = GrpcWebFrameCodec.extractFirstRequestJson(response.body)
        assertTrue(responseJson.contains("Gemini 4 Argon"))
    }

    @Test
    fun `relayRequest returns UNAVAILABLE when connection is disconnected`() = runBlocking {
        val connectionManager = RemoteHubConnectionManager()
        val relay = RemoteHubProxyHandler(connectionManager)

        val request = GrpcWebHttpRequest(
            path = "/exa.language_server_pb.LanguageServerService/GetUserStatus",
            method = "POST"
        )

        val response = relay.relayRequest(request)
        assertEquals(200, response.statusCode)
        assertEquals(GrpcStatus.UNAVAILABLE.code.toString(), response.headers[GrpcWebFrameCodec.HEADER_GRPC_STATUS])
        assertTrue(response.headers[GrpcWebFrameCodec.HEADER_GRPC_MESSAGE]?.contains("Desktop Hub is not connected") == true)
    }

    @Test
    fun `relayStreamingRequest receives multi-frame chunks in real time`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        val frame1 = GrpcWebFrameCodec.encodeDataFrame("""{"chunk":1}""")
        val frame2 = GrpcWebFrameCodec.encodeDataFrame("""{"chunk":2}""")
        val trailer = GrpcWebFrameCodec.encodeTrailerFrame(GrpcStatus.OK)

        server.handler = { path, _, _, socket ->
            if (path == RemoteHubConnectionManager.RPC_GET_USER_STATUS) {
                val okJson = """{"userStatus":{}}""".toByteArray(Charsets.UTF_8)
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${okJson.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(okJson)
                out.flush()
            } else if (path == "/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates") {
                val totalBytes = frame1.size + frame2.size + trailer.size
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: ${GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON}\r\nContent-Length: $totalBytes\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
                out.write(frame1)
                out.flush()
                out.write(frame2)
                out.flush()
                out.write(trailer)
                out.flush()
            }
        }

        val connectionManager = RemoteHubConnectionManager()
        val config = DesktopHubConfig(host = "127.0.0.1", port = mockPort, csrfToken = expectedCsrfToken, useTls = false)
        connectionManager.connect(config)

        val relay = RemoteHubProxyHandler(connectionManager)
        val receivedChunks = CopyOnWriteArrayList<ByteArray>()

        val request = GrpcWebHttpRequest(
            path = "/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates",
            method = "POST",
            body = ByteArray(0)
        )

        val response = relay.relayStreamingRequest(request) { chunk ->
            receivedChunks.add(chunk)
        }

        assertEquals(200, response.statusCode)
        assertTrue("Must have received streaming chunks", receivedChunks.isNotEmpty())
        val combined = receivedChunks.reduce { acc, b -> acc + b }
        assertEquals(frame1.size + frame2.size + trailer.size, combined.size)
    }

    @Test
    fun `relayRequest augments GetUserStatus to inject Gemini 3_8 Flash for free tier users`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        val upstreamJson = """
            {
              "userStatus": {
                "name": "Test User",
                "email": "test@gmail.com",
                "planInfo": {
                  "teamsTier": "TEAMS_TIER_FREE",
                  "planName": "Free"
                },
                "planStatus": {
                  "availablePromptCredits": 0
                },
                "cascadeModelConfigData": {
                  "clientModelConfigs": [
                    {
                      "label": "Gemini 4 Argon (Medium)",
                      "modelOrAlias": { "model": "MODEL_PLACEHOLDER_M418" }
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val upstreamFrames = GrpcWebFrameCodec.encodeUnaryResponse(upstreamJson)

        server.handler = { _, _, _, socket ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: ${GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON}\r\nContent-Length: ${upstreamFrames.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            out.write(upstreamFrames)
            out.flush()
        }

        val connectionManager = RemoteHubConnectionManager()
        val config = DesktopHubConfig(host = "127.0.0.1", port = mockPort, csrfToken = expectedCsrfToken, useTls = false)
        connectionManager.connect(config)

        val relay = RemoteHubProxyHandler(connectionManager)
        val request = GrpcWebHttpRequest(
            path = "/exa.language_server_pb.LanguageServerService/GetUserStatus",
            method = "POST",
            body = GrpcWebFrameCodec.encodeDataFrame("{}")
        )

        val response = relay.relayRequest(request)
        assertEquals(200, response.statusCode)

        val decodedFrames = GrpcWebFrameCodec.decodeFrames(response.body)
        val jsonPayload = decodedFrames.first { !it.isTrailer }.utf8Text

        assertTrue("Must contain Gemini 4 Argon (Medium)", jsonPayload.contains("Gemini 4 Argon (Medium)"))
        assertTrue("Must contain Gemini 3.8 Flash (High)", jsonPayload.contains("Gemini 3.8 Flash (High)"))
        assertTrue("Must contain MODEL_PLACEHOLDER_M318", jsonPayload.contains("MODEL_PLACEHOLDER_M318"))
        assertTrue("Must contain Claude Opus 5.5 (Medium)", jsonPayload.contains("Claude Opus 5.5 (Medium)"))
        assertTrue("Must contain MODEL_PLACEHOLDER_M401", jsonPayload.contains("MODEL_PLACEHOLDER_M401"))
        assertTrue("Must contain Claude Sonnet 5.5 (Medium)", jsonPayload.contains("Claude Sonnet 5.5 (Medium)"))
        assertTrue("Must contain MODEL_PLACEHOLDER_M404", jsonPayload.contains("MODEL_PLACEHOLDER_M404"))
        assertTrue("Must contain GPT-OSS 120B (Medium)", jsonPayload.contains("GPT-OSS 120B (Medium)"))
        assertTrue("Must contain MODEL_OPENAI_GPT_OSS_120B_MEDIUM", jsonPayload.contains("MODEL_OPENAI_GPT_OSS_120B_MEDIUM"))
        assertTrue("Must contain Gemini 4 Carbon", jsonPayload.contains("Gemini 4 Carbon"))
        assertTrue("Must contain MODEL_PLACEHOLDER_M670", jsonPayload.contains("MODEL_PLACEHOLDER_M670"))
        assertTrue("Must contain Unavailable tag for Gemini 4 Carbon", jsonPayload.contains("\"tagTitle\":\"Unavailable\""))
        assertTrue("Must contain Ultra plan description for Gemini 4 Carbon", jsonPayload.contains("Ultra plan subscribers"))
        assertTrue("Must contain gemini-4-carbon-disabled modelId", jsonPayload.contains("gemini-4-carbon-disabled"))
        assertTrue("Must contain Recommended sort group", jsonPayload.contains("Recommended Models"))
        assertTrue("Must override default model to Gemini 3.8 Flash (High) for free plan", jsonPayload.contains("\"defaultOverrideModelConfig\":{\"modelOrAlias\":{\"model\":\"MODEL_PLACEHOLDER_M318\"}}"))
    }

    @Test
    fun `augmentUserStatusJson preserves live enabled Gemini 4 Carbon on Ultra plan`() {
        val ultraJson = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "userStatus": {
                "cascadeModelConfigData": {
                  "clientModelConfigs": [
                    {
                      "label": "Gemini 4 Carbon",
                      "modelOrAlias": { "model": "MODEL_PLACEHOLDER_M670" },
                      "supportsImages": true,
                      "modelId": "gemini-4-carbon"
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        ).let { it as kotlinx.serialization.json.JsonObject }

        val augmented = DesktopModelCatalog.augmentUserStatusJson(ultraJson)!!
        val augmentedStr = augmented.toString()
        assertTrue("Must keep live Gemini 4 Carbon", augmentedStr.contains("\"modelId\":\"gemini-4-carbon\""))
        assertFalse("Must not mark live Ultra Carbon as Unavailable", augmentedStr.contains("\"tagTitle\":\"Unavailable\""))
        assertFalse("Must not mark live Ultra Carbon as disabled", augmentedStr.contains("\"disabled\":true"))
    }

    @Test
    fun `relayRequest preserves live quota groups and guarantees all 3 desktop usage groups`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        val upstreamQuotaJson = """
            {
              "response": {
                "groups": [
                  {
                    "displayName": "Gemini Models",
                    "buckets": [
                      {
                        "bucketId": "gemini-weekly",
                        "displayName": "Weekly limit",
                        "window": "weekly",
                        "remainingFraction": 0.49,
                        "resetTime": "2026-11-16T09:00:00Z"
                      },
                      {
                        "bucketId": "gemini-5h",
                        "displayName": "5h limit",
                        "window": "5h",
                        "description": "5h limit",
                        "remainingFraction": 0.69,
                        "resetTime": "2026-11-12T12:00:00Z"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val upstreamFrames = GrpcWebFrameCodec.encodeUnaryResponse(upstreamQuotaJson)

        server.handler = { _, _, _, socket ->
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: ${GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON}\r\nContent-Length: ${upstreamFrames.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            out.write(upstreamFrames)
            out.flush()
        }

        val connectionManager = RemoteHubConnectionManager()
        val config = DesktopHubConfig(host = "127.0.0.1", port = mockPort, csrfToken = expectedCsrfToken, useTls = false)
        connectionManager.connect(config)

        val relay = RemoteHubProxyHandler(connectionManager)
        val request = GrpcWebHttpRequest(
            path = "/exa.language_server_pb.LanguageServerService/RetrieveUserQuotaSummary",
            method = "POST",
            body = GrpcWebFrameCodec.encodeDataFrame("{\"forceRefresh\":true}")
        )

        val response = relay.relayRequest(request)
        assertEquals(200, response.statusCode)

        val decodedFrames = GrpcWebFrameCodec.decodeFrames(response.body)
        val jsonPayload = decodedFrames.first { !it.isTrailer }.utf8Text

        assertTrue("Must preserve live remainingFraction 0.49", jsonPayload.contains("0.49"))
        assertTrue("Must preserve live remainingFraction 0.69", jsonPayload.contains("0.69"))
        assertTrue("Must include Gemini Models group", jsonPayload.contains("Gemini Models"))
        assertTrue("Must include Claude and GPT models group", jsonPayload.contains("Claude and GPT models"))
        assertTrue("Must include Gemini 4 Argon group", jsonPayload.contains("Gemini 4 Argon"))
        assertTrue("Must ensure description is populated when resetTime is present", jsonPayload.contains("Quota window reset"))
    }
}
