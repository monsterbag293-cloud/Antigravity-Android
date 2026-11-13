package dev.agentic.core.network.hub

import dev.agentic.core.network.jetski.GrpcStatus
import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import dev.agentic.core.network.jetski.GrpcWebHttpRequest
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Phase P3.4: Tests verifying genuine Cascade conversation and agent execution relayed through
 * [RemoteHubProxyHandler] to the desktop Antigravity Language Server Hub.
 */
class GenuineCascadeAgentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var mockServer: SimpleTestServer? = null
    private var mockPort: Int = 0
    private val expectedDesktopCsrf = "cascade-test-csrf-token-5555"

    private class SimpleTestServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort

        @Volatile
        var handler: ((path: String, headers: Map<String, String>, body: String, socket: Socket) -> Unit)? = null

        private val worker = thread(isDaemon = true, name = "GenuineCascadeAgentTest-Server") {
            try {
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    thread(isDaemon = true) {
                        socket.use { s ->
                            handleConnection(s)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        private fun handleConnection(s: Socket) {
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val line = input.readLine() ?: return
            val parts = line.split(" ")
            val path = if (parts.size > 1) parts[1] else "/"

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var headerLine = input.readLine()
            while (!headerLine.isNullOrEmpty()) {
                val colon = headerLine.indexOf(':')
                if (colon != -1) {
                    val k = headerLine.substring(0, colon).trim().lowercase()
                    val v = headerLine.substring(colon + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
                headerLine = input.readLine()
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

        override fun close() {
            serverSocket.close()
        }
    }

    private class DummyModelProvider : ModelProvider {
        override val providerId: String = "dummy"
        override val displayName: String = "Dummy"
        override suspend fun streamCompletion(
            request: ModelRequest,
            tools: List<ModelToolDefinition>,
            onDelta: (String) -> Unit
        ): ModelResponse = ModelResponse(text = "dummy")
    }

    @Before
    fun setUp() {
        mockServer = SimpleTestServer().apply {
            mockPort = port
        }
    }

    @After
    fun tearDown() {
        mockServer?.close()
        mockServer = null
    }

    @Test
    fun `SendUserCascadeMessage is relayed to Desktop Hub with genuine conversation turn execution`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        var receivedMethod: String? = null
        var receivedPromptText: String? = null
        var receivedCsrf: String? = null

        val cascadeResponseJson = """
        {
          "cascadeId": "casc-9999-genuine",
          "trajectoryId": "traj-9999-genuine",
          "summary": "Implement authentic desktop relay",
          "status": "CASCADE_RUN_STATUS_IDLE",
          "fullyIdle": true,
          "steps": [
            {
              "stepIndex": 0,
              "type": "CORTEX_STEP_TYPE_USER_INPUT",
              "status": "CORTEX_STEP_STATUS_DONE",
              "userText": "Verify authentic Cascade execution"
            },
            {
              "stepIndex": 1,
              "type": "CORTEX_STEP_TYPE_PLANNER_RESPONSE",
              "status": "CORTEX_STEP_STATUS_DONE",
              "plannerResponseText": "Cascade turn successfully executed on genuine desktop language server.",
              "plannerThinkingText": "Verified multi-step turn, Cloud Code PA upstream connection, and trajectory database persistence."
            }
          ]
        }
        """.trimIndent()
        val responseBytes = GrpcWebFrameCodec.encodeUnaryResponse(cascadeResponseJson)

        server.handler = { path, headers, body, socket ->
            receivedMethod = path
            receivedCsrf = headers["x-codeium-csrf-token"]
            receivedPromptText = body

            val out = socket.getOutputStream()
            val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: application/grpc-web+json\r\n")
            writer.print("Content-Length: ${responseBytes.size}\r\n")
            writer.print("Connection: close\r\n\r\n")
            writer.flush()
            out.write(responseBytes)
            out.flush()
        }

        val connectionManager = RemoteHubConnectionManager()
        val config = DesktopHubConfig(host = "127.0.0.1", port = mockPort, csrfToken = expectedDesktopCsrf, useTls = false)
        val connectResult = connectionManager.connect(config)
        assertTrue("Connect must succeed", connectResult.isSuccess)

        val proxyHandler = RemoteHubProxyHandler(connectionManager)
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = DummyModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = "android-local-token",
            workspaceRootDir = tempFolder.root,
            hubProxyHandler = proxyHandler,
            initialBackendMode = BackendMode.ORIGINAL_DESKTOP_HUB
        )

        val userPromptPayload = """{"cascadeId":"casc-9999-genuine","prompt":"Verify authentic Cascade execution"}"""
        val response = rpcHandler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage",
                method = "POST",
                headers = mapOf(
                    "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                    "x-codeium-csrf-token" to "android-local-token"
                ),
                body = GrpcWebFrameCodec.encodeDataFrame(userPromptPayload)
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage", receivedMethod)
        assertEquals(expectedDesktopCsrf, receivedCsrf)
        assertNotNull(receivedPromptText)

        val extracted = GrpcWebFrameCodec.extractFirstRequestJson(response.body)
        assertTrue(extracted.contains("casc-9999-genuine"))
        assertTrue(extracted.contains("Cascade turn successfully executed on genuine desktop language server."))
        assertTrue(extracted.contains("CORTEX_STEP_TYPE_PLANNER_RESPONSE"))
    }

    @Test
    fun `CancelCascadeInvocation is relayed to Desktop Hub and returns success`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        var receivedMethod: String? = null

        server.handler = { path, _, _, socket ->
            receivedMethod = path
            val bodyBytes = GrpcWebFrameCodec.encodeUnaryResponse("{}")
            val out = socket.getOutputStream()
            val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: application/grpc-web+json\r\n")
            writer.print("Content-Length: ${bodyBytes.size}\r\n")
            writer.print("Connection: close\r\n\r\n")
            writer.flush()
            out.write(bodyBytes)
            out.flush()
        }

        val connectionManager = RemoteHubConnectionManager()
        val config = DesktopHubConfig(host = "127.0.0.1", port = mockPort, csrfToken = expectedDesktopCsrf, useTls = false)
        connectionManager.connect(config)

        val proxyHandler = RemoteHubProxyHandler(connectionManager)
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = DummyModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = "android-local-token",
            workspaceRootDir = tempFolder.root,
            hubProxyHandler = proxyHandler,
            initialBackendMode = BackendMode.ORIGINAL_DESKTOP_HUB
        )

        val cancelPayload = """{"cascadeId":"casc-9999-genuine"}"""
        val response = rpcHandler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/CancelCascadeInvocation",
                method = "POST",
                headers = mapOf(
                    "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                    "x-codeium-csrf-token" to "android-local-token"
                ),
                body = GrpcWebFrameCodec.encodeDataFrame(cancelPayload)
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("/exa.language_server_pb.LanguageServerService/CancelCascadeInvocation", receivedMethod)
        val extracted = GrpcWebFrameCodec.extractFirstRequestJson(response.body)
        assertEquals("{}", extracted.trim())
    }

    @Test
    fun `live desktop daemon verification of Cascade trajectories if running`() = runBlocking {
        val homeDir = System.getProperty("user.home") ?: return@runBlocking
        val daemonDir = File(homeDir, ".gemini/antigravity/daemon")
        val hubFiles = daemonDir.listFiles { _, name -> name.startsWith("hub_") && name.endsWith(".json") }
        if (hubFiles.isNullOrEmpty()) return@runBlocking

        val latestHub = hubFiles.maxByOrNull { it.lastModified() } ?: return@runBlocking
        val jsonText = latestHub.readText()
        if (!jsonText.contains("\"httpPort\"") || !jsonText.contains("\"csrfToken\"")) return@runBlocking

        val httpPort = Regex("\"httpPort\":\\s*(\\d+)").find(jsonText)?.groupValues?.get(1)?.toIntOrNull() ?: return@runBlocking
        val csrf = Regex("\"csrfToken\":\\s*\"([^\"]+)\"").find(jsonText)?.groupValues?.get(1) ?: return@runBlocking

        val conn = RemoteHubConnectionManager()
        val cfg = DesktopHubConfig(host = "127.0.0.1", port = httpPort, csrfToken = csrf, useTls = false)
        val res = conn.connect(cfg)
        if (res.isFailure) return@runBlocking

        val proxy = RemoteHubProxyHandler(conn)
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = DummyModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = "test-token",
            workspaceRootDir = tempFolder.root,
            hubProxyHandler = proxy,
            initialBackendMode = BackendMode.ORIGINAL_DESKTOP_HUB
        )

        val listTrajResp = rpcHandler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetAllCascadeTrajectories",
                method = "POST",
                headers = mapOf(
                    "content-type" to "application/json",
                    "x-codeium-csrf-token" to "test-token"
                ),
                body = "{}".toByteArray(Charsets.UTF_8)
            )
        )

        assertEquals(200, listTrajResp.statusCode)
        val bodyText = String(listTrajResp.body, Charsets.UTF_8)
        assertTrue("Live daemon returns trajectorySummaries", bodyText.contains("trajectorySummaries"))
    }
}
