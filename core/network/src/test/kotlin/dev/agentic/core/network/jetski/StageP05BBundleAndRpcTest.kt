package dev.agentic.core.network.jetski

import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StageP05BBundleAndRpcTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createSyntheticBundleZipBytes(
        includeShikiWorker: Boolean = true,
        validMainJs: Boolean = true
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun putEntry(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            putEntry(
                "index.html",
                """<!doctype html><html><head><title>Jetski Web</title></head><body><div id="root"></div><script src="/main.js"></script></body></html>"""
            )
            putEntry(
                "main.js",
                if (validMainJs) {
                    "/* LanguageServerService */ function rYc(a){var b=globalThis.__JETSKI_LS_CLIENT_DECORATOR__;return b?b(a):a}"
                } else {
                    "console.log('unrelated bundle');"
                }
            )
            putEntry("jetbox.css", ":root { --background: #101010; --primary: #007acc; }")
            putEntry("compiled_tailwind.css", ".bg-background { background-color: var(--background); }")
            putEntry("diff_worker.js", "self.onmessage = function(e) {};")
            if (includeShikiWorker) {
                putEntry("workers/shiki_worker.js", "self.postMessage({type:'ready'});")
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `JetskiBundleLoader validates standalone zip and embedded binary slice and serves assets`() {
        val tempDir = Files.createTempDirectory("jetski-loader-test").toFile()
        try {
            val zipBytes = createSyntheticBundleZipBytes()

            // 1. Standalone ZIP validation
            val standaloneZip = File(tempDir, "jetski-web.zip")
            standaloneZip.writeBytes(zipBytes)

            val loader = JetskiBundleLoader()
            val (res1, bundle1) = loader.validateAndOpen(standaloneZip)
            assertTrue("Expected Valid result, got $res1", res1 is BundleValidationResult.Valid)
            assertNotNull(bundle1)

            // 2. Embedded ZIP inside synthetic PE binary prefix (simulating language_server.exe)
            val embeddedBin = File(tempDir, "synthetic_language_server.exe")
            val prefixBytes = ByteArray(4096) { 0x4D.toByte() } // MZ-like prefix
            embeddedBin.outputStream().use { out ->
                out.write(prefixBytes)
                out.write(zipBytes)
            }

            val (res2, bundle2) = loader.validateAndOpen(embeddedBin)
            assertTrue("Expected Valid result for embedded binary, got $res2", res2 is BundleValidationResult.Valid)
            assertEquals(4096L, (res2 as BundleValidationResult.Valid).metadata.zipOffset)
            assertNotNull(bundle2)

            // Verify serving index.html with injected __APP_CONFIG__ and decorator
            val appConfig = JetskiAppConfig(csrfToken = "csrf-test-999")
            val servedIndex = bundle2!!.serveAsset("/c/00000000-0000-4000-8000-000000000001", appConfig)
            assertNotNull(servedIndex)
            assertEquals("text/html; charset=utf-8", servedIndex!!.mimeType)
            val htmlText = servedIndex.bytes.toString(Charsets.UTF_8)
            assertTrue(htmlText.contains("csrfToken: \"csrf-test-999\""))
            assertTrue(htmlText.contains("__JETSKI_LS_CLIENT_DECORATOR__"))

            // Verify serving worker scripts
            val servedWorker = bundle2.serveAsset("/workers/shiki_worker.js", appConfig)
            assertNotNull(servedWorker)
            assertEquals("application/javascript; charset=utf-8", servedWorker!!.mimeType)

            // Verify 404 for non-existent static asset with extension
            assertNull(bundle2.serveAsset("/missing-file.js", appConfig))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `JetskiBundleLoader rejects invalid, incomplete, and incompatible bundles gracefully`() {
        val tempDir = Files.createTempDirectory("jetski-invalid-test").toFile()
        try {
            val loader = JetskiBundleLoader()

            // Invalid non-zip
            val garbageFile = File(tempDir, "garbage.bin").apply { writeBytes(ByteArray(128) { 0x07 }) }
            val (invalidRes, _) = loader.validateAndOpen(garbageFile)
            assertTrue(invalidRes is BundleValidationResult.InvalidFormat)

            // Incomplete bundle (missing shiki_worker.js)
            val incompleteFile = File(tempDir, "incomplete.zip").apply {
                writeBytes(createSyntheticBundleZipBytes(includeShikiWorker = false))
            }
            val (incompleteRes, _) = loader.validateAndOpen(incompleteFile)
            assertTrue(incompleteRes is BundleValidationResult.IncompleteBundle)
            assertTrue((incompleteRes as BundleValidationResult.IncompleteBundle).missingAssets.contains("workers/shiki_worker.js"))

            // Incompatible bundle (main.js missing LanguageServerService)
            val incompatibleFile = File(tempDir, "incompatible.zip").apply {
                writeBytes(createSyntheticBundleZipBytes(validMainJs = false))
            }
            val (incompatibleRes, _) = loader.validateAndOpen(incompatibleFile)
            assertTrue(incompatibleRes is BundleValidationResult.IncompatibleBundle)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `JetskiBundleLoader validates real installed desktop language_server binary if present`() {
        val localAppData = System.getenv("LOCALAPPDATA") ?: return
        val desktopExe = File(localAppData, "Programs\\antigravity\\resources\\bin\\language_server.exe")
        if (!desktopExe.exists()) return

        val loader = JetskiBundleLoader()
        val (validation, bundle) = loader.validateAndOpen(desktopExe)
        assertTrue("Desktop language_server.exe should validate cleanly: $validation", validation is BundleValidationResult.Valid)
        assertNotNull(bundle)
        val valid = validation as BundleValidationResult.Valid
        assertTrue("Expected non-zero embedded zip offset, got ${valid.metadata.zipOffset}", valid.metadata.zipOffset > 0L)
        assertTrue(valid.metadata.entryCount >= 90)
    }

    @Test
    fun `GrpcWebFrameCodec frames and decodes unary and error trailers accurately`() {
        val encoded = GrpcWebFrameCodec.encodeUnaryResponse("""{"hasToken":true}""")
        val frames = GrpcWebFrameCodec.decodeFrames(encoded)
        assertEquals(2, frames.size)
        assertFalse(frames[0].isTrailer)
        assertEquals("""{"hasToken":true}""", frames[0].utf8Text)
        assertTrue(frames[1].isTrailer)
        assertTrue(frames[1].utf8Text.contains("grpc-status: 0"))

        // Reject compressed flag
        val compressedFrame = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x02, 0x7B, 0x7D)
        try {
            GrpcWebFrameCodec.decodeFrames(compressedFrame)
            org.junit.Assert.fail("Should have thrown GrpcWebProtocolException for compressed frame")
        } catch (e: GrpcWebProtocolException) {
            assertEquals(GrpcStatus.INTERNAL, e.status)
        }
    }

    @Test
    fun `LanguageServerGrpcWebHandler enforces CSRF, auth gates, timeouts, and streams real AI conversation turns`() = runBlocking {
        var isAuthenticated = false
        val fakeStreamingModelProvider = object : ModelProvider {
            override val providerId: String = "test-gemini"
            override val displayName: String = "Test Gemini"
            override suspend fun streamCompletion(
                request: ModelRequest,
                tools: List<ModelToolDefinition>,
                onDelta: (String) -> Unit
            ): ModelResponse {
                val userMsg = request.messages.lastOrNull()?.content ?: ""
                val chunks = listOf("Echoing ", "from Android backend: ", userMsg)
                for (c in chunks) {
                    delay(10)
                    onDelta(c)
                }
                return ModelResponse(text = chunks.joinToString(""))
            }
        }

        val csrf = "csrf-secret-token"
        val handler = LanguageServerGrpcWebHandler(
            modelProvider = fakeStreamingModelProvider,
            isAuthenticatedProvider = { isAuthenticated },
            csrfToken = csrf
        )

        // 1. Missing CSRF token -> UNAUTHENTICATED (grpc-status: 16)
        val noCsrfResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetLocalUserInfo",
                headers = emptyMap(),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )
        val noCsrfFrames = GrpcWebFrameCodec.decodeFrames(noCsrfResp.body)
        assertEquals(1, noCsrfFrames.size)
        assertTrue(noCsrfFrames[0].isTrailer)
        assertTrue(noCsrfFrames[0].utf8Text.contains("grpc-status: 16"))

        // 2. Local host readiness (HasAuthToken / GetAuthStatus / GetUserStatus) vs independent Gemini API key
        val authCheck1 = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/HasAuthToken",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )
        val authCheck1Frames = GrpcWebFrameCodec.decodeFrames(authCheck1.body)
        val authJson1 = json.parseToJsonElement(authCheck1Frames[0].utf8Text).jsonObject
        // Local Android host session is ready (so Jetski Web enters / instead of desktop /onboarding?login=true),
        // while claiming no GCP project or Google login session.
        assertTrue(authJson1["hasToken"]!!.jsonPrimitive.boolean)
        assertFalse(authJson1["isGcpTos"]!!.jsonPrimitive.boolean)
        assertEquals("", authJson1["projectId"]!!.jsonPrimitive.content)

        val authStatusUnconfigured = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetAuthStatus",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )
        val authStatusJson1 = json.parseToJsonElement(
            GrpcWebFrameCodec.decodeFrames(authStatusUnconfigured.body)[0].utf8Text
        ).jsonObject["authResult"]!!.jsonObject
        assertTrue(authStatusJson1["hasValidAuth"]!!.jsonPrimitive.boolean)
        assertEquals("AUTH_MODE_GEMINI_API_KEY", authStatusJson1["authMode"]!!.jsonPrimitive.content)
        assertEquals(0, authStatusJson1["grantedScopes"]!!.jsonArray.size)
        assertTrue(authStatusJson1["uiMessage"]!!.jsonPrimitive.content.contains("not signed into a Google account"))

        val userStatusUnconfigured = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetUserStatus",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )
        val userStatusJson1 = json.parseToJsonElement(
            GrpcWebFrameCodec.decodeFrames(userStatusUnconfigured.body)[0].utf8Text
        ).jsonObject["userStatus"]!!.jsonObject
        assertFalse(userStatusJson1["pro"]!!.jsonPrimitive.boolean)
        assertEquals("", userStatusJson1["email"]!!.jsonPrimitive.content)
        assertEquals(
            "local-host-unconfigured-key",
            userStatusJson1["userTier"]!!.jsonObject["id"]!!.jsonPrimitive.content
        )

        // 3. Unauthenticated SendUserCascadeMessage -> rejected with grpc-status: 16
        val unauthSend = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("""{"cascadeId":"c-1","items":[{"text":"Hello"}]}""")
            )
        )
        val unauthSendFrames = GrpcWebFrameCodec.decodeFrames(unauthSend.body)
        assertTrue(unauthSendFrames.last().utf8Text.contains("grpc-status: 16"))

        // 4. Verify schema-compatible responses for SubscribeToSidecars, SetMendelFlagOverrides,
        // ListTriggers, ListTriggerOptions, GetAgentScripts, and JetboxSubscribeToSummaries
        val sidecarsResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/SubscribeToSidecars",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )
        val sidecarsJson = json.parseToJsonElement(GrpcWebFrameCodec.decodeFrames(sidecarsResp.body)[0].utf8Text).jsonObject
        assertEquals(0, sidecarsJson["sidecars"]!!.jsonArray.size)

        val mendelOverrideResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/SetMendelFlagOverrides",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("""{"overrides":{"enable-sidecars":false}}""")
            )
        )
        assertTrue(GrpcWebFrameCodec.decodeFrames(mendelOverrideResp.body).last().utf8Text.contains("grpc-status: 0"))

        val listTriggersResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/ListTriggers",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("""{"backend":2,"pageSize":50}""")
            )
        )
        val listTriggersJson = json.parseToJsonElement(GrpcWebFrameCodec.decodeFrames(listTriggersResp.body)[0].utf8Text).jsonObject
        assertEquals(0, listTriggersJson["triggers"]!!.jsonArray.size)
        assertEquals("", listTriggersJson["nextPageToken"]!!.jsonPrimitive.content)

        val listTriggerOptsResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/ListTriggerOptions",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("""{"backend":2}""")
            )
        )
        val listTriggerOptsJson = json.parseToJsonElement(GrpcWebFrameCodec.decodeFrames(listTriggerOptsResp.body)[0].utf8Text).jsonObject
        assertEquals(0, listTriggerOptsJson["options"]!!.jsonArray.size)

        val summariesResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/JetboxSubscribeToSummaries",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )
        val summariesUpdates = json.parseToJsonElement(
            GrpcWebFrameCodec.decodeFrames(summariesResp.body)[0].utf8Text
        ).jsonObject["updates"]!!.jsonObject
        val defaultSummary = summariesUpdates[LanguageServerGrpcWebHandler.DEFAULT_CASCADE_ID]!!.jsonObject
        assertNull("latestNotifyUserStep must not be a raw integer", defaultSummary["latestNotifyUserStep"])
        assertEquals(0, defaultSummary["lastUserInputStepIndex"]!!.jsonPrimitive.int)

        // 5. Enable independent Gemini API key -> verify GetUserStatus updates & SendUserCascadeMessage streams
        isAuthenticated = true
        val userStatusConfigured = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetUserStatus",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )
        val userStatusJson2 = json.parseToJsonElement(
            GrpcWebFrameCodec.decodeFrames(userStatusConfigured.body)[0].utf8Text
        ).jsonObject["userStatus"]!!.jsonObject
        assertEquals("", userStatusJson2["email"]!!.jsonPrimitive.content)
        assertEquals(
            "independent-gemini-api-key",
            userStatusJson2["userTier"]!!.jsonObject["id"]!!.jsonPrimitive.content
        )
        assertTrue(
            userStatusJson2["cascadeModelConfigData"]!!.jsonObject["clientModelConfigs"]!!.jsonArray.isNotEmpty()
        )
        val modelLabels = userStatusJson2["cascadeModelConfigData"]!!.jsonObject["clientModelConfigs"]!!.jsonArray
            .map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assertTrue(modelLabels.contains("Gemini 4 Argon (Medium)"))
        assertTrue(modelLabels.contains("Gemini 3.8 Flash (High)"))
        assertTrue(modelLabels.contains("Claude Opus 5.5 (Medium)"))
        assertTrue(modelLabels.contains("Claude Sonnet 5.5 (Medium)"))
        assertTrue(modelLabels.contains("GPT-OSS 120B (Medium)"))
        assertTrue(modelLabels.contains("Gemini 4 Carbon"))

        val quotaSummaryResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/RetrieveUserQuotaSummary",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{\"forceRefresh\":true}")
            )
        )
        val quotaGroups = json.parseToJsonElement(
            GrpcWebFrameCodec.decodeFrames(quotaSummaryResp.body)[0].utf8Text
        ).jsonObject["response"]!!.jsonObject["groups"]!!.jsonArray
        assertEquals(
            listOf("Gemini Models", "Claude and GPT models", "Gemini 4 Argon"),
            quotaGroups.map { it.jsonObject["displayName"]!!.jsonPrimitive.content }
        )
        val startResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/StartCascade",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )
        val startJson = json.parseToJsonElement(GrpcWebFrameCodec.decodeFrames(startResp.body)[0].utf8Text).jsonObject
        val cascadeId = startJson["cascadeId"]!!.jsonPrimitive.content
        assertTrue(cascadeId.isNotBlank())

        val sendResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame(
                    """{"cascadeId":"$cascadeId","items":[{"text":"Port Jetski Web to Android"}]}"""
                )
            )
        )
        val sendFrames = GrpcWebFrameCodec.decodeFrames(sendResp.body)
        assertTrue(sendFrames.last().utf8Text.contains("grpc-status: 0"))

        // Verify StreamAgentStateUpdates contains both USER_INPUT and PLANNER_RESPONSE steps
        val streamResp = handler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("""{"conversationId":"$cascadeId"}""")
            )
        )
        val streamFrames = GrpcWebFrameCodec.decodeFrames(streamResp.body)
        assertEquals(2, streamFrames.size)
        val updateRoot = json.parseToJsonElement(streamFrames[0].utf8Text).jsonObject["update"]!!.jsonObject
        assertEquals(cascadeId, updateRoot["conversationId"]!!.jsonPrimitive.content)
        val stepsUpdate = updateRoot["mainTrajectoryUpdate"]!!.jsonObject["stepsUpdate"]!!.jsonObject
        assertEquals(2, stepsUpdate["totalLength"]!!.jsonPrimitive.int)
        val stepsArr = stepsUpdate["steps"]!!.jsonArray
        assertEquals("CORTEX_STEP_TYPE_USER_INPUT", stepsArr[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "Echoing from Android backend: Port Jetski Web to Android",
            stepsArr[1].jsonObject["plannerResponse"]!!.jsonObject["response"]!!.jsonPrimitive.content
        )
    }
}
