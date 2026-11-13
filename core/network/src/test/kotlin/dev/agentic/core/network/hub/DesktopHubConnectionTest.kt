package dev.agentic.core.network.hub

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket

class DesktopHubConnectionTest {

    private var mockServer: SimpleTestServer? = null
    private var mockPort: Int = 0
    private val expectedCsrfToken = "test-csrf-token-uuid-12345"

    class SimpleTestServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        var handler: ((path: String, headers: Map<String, String>, body: String) -> Pair<Int, String>)? = null

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

                val (code, respBody) = handler?.invoke(path, headers, body) ?: Pair(200, "{}")
                val respBytes = respBody.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                val statusText = if (code == 200) "OK" else if (code == 401) "Unauthorized" else "Error"
                out.write("HTTP/1.1 $code $statusText\r\nContent-Type: application/json\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(respBytes)
                out.flush()
            }
        }

        override fun close() {
            serverSocket.close()
        }
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

    // -------------------------------------------------------------------------
    // 1. DesktopHubConfig Tests
    // -------------------------------------------------------------------------

    @Test
    fun `DesktopHubConfig accepts valid parameters and formats baseUrl correctly`() {
        val configHttps = DesktopHubConfig(
            host = "127.0.0.1",
            port = 57733,
            csrfToken = "secret-token",
            useTls = true
        )
        assertEquals("https://127.0.0.1:57733", configHttps.baseUrl)

        val configHttp = DesktopHubConfig(
            host = "192.168.1.50",
            port = 57734,
            csrfToken = "secret-token",
            useTls = false
        )
        assertEquals("http://192.168.1.50:57734", configHttp.baseUrl)
    }

    @Test
    fun `DesktopHubConfig rejects invalid inputs`() {
        try {
            DesktopHubConfig(host = "", port = 8080, csrfToken = "token")
            fail("Should reject blank host")
        } catch (_: IllegalArgumentException) {}

        try {
            DesktopHubConfig(host = "127.0.0.1", port = 0, csrfToken = "token")
            fail("Should reject port 0")
        } catch (_: IllegalArgumentException) {}

        try {
            DesktopHubConfig(host = "127.0.0.1", port = 70000, csrfToken = "token")
            fail("Should reject port 70000")
        } catch (_: IllegalArgumentException) {}

        try {
            DesktopHubConfig(host = "127.0.0.1", port = 8080, csrfToken = "")
            fail("Should reject blank csrfToken")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `DesktopHubConfig toString explicitly redacts csrfToken`() {
        val sensitiveToken = "super-sensitive-uuid-token-999"
        val config = DesktopHubConfig(
            host = "127.0.0.1",
            port = 57733,
            csrfToken = sensitiveToken,
            useTls = true
        )

        val debugString = config.toString()
        assertFalse("toString must not leak the raw token", debugString.contains(sensitiveToken))
        assertTrue("toString must indicate redaction", debugString.contains("[REDACTED]"))

        val diagString = config.toSafeDiagnosticsString()
        assertFalse("toSafeDiagnosticsString must not leak token", diagString.contains(sensitiveToken))
    }

    // -------------------------------------------------------------------------
    // 2. RemoteHubConnectionManager Lifecycle & Verification Tests
    // -------------------------------------------------------------------------

    @Test
    fun `connect succeeds against mock Hub and parses genuine status`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        server.handler = { path, headers, _ ->
            val csrf = headers[RemoteHubConnectionManager.HEADER_CSRF_TOKEN.lowercase()]
            if (csrf != expectedCsrfToken) {
                Pair(401, """{"error":"unauthorized"}""")
            } else {
                val responseJson = """
                    {
                      "userStatus": {
                        "name": "Antigravity Engineer",
                        "email": "engineer@example.com",
                        "planStatus": {
                          "planInfo": {
                            "teamsTier": "TEAMS_TIER_PRO",
                            "planName": "Antigravity Pro",
                            "cascadeWebSearchEnabled": true
                          },
                          "availablePromptCredits": 5000
                        },
                        "cascadeModelConfigData": {
                          "clientModelConfigs": [
                            {"label": "Gemini 4 Argon (Medium)"},
                            {"label": "Claude 3.7 Sonnet"},
                            {"label": "GPT-4o"}
                          ]
                        }
                      },
                      "version": "2.29.1"
                    }
                """.trimIndent()
                Pair(200, responseJson)
            }
        }

        val manager = RemoteHubConnectionManager(connectTimeoutSeconds = 2, readTimeoutSeconds = 2)
        val config = DesktopHubConfig(
            host = "127.0.0.1",
            port = mockPort,
            csrfToken = expectedCsrfToken,
            useTls = false
        )

        val result = manager.connect(config)
        assertTrue("Connection must succeed", result.isSuccess)

        val status = result.getOrThrow()
        assertEquals("Antigravity Pro", status.planName)
        assertEquals("TEAMS_TIER_PRO", status.teamsTier)
        assertEquals("Antigravity Engineer", status.userName)
        assertEquals(5000L, status.availablePromptCredits)
        assertTrue(status.cascadeWebSearchEnabled)
        assertEquals(3, status.availableModelsCount)
        assertEquals(listOf("Gemini 4 Argon (Medium)", "Claude 3.7 Sonnet", "GPT-4o"), status.clientModelLabels)

        val state = manager.connectionState.value
        assertTrue(state is HubConnectionState.Connected)
        assertEquals(config, (state as HubConnectionState.Connected).config)

        // Disconnect
        manager.disconnect()
        assertTrue(manager.connectionState.value is HubConnectionState.Disconnected)
    }

    @Test
    fun `connect fails with 401 when CSRF token is mismatched`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        server.handler = { _, headers, _ ->
            val csrf = headers[RemoteHubConnectionManager.HEADER_CSRF_TOKEN.lowercase()]
            if (csrf != expectedCsrfToken) {
                Pair(401, """{"error":"CSRF mismatch"}""")
            } else {
                Pair(200, "{}")
            }
        }

        val manager = RemoteHubConnectionManager(connectTimeoutSeconds = 2, readTimeoutSeconds = 2)
        val badConfig = DesktopHubConfig(
            host = "127.0.0.1",
            port = mockPort,
            csrfToken = "wrong-csrf-token",
            useTls = false
        )

        val result = manager.connect(badConfig)
        assertTrue("Connection must fail on invalid CSRF", result.isFailure)

        val state = manager.connectionState.value
        assertTrue(state is HubConnectionState.Error)
        val errState = state as HubConnectionState.Error
        assertTrue("Error must describe 401/CSRF issue", errState.message.contains("CSRF token mismatch or unauthorized"))
        assertFalse("Error message must not expose the sensitive token", errState.message.contains("wrong-csrf-token"))
    }

    @Test
    fun `connect fails when desktop Hub endpoint is unreachable`() = runBlocking {
        val manager = RemoteHubConnectionManager(connectTimeoutSeconds = 1, readTimeoutSeconds = 1)
        val unreachableConfig = DesktopHubConfig(
            host = "127.0.0.1",
            port = 59998,
            csrfToken = "token",
            useTls = false
        )

        val result = manager.connect(unreachableConfig)
        assertTrue("Connection to unused port must fail", result.isFailure)

        val state = manager.connectionState.value
        assertTrue(state is HubConnectionState.Error)
        val errState = state as HubConnectionState.Error
        assertTrue("Must indicate unable to connect", errState.message.contains("Unable to connect"))
    }

    // -------------------------------------------------------------------------
    // 3. Live Desktop Daemon Verification Test (when daemon is active)
    // -------------------------------------------------------------------------

    @Test
    fun `verify live desktop language server connection if daemon discovery file is present`() = runBlocking {
        val userHome = System.getProperty("user.home") ?: return@runBlocking
        val daemonDir = File(userHome, ".gemini/antigravity/daemon")
        if (!daemonDir.exists()) return@runBlocking

        val hubFiles = daemonDir.listFiles { _, name -> name.startsWith("hub_") && name.endsWith(".json") }
        if (hubFiles.isNullOrEmpty()) return@runBlocking

        val hubFile = hubFiles.maxByOrNull { it.lastModified() } ?: return@runBlocking
        val content = hubFile.readText()

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val root = try {
            json.parseToJsonElement(content) as kotlinx.serialization.json.JsonObject
        } catch (_: Exception) {
            return@runBlocking
        }

        val httpPort = root["httpPort"]?.toString()?.toIntOrNull() ?: return@runBlocking
        val csrfToken = root["csrfToken"]?.toString()?.trim('"') ?: return@runBlocking

        val manager = RemoteHubConnectionManager(connectTimeoutSeconds = 3, readTimeoutSeconds = 5)
        val liveConfig = DesktopHubConfig(
            host = "127.0.0.1",
            port = httpPort,
            csrfToken = csrfToken,
            useTls = false
        )

        val result = manager.connect(liveConfig)
        if (result.isSuccess) {
            val status = result.getOrThrow()
            assertNotNull(status.planName)
            println("Live Desktop Hub verified successfully: plan=${status.planName}, tier=${status.teamsTier}, models=${status.availableModelsCount}")
        } else {
            println("Live Desktop Hub not reachable on port $httpPort: ${result.exceptionOrNull()?.message}")
        }
    }
}
