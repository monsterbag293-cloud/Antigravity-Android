package dev.agentic.core.network.jetski

import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StageP05CWebViewInterceptorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeAiProvider : ModelProvider {
        override val providerId: String = "gemini-test"
        override val displayName: String = "Gemini Test"
        override suspend fun streamCompletion(
            request: ModelRequest,
            tools: List<ModelToolDefinition>,
            onDelta: (String) -> Unit
        ): ModelResponse {
            val prompt = request.messages.lastOrNull()?.content ?: ""
            val reply = "WebView bridge AI reply to: $prompt"
            onDelta(reply)
            return ModelResponse(text = reply)
        }
    }

    @Test
    fun `interceptor serves assets, workers, boot config, and routes header-bridged POST RPCs`() {
        val zipFile = createTestJetskiZip(tempFolder.newFile("jetski-webview.zip"))
        val loader = JetskiBundleLoader()
        val (validation, bundle) = loader.validateAndOpen(zipFile)
        assertTrue(validation is BundleValidationResult.Valid)
        assertNotNull(bundle)

        val csrfToken = "stage-p05c-csrf"
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = FakeAiProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = csrfToken,
            workspaceRootDir = tempFolder.root
        )

        val interceptor = JetskiWebViewRequestInterceptor(
            bundleProvider = { bundle },
            rpcHandler = rpcHandler,
            appConfig = JetskiAppConfig(csrfToken = csrfToken)
        )

        // 1. Non-virtual origin returns null (ignored by interceptor)
        assertNull(interceptor.intercept("https://example.com/index.html"))

        // 2. Serve root index.html and verify injected boot config + fetch bridge
        val indexResp = interceptor.intercept("https://appassets.androidplatform.net/?hostTheme=dark")
        assertNotNull(indexResp)
        assertEquals(200, indexResp!!.statusCode)
        assertEquals("text/html", indexResp.mimeType)
        assertEquals("utf-8", indexResp.encoding)
        val indexHtml = indexResp.body.toString(Charsets.UTF_8)
        assertTrue(indexHtml.contains("window.__APP_CONFIG__"))
        assertTrue(indexHtml.contains(csrfToken))
        assertTrue(indexHtml.contains("__JETSKI_LS_CLIENT_DECORATOR__"))
        assertTrue(indexHtml.contains("x-antigravity-post-body-b64"))

        // 3. Serve Web Workers (diff_worker.js and workers/shiki_worker.js)
        val diffWorkerResp = interceptor.intercept("https://appassets.androidplatform.net/diff_worker.js")
        assertNotNull(diffWorkerResp)
        assertEquals(200, diffWorkerResp!!.statusCode)
        assertEquals("application/javascript", diffWorkerResp.mimeType)
        assertEquals("same-origin", diffWorkerResp.headers["Cross-Origin-Resource-Policy"])

        val shikiWorkerResp = interceptor.intercept("https://appassets.androidplatform.net/workers/shiki_worker.js")
        assertNotNull(shikiWorkerResp)
        assertEquals(200, shikiWorkerResp!!.statusCode)
        assertEquals(2, interceptor.diagnostics.value.servedWorkerCount)

        // 4. Simulate Android WebViewClient.shouldInterceptRequest POST where rawBody is null
        // and the request payload is carried in x-antigravity-post-body-b64
        val startBodyBytes = GrpcWebFrameCodec.encodeDataFrame("{}")
        val startBodyB64 = Base64.getEncoder().encodeToString(startBodyBytes)

        val startRpcResp = interceptor.intercept(
            url = "https://appassets.androidplatform.net/exa.language_server_pb.LanguageServerService/StartCascade",
            method = "POST",
            headers = mapOf(
                "x-codeium-csrf-token" to csrfToken,
                "x-antigravity-post-body-b64" to startBodyB64
            ),
            rawBody = null
        )
        assertNotNull(startRpcResp)
        assertEquals(200, startRpcResp!!.statusCode)
        // Binary gRPC-Web responses MUST have encoding = null so Android WebView never UTF-8 transcodes 0x80 trailer headers
        assertNull(startRpcResp.encoding)
        assertEquals("application/grpc-web+json", startRpcResp.mimeType)

        // Verify raw 0x00 data-frame header and 0x80 trailer-frame header bytes are unchanged
        val rawBytes = startRpcResp.body
        assertEquals(0x00.toByte(), rawBytes[0])
        val dataFrameLen = ByteBuffer.wrap(rawBytes, 1, 4).int
        val trailerHeaderOffset = 5 + dataFrameLen
        assertEquals(0x80.toByte(), rawBytes[trailerHeaderOffset])
        val respB64Header = startRpcResp.headers[JetskiWebViewRequestInterceptor.HEADER_GRPC_WEB_B64]
        assertNotNull(respB64Header)
        assertArrayEquals(rawBytes, Base64.getDecoder().decode(respB64Header))

        val startFrames = GrpcWebFrameCodec.decodeFrames(startRpcResp.body)
        val cascadeId = json.parseToJsonElement(startFrames[0].utf8Text).jsonObject["cascadeId"]!!.jsonPrimitive.content
        assertTrue(cascadeId.isNotBlank())

        // Verify non-critical 404 (e.g. /favicon.ico) returns 404 without setting a blocking host error
        val faviconResp = interceptor.intercept("https://appassets.androidplatform.net/favicon.ico")
        assertNotNull(faviconResp)
        assertEquals(404, faviconResp!!.statusCode)
        assertNull(interceptor.diagnostics.value.lastError)

        // 5. Send user message through header-bridged POST and stream updates
        val sendPayload = """{"cascadeId":"$cascadeId","items":[{"text":"Verify Stage P0.5-C"}]}"""
        val sendB64 = Base64.getEncoder().encodeToString(GrpcWebFrameCodec.encodeDataFrame(sendPayload))
        val sendRpcResp = interceptor.intercept(
            url = "https://appassets.androidplatform.net/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage",
            method = "POST",
            headers = mapOf(
                "x-codeium-csrf-token" to csrfToken,
                "x-antigravity-post-body-b64" to sendB64
            ),
            rawBody = null
        )
        assertEquals(200, sendRpcResp!!.statusCode)

        val streamPayload = """{"conversationId":"$cascadeId"}"""
        val streamB64 = Base64.getEncoder().encodeToString(GrpcWebFrameCodec.encodeDataFrame(streamPayload))
        val streamRpcResp = interceptor.intercept(
            url = "https://appassets.androidplatform.net/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates",
            method = "POST",
            headers = mapOf(
                "x-codeium-csrf-token" to csrfToken,
                "x-antigravity-post-body-b64" to streamB64
            ),
            rawBody = null
        )
        assertEquals(200, streamRpcResp!!.statusCode)
        val streamFrames = GrpcWebFrameCodec.decodeFrames(streamRpcResp.body)
        assertEquals(2, streamFrames.size)
        assertTrue(streamFrames[0].utf8Text.contains("WebView bridge AI reply to: Verify Stage P0.5-C"))
        assertEquals(3, interceptor.diagnostics.value.handledRpcCount)
    }

    private fun createTestJetskiZip(target: File): File {
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
}
