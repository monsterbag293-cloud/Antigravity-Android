package dev.agentic.core.network.hub

import dev.agentic.core.network.jetski.BundleValidationResult
import dev.agentic.core.network.jetski.GrpcStatus
import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import dev.agentic.core.network.jetski.JetskiAppConfig
import dev.agentic.core.network.jetski.JetskiBundleLoader
import dev.agentic.core.network.jetski.JetskiWebViewRequestInterceptor
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Phase P3.3: Integration tests verifying that intercepted WebView requests from the original
 * Jetski Web frontend are relayed through [JetskiWebViewRequestInterceptor], routed by
 * [LanguageServerGrpcWebHandler] according to [BackendMode], and processed by [RemoteHubProxyHandler].
 */
class JetskiFrontendHubIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var mockServer: SimpleTestServer? = null
    private var mockPort: Int = 0
    private val expectedDesktopCsrf = "desktop-csrf-secret-12345"
    private val androidCsrf = "android-frontend-csrf-9999"

    private class SimpleTestServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort

        @Volatile
        var handler: ((path: String, headers: Map<String, String>, body: String, socket: Socket) -> Unit)? = null

        private val worker = thread(isDaemon = true, name = "JetskiFrontendHubIntegrationTest-Server") {
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

    private fun createMinimalBundleZip(target: File): File {
        val entries = mapOf(
            "index.html" to "<!doctype html><html><head></head><body><div id=\"root\"></div><script src=\"/main.js\"></script></body></html>",
            "main.js" to "globalThis.__JETSKI_LS_CLIENT_DECORATOR__; console.log('LanguageServerService');",
            "jetbox.css" to ":root { --color-jetbox-background: #101010; }",
            "compiled_tailwind.css" to ".bg-jetbox-background { background-color: #101010; }",
            "diff_worker.js" to "self.onmessage = function(e) { postMessage({ lines: [] }); };",
            "workers/shiki_worker.js" to "self.onmessage = function(e) { postMessage({ tokens: [] }); };"
        )
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return target
    }

    @Test
    fun `intercepted WebView request relays to Desktop Hub and returns genuine response with null encoding and CORS`() = runBlocking {
        val server = mockServer ?: error("Server not started")
        var receivedPath: String? = null
        var receivedCsrf: String? = null
        var receivedConnectProto: String? = null

        val genuineHubJson = """{"userStatus":{"name":"Bean Bag","email":"monsterbag293@gmail.com","planStatus":{"planInfo":{"planName":"Pro"}}}}"""
        val responseGrpcWebFrame = GrpcWebFrameCodec.encodeDataFrame(genuineHubJson)

        server.handler = { path, headers, _, socket ->
            receivedPath = path
            receivedCsrf = headers["x-codeium-csrf-token"]
            receivedConnectProto = headers["connect-protocol-version"]

            val ctype = headers["content-type"] ?: ""
            val out = socket.getOutputStream()
            if (ctype.contains("grpc-web")) {
                val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: application/grpc-web+json\r\n")
                writer.print("Content-Length: ${responseGrpcWebFrame.size}\r\n")
                writer.print("x-custom-hub-meta: verified-p3\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                out.write(responseGrpcWebFrame)
                out.flush()
            } else {
                val jsonBytes = genuineHubJson.toByteArray(Charsets.UTF_8)
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

        // 1. Establish connection to Desktop Hub
        val connectionManager = RemoteHubConnectionManager()
        val config = DesktopHubConfig(host = "127.0.0.1", port = mockPort, csrfToken = expectedDesktopCsrf, useTls = false)
        val connectResult = connectionManager.connect(config)
        assertTrue("Connect must succeed", connectResult.isSuccess)

        // 2. Setup LanguageServerGrpcWebHandler with RemoteHubProxyHandler in ORIGINAL_DESKTOP_HUB mode
        val proxyHandler = RemoteHubProxyHandler(connectionManager)
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = DummyModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = androidCsrf,
            workspaceRootDir = tempFolder.root,
            hubProxyHandler = proxyHandler,
            initialBackendMode = BackendMode.ORIGINAL_DESKTOP_HUB
        )
        assertEquals(BackendMode.ORIGINAL_DESKTOP_HUB, rpcHandler.backendMode)

        // 3. Setup bundle and interceptor
        val bundleFile = createMinimalBundleZip(tempFolder.newFile("test-bundle.zip"))
        val (validation, bundle) = JetskiBundleLoader().validateAndOpen(bundleFile)
        assertTrue(validation is BundleValidationResult.Valid)

        val interceptor = JetskiWebViewRequestInterceptor(
            bundleProvider = { bundle },
            rpcHandler = rpcHandler,
            appConfig = JetskiAppConfig(csrfToken = androidCsrf)
        )

        // 4. Simulate WebView request where rawBody is carried via base64 bridge header
        val reqPayloadBytes = GrpcWebFrameCodec.encodeDataFrame("{}")
        val reqPayloadB64 = Base64.getEncoder().encodeToString(reqPayloadBytes)
        val intercepted = interceptor.intercept(
            url = "https://appassets.androidplatform.net/exa.language_server_pb.LanguageServerService/GetUserStatus",
            method = "POST",
            headers = mapOf(
                "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                "x-codeium-csrf-token" to androidCsrf,
                JetskiWebViewRequestInterceptor.HEADER_POST_BODY_B64 to reqPayloadB64
            )
        )

        assertNotNull(intercepted)
        assertEquals(200, intercepted!!.statusCode)
        // Encoding MUST be null so Android WebView does not apply UTF-8 text transcoding to binary gRPC-Web frames
        assertNull("Encoding must be null for binary gRPC-Web frame safety", intercepted.encoding)
        assertEquals("application/grpc-web+json", intercepted.mimeType)
        assertEquals("*", intercepted.headers["Access-Control-Allow-Origin"])
        assertEquals("no-cache", intercepted.headers["Cache-Control"])
        assertNotNull(intercepted.headers[JetskiWebViewRequestInterceptor.HEADER_GRPC_WEB_B64])

        // Verify that the desktop Hub received the substituted token and protocol version
        assertEquals("/exa.language_server_pb.LanguageServerService/GetUserStatus", receivedPath)
        assertEquals(expectedDesktopCsrf, receivedCsrf)
        assertEquals("1", receivedConnectProto)

        // Verify the response body was received intact
        val extractedJson = GrpcWebFrameCodec.extractFirstRequestJson(intercepted.body)
        assertTrue(extractedJson.contains("Bean Bag"))
        assertTrue(extractedJson.contains("monsterbag293@gmail.com"))
        assertTrue(extractedJson.contains("Pro"))
    }

    @Test
    fun `when Desktop Hub is disconnected in ORIGINAL_DESKTOP_HUB mode, returns explicit UNAVAILABLE error without falling back`() = runBlocking {
        val connectionManager = RemoteHubConnectionManager() // Not connected!
        val proxyHandler = RemoteHubProxyHandler(connectionManager)
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = DummyModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = androidCsrf,
            workspaceRootDir = tempFolder.root,
            hubProxyHandler = proxyHandler,
            initialBackendMode = BackendMode.ORIGINAL_DESKTOP_HUB
        )

        val bundleFile = createMinimalBundleZip(tempFolder.newFile("test-bundle-2.zip"))
        val (_, bundle) = JetskiBundleLoader().validateAndOpen(bundleFile)

        val interceptor = JetskiWebViewRequestInterceptor(
            bundleProvider = { bundle },
            rpcHandler = rpcHandler,
            appConfig = JetskiAppConfig(csrfToken = androidCsrf)
        )

        val intercepted = interceptor.intercept(
            url = "https://appassets.androidplatform.net/exa.language_server_pb.LanguageServerService/ListProjects",
            method = "POST",
            headers = mapOf(
                "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                "x-codeium-csrf-token" to androidCsrf,
                JetskiWebViewRequestInterceptor.HEADER_POST_BODY_B64 to Base64.getEncoder().encodeToString(GrpcWebFrameCodec.encodeDataFrame("{}"))
            )
        )

        assertNotNull(intercepted)
        assertEquals(200, intercepted!!.statusCode)
        val headerStatus = intercepted.headers[GrpcWebFrameCodec.HEADER_GRPC_STATUS]
        val headerMsg = intercepted.headers[GrpcWebFrameCodec.HEADER_GRPC_MESSAGE]

        assertEquals(GrpcStatus.UNAVAILABLE.code.toString(), headerStatus)
        assertTrue(headerMsg != null && headerMsg.contains("Desktop Hub is not connected"))
    }

    @Test
    fun `when backendMode is explicitly switched to LOCAL_GEMINI_FALLBACK, routes to in-process local handler`() = runBlocking {
        val connectionManager = RemoteHubConnectionManager() // Disconnected!
        val proxyHandler = RemoteHubProxyHandler(connectionManager)
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = DummyModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = androidCsrf,
            workspaceRootDir = tempFolder.root,
            hubProxyHandler = proxyHandler,
            initialBackendMode = BackendMode.LOCAL_GEMINI_FALLBACK // Explicit fallback!
        )

        val bundleFile = createMinimalBundleZip(tempFolder.newFile("test-bundle-3.zip"))
        val (_, bundle) = JetskiBundleLoader().validateAndOpen(bundleFile)

        val interceptor = JetskiWebViewRequestInterceptor(
            bundleProvider = { bundle },
            rpcHandler = rpcHandler,
            appConfig = JetskiAppConfig(csrfToken = androidCsrf)
        )

        val intercepted = interceptor.intercept(
            url = "https://appassets.androidplatform.net/exa.language_server_pb.LanguageServerService/GetServerConfiguration",
            method = "POST",
            headers = mapOf(
                "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                "x-codeium-csrf-token" to androidCsrf,
                JetskiWebViewRequestInterceptor.HEADER_POST_BODY_B64 to Base64.getEncoder().encodeToString(GrpcWebFrameCodec.encodeDataFrame("{}"))
            )
        )

        assertNotNull(intercepted)
        assertEquals(200, intercepted!!.statusCode)
        val json = GrpcWebFrameCodec.extractFirstRequestJson(intercepted.body)
        assertTrue("Local handler returns server configuration", json.contains("serverConfig"))
        assertTrue("Local handler identifies as Jetski", json.contains("isJetski"))
    }
}
