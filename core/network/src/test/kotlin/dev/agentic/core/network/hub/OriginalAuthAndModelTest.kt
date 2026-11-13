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
import org.junit.Assert.assertFalse
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
 * Phase P3.5: Tests verifying original Antigravity authentication, user status, and model selection
 * via [LanguageServerGrpcWebHandler] in [BackendMode.ORIGINAL_DESKTOP_HUB] mode, as well as strict
 * separation from local Android BYOK host sessions (Requirement 1).
 */
class OriginalAuthAndModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var mockServer: SimpleTestServer? = null
    private var mockPort: Int = 0
    private val expectedDesktopCsrf = "auth-model-test-csrf-token-7777"

    private class SimpleTestServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort

        @Volatile
        var handler: ((path: String, headers: Map<String, String>, body: String, socket: Socket) -> Unit)? = null

        private val worker = thread(isDaemon = true, name = "OriginalAuthAndModelTest-Server") {
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
    fun `GetUserStatus returns genuine Google user profile, Pro tier, prompt credits, and official models`() = runBlocking {
        val server = mockServer ?: error("Server not started")

        val genuineUserStatusJson = """
        {
          "userStatus": {
            "name": "Bean Bag",
            "email": "monsterbag293@gmail.com",
            "planStatus": {
              "planInfo": {
                "teamsTier": "TEAMS_TIER_PRO",
                "planName": "Pro",
                "hasAutocompleteFastMode": true,
                "cascadeWebSearchEnabled": true,
                "browserEnabled": true
              },
              "availablePromptCredits": 500,
              "availableFlowCredits": 100
            },
            "cascadeModelConfigData": {
              "clientModelConfigs": [
                {
                  "label": "Gemini 4 Argon (Medium)",
                  "modelOrAlias": { "model": "MODEL_PLACEHOLDER_M418" },
                  "supportsImages": true
                },
                {
                  "label": "Gemini 4 Argon (High)",
                  "modelOrAlias": { "model": "MODEL_PLACEHOLDER_M417" },
                  "supportsImages": true
                },
                {
                  "label": "Claude Sonnet 5.5 (High)",
                  "modelOrAlias": { "model": "MODEL_PLACEHOLDER_M405" },
                  "supportsImages": true
                }
              ]
            }
          }
        }
        """.trimIndent()
        val responseBytes = GrpcWebFrameCodec.encodeUnaryResponse(genuineUserStatusJson)

        server.handler = { _, headers, _, socket ->
            val ctype = headers["content-type"] ?: ""
            val out = socket.getOutputStream()
            if (ctype.contains("grpc-web")) {
                val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: application/grpc-web+json\r\n")
                writer.print("Content-Length: ${responseBytes.size}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                out.write(responseBytes)
                out.flush()
            } else {
                val jsonBytes = genuineUserStatusJson.toByteArray(Charsets.UTF_8)
                val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: application/json\r\n")
                writer.print("Content-Length: ${jsonBytes.size}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                out.write(jsonBytes)
                out.flush()
            }
        }

        val connectionManager = RemoteHubConnectionManager()
        val config = DesktopHubConfig(host = "127.0.0.1", port = mockPort, csrfToken = expectedDesktopCsrf, useTls = false)
        val connectResult = connectionManager.connect(config)
        assertTrue("Connect must succeed", connectResult.isSuccess)

        val status = connectResult.getOrThrow()
        assertEquals("Bean Bag", status.userName)
        assertEquals("monsterbag293@gmail.com", status.userEmail)
        assertEquals("TEAMS_TIER_PRO", status.teamsTier)
        assertEquals("Pro", status.planName)
        assertEquals(500L, status.availablePromptCredits)
        assertEquals(3, status.clientModelLabels.size)
        assertTrue(status.clientModelLabels.contains("Gemini 4 Argon (Medium)"))
        assertTrue(status.clientModelLabels.contains("Claude Sonnet 5.5 (High)"))

        val proxyHandler = RemoteHubProxyHandler(connectionManager)
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = DummyModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = "android-token-123",
            workspaceRootDir = tempFolder.root,
            hubProxyHandler = proxyHandler,
            initialBackendMode = BackendMode.ORIGINAL_DESKTOP_HUB
        )

        val resp = rpcHandler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetUserStatus",
                method = "POST",
                headers = mapOf(
                    "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                    "x-codeium-csrf-token" to "android-token-123"
                ),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )

        assertEquals(200, resp.statusCode)
        val extractedJson = GrpcWebFrameCodec.extractFirstRequestJson(resp.body)
        assertTrue(extractedJson.contains("Bean Bag"))
        assertTrue(extractedJson.contains("monsterbag293@gmail.com"))
        assertTrue(extractedJson.contains("MODEL_PLACEHOLDER_M418"))
        assertTrue(extractedJson.contains("Claude Sonnet 5.5 (High)"))
    }

    @Test
    fun `strict separation between local Android host-session readiness and actual Google authentication`() = runBlocking {
        // Requirement 1 Verification:
        // In LOCAL_GEMINI_FALLBACK mode without Hub pairing:
        // LanguageServerGrpcWebHandler does NOT fabricate a genuine Google user account or claim fake Google login.
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = DummyModelProvider(),
            isAuthenticatedProvider = { false }, // No Gemini BYOK key in Keystore
            csrfToken = "android-token-xyz",
            workspaceRootDir = tempFolder.root,
            initialBackendMode = BackendMode.LOCAL_GEMINI_FALLBACK
        )

        // Local session is ready, but SendUserCascadeMessage fails with explicit UNAUTHENTICATED
        val response = rpcHandler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage",
                method = "POST",
                headers = mapOf(
                    "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                    "x-codeium-csrf-token" to "android-token-xyz"
                ),
                body = GrpcWebFrameCodec.encodeDataFrame("""{"prompt":"test"}""")
            )
        )

        assertEquals(200, response.statusCode)
        val trailerString = String(response.body, Charsets.UTF_8)
        val decodedTrailer = try {
            java.net.URLDecoder.decode(trailerString, "UTF-8")
        } catch (_: Exception) {
            trailerString
        }
        assertTrue("Must return UNAUTHENTICATED code 16", trailerString.contains("grpc-status: 16") || trailerString.contains("\"code\":16"))
        assertTrue("Must clearly mention independent Gemini API key", decodedTrailer.contains("independent Gemini API key in Settings"))
        assertFalse("Must never claim genuine Google login session", decodedTrailer.contains("monsterbag293@gmail.com"))
    }
}
