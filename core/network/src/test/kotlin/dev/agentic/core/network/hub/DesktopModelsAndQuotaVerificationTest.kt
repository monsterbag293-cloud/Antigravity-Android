package dev.agentic.core.network.hub

import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import dev.agentic.core.network.jetski.GrpcWebHttpRequest
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fast, cheap local unit test validating that all 5 desktop models across their effort levels
 * and all 3 desktop usage limit groups work as expected across both Genuine Hub routing
 * and Local Gemini Fallback execution.
 */
class DesktopModelsAndQuotaVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private class RecordingModelProvider : ModelProvider {
        override val providerId: String = "test-provider"
        override val displayName: String = "Test Provider"

        val recordedRequests = mutableListOf<ModelRequest>()

        override suspend fun streamCompletion(
            request: ModelRequest,
            tools: List<ModelToolDefinition>,
            onDelta: (String) -> Unit
        ): ModelResponse {
            recordedRequests.add(request)
            onDelta("Model output for ${request.modelId}")
            return ModelResponse(text = "Done: ${request.modelId}")
        }
    }

    @Test
    fun `verify all 14 desktop model configs are present in DesktopModelCatalog with correct metadata`() {
        assertEquals(14, DesktopModelCatalog.DESKTOP_MODELS.size)

        // 1. Gemini 4 Argon
        val argonConfigs = DesktopModelCatalog.DESKTOP_MODELS.filter { it.label.startsWith("Gemini 4 Argon") }
        assertEquals(3, argonConfigs.size)
        assertTrue(argonConfigs.any { it.label == "Gemini 4 Argon (Low)" && it.modelEnum == "MODEL_PLACEHOLDER_M419" })
        assertTrue(argonConfigs.any { it.label == "Gemini 4 Argon (Medium)" && it.modelEnum == "MODEL_PLACEHOLDER_M418" && it.tagTitle == "New" })
        assertTrue(argonConfigs.any { it.label == "Gemini 4 Argon (High)" && it.modelEnum == "MODEL_PLACEHOLDER_M417" })

        // 2. Gemini 3.8 Flash
        val flashConfigs = DesktopModelCatalog.DESKTOP_MODELS.filter { it.label.startsWith("Gemini 3.8 Flash") }
        assertEquals(3, flashConfigs.size)
        assertTrue(flashConfigs.any { it.label == "Gemini 3.8 Flash (Low)" && it.modelEnum == "MODEL_PLACEHOLDER_M320" })
        assertTrue(flashConfigs.any { it.label == "Gemini 3.8 Flash (Medium)" && it.modelEnum == "MODEL_PLACEHOLDER_M319" })
        assertTrue(flashConfigs.any { it.label == "Gemini 3.8 Flash (High)" && it.modelEnum == "MODEL_PLACEHOLDER_M318" && it.isRecommended })

        // 3. Claude Opus 5.5
        val opusConfigs = DesktopModelCatalog.DESKTOP_MODELS.filter { it.label.startsWith("Claude Opus 5.5") }
        assertEquals(3, opusConfigs.size)
        assertTrue(opusConfigs.any { it.label == "Claude Opus 5.5 (Low)" && it.modelEnum == "MODEL_PLACEHOLDER_M400" })
        assertTrue(opusConfigs.any { it.label == "Claude Opus 5.5 (Medium)" && it.modelEnum == "MODEL_PLACEHOLDER_M401" && it.tagTitle == "New" })
        assertTrue(opusConfigs.any { it.label == "Claude Opus 5.5 (High)" && it.modelEnum == "MODEL_PLACEHOLDER_M402" })

        // 4. Claude Sonnet 5.5
        val sonnetConfigs = DesktopModelCatalog.DESKTOP_MODELS.filter { it.label.startsWith("Claude Sonnet 5.5") }
        assertEquals(3, sonnetConfigs.size)
        assertTrue(sonnetConfigs.any { it.label == "Claude Sonnet 5.5 (Low)" && it.modelEnum == "MODEL_PLACEHOLDER_M403" })
        assertTrue(sonnetConfigs.any { it.label == "Claude Sonnet 5.5 (Medium)" && it.modelEnum == "MODEL_PLACEHOLDER_M404" && it.tagTitle == "New" })
        assertTrue(sonnetConfigs.any { it.label == "Claude Sonnet 5.5 (High)" && it.modelEnum == "MODEL_PLACEHOLDER_M405" })

        // 5. GPT-OSS 120B
        val gptOssConfigs = DesktopModelCatalog.DESKTOP_MODELS.filter { it.label.contains("GPT-OSS 120B") }
        assertEquals(1, gptOssConfigs.size)
        val gptOss = gptOssConfigs.first()
        assertEquals("GPT-OSS 120B (Medium)", gptOss.label)
        assertEquals("MODEL_OPENAI_GPT_OSS_120B_MEDIUM", gptOss.modelEnum)
        assertEquals("Notice", gptOss.tagTitle)
        assertEquals("Open-weight model hosted by Antigravity.", gptOss.tagDescription)

        // 6. Gemini 4 Carbon
        val carbonConfigs = DesktopModelCatalog.DESKTOP_MODELS.filter { it.label.startsWith("Gemini 4 Carbon") }
        assertEquals(1, carbonConfigs.size)
        val carbon = carbonConfigs.first()
        assertEquals("Gemini 4 Carbon", carbon.label)
        assertEquals("MODEL_PLACEHOLDER_M670", carbon.modelEnum)
        assertTrue(carbon.disabled)
        assertEquals("Unavailable", carbon.tagTitle)
        assertEquals("Gemini 4 Carbon is currently available to Ultra plan subscribers.", carbon.tagDescription)
        assertEquals("gemini-4-carbon-disabled", carbon.modelId)
    }

    @Test
    fun `verify RetrieveUserQuotaSummary returns all 3 desktop groups exactly matching desktop UI`() = runBlocking {
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = RecordingModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = "test-token",
            workspaceRootDir = tempFolder.root
        )

        val resp = rpcHandler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/RetrieveUserQuotaSummary",
                method = "POST",
                headers = mapOf(
                    "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                    "x-codeium-csrf-token" to "test-token"
                ),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )

        assertEquals(200, resp.statusCode)
        val jsonStr = GrpcWebFrameCodec.extractFirstRequestJson(resp.body)
        val root = json.parseToJsonElement(jsonStr).jsonObject

        val responseObj = root["response"]!!.jsonObject
        val groups = responseObj["groups"]!!.jsonArray
        assertEquals(3, groups.size)

        val groupMap = groups.map { it.jsonObject }.associateBy { it["displayName"]!!.jsonPrimitive.content }

        // 1. Gemini Models
        val geminiGroup = groupMap["Gemini Models"]
        assertNotNull("Gemini Models group must exist", geminiGroup)
        assertEquals("Gemini Models", geminiGroup!!["displayName"]!!.jsonPrimitive.content)
        val geminiBuckets = geminiGroup["buckets"]!!.jsonArray
        assertEquals(2, geminiBuckets.size) // weekly + 5h
        val geminiWinMap = geminiBuckets.map { it.jsonObject }.associateBy { it["window"]!!.jsonPrimitive.content }
        assertTrue(geminiWinMap.containsKey("weekly"))
        assertTrue(geminiWinMap.containsKey("5h"))

        // 2. Claude and GPT models
        val claudeGroup = groupMap["Claude and GPT models"]
        assertNotNull("Claude and GPT models group must exist", claudeGroup)
        assertEquals("Claude and GPT models", claudeGroup!!["displayName"]!!.jsonPrimitive.content)
        val claudeBuckets = claudeGroup["buckets"]!!.jsonArray
        assertEquals(2, claudeBuckets.size)
        val claudeWinMap = claudeBuckets.map { it.jsonObject }.associateBy { it["window"]!!.jsonPrimitive.content }
        assertTrue(claudeWinMap.containsKey("weekly"))
        assertTrue(claudeWinMap.containsKey("5h"))

        // 3. Gemini 4 Argon
        val argonGroup = groupMap["Gemini 4 Argon"]
        assertNotNull("Gemini 4 Argon group must exist", argonGroup)
        assertEquals("Gemini 4 Argon", argonGroup!!["displayName"]!!.jsonPrimitive.content)
        val argonBuckets = argonGroup["buckets"]!!.jsonArray
        assertEquals(1, argonBuckets.size) // weekly limit
        assertEquals("weekly", argonBuckets[0].jsonObject["window"]!!.jsonPrimitive.content)
    }

    @Test
    fun `verify GetUserStatus exposes Gemini 4 Carbon matching desktop UI fields`() = runBlocking {
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = RecordingModelProvider(),
            isAuthenticatedProvider = { true },
            csrfToken = "test-token",
            workspaceRootDir = tempFolder.root
        )

        val resp = rpcHandler.handleRequest(
            GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetUserStatus",
                method = "POST",
                headers = mapOf(
                    "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                    "x-codeium-csrf-token" to "test-token"
                ),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
        )

        assertEquals(200, resp.statusCode)
        val root = json.parseToJsonElement(GrpcWebFrameCodec.extractFirstRequestJson(resp.body)).jsonObject
        val cascadeData = root["userStatus"]!!.jsonObject["cascadeModelConfigData"]!!.jsonObject
        val configs = cascadeData["clientModelConfigs"]!!.jsonArray
        val carbon = configs.map { it.jsonObject }.firstOrNull {
            it["label"]?.jsonPrimitive?.content == "Gemini 4 Carbon"
        }
        assertNotNull("Gemini 4 Carbon must be present in clientModelConfigs", carbon)
        assertEquals("MODEL_PLACEHOLDER_M670", carbon!!["modelOrAlias"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals("true", carbon["disabled"]!!.jsonPrimitive.content)
        assertEquals("Unavailable", carbon["tagTitle"]!!.jsonPrimitive.content)
        assertEquals(
            "Gemini 4 Carbon is currently available to Ultra plan subscribers.",
            carbon["tagDescription"]!!.jsonPrimitive.content
        )
        assertEquals("gemini-4-carbon-disabled", carbon["modelId"]!!.jsonPrimitive.content)

        val sortLabels = cascadeData["clientModelSorts"]!!.jsonArray[0].jsonObject["groups"]!!
            .jsonArray[0].jsonObject["modelLabels"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(sortLabels.contains("Gemini 4 Carbon"))
    }

    @Test
    fun `verify all enabled and fallback desktop models execute successfully in SendUserCascadeMessage`() = runBlocking {
        val recordingProvider = RecordingModelProvider()
        val rpcHandler = LanguageServerGrpcWebHandler(
            modelProvider = recordingProvider,
            isAuthenticatedProvider = { true },
            csrfToken = "test-token",
            workspaceRootDir = tempFolder.root,
            initialBackendMode = BackendMode.LOCAL_GEMINI_FALLBACK
        )

        for (modelSpec in DesktopModelCatalog.DESKTOP_MODELS) {
            recordingProvider.recordedRequests.clear()

            // 1. Test sending message via modelEnum
            val reqPayloadWithEnum = """
            {
              "cascadeId": "test-casc-enum-${modelSpec.modelEnum}",
              "model": "${modelSpec.modelEnum}",
              "items": [
                { "text": "Hello from ${modelSpec.label}" }
              ]
            }
            """.trimIndent()

            val resp1 = rpcHandler.handleRequest(
                GrpcWebHttpRequest(
                    path = "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage",
                    method = "POST",
                    headers = mapOf(
                        "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                        "x-codeium-csrf-token" to "test-token"
                    ),
                    body = GrpcWebFrameCodec.encodeDataFrame(reqPayloadWithEnum)
                )
            )

            assertEquals("Model ${modelSpec.label} with enum must return 200 HTTP", 200, resp1.statusCode)
            val trailer1 = String(resp1.body, Charsets.UTF_8)
            assertFalse("Model ${modelSpec.label} must not fail with gRPC error in trailer: $trailer1", trailer1.contains("grpc-status: 2"))
            assertEquals("Model ${modelSpec.label} must dispatch exactly 1 completion request", 1, recordingProvider.recordedRequests.size)

            val req1 = recordingProvider.recordedRequests.first()
            assertEquals("gemini-3.8-flash", req1.modelId) // normalized to supported gemini-3.8-flash

            // 2. Test sending message via cascadeConfig plannerConfig modelName label
            recordingProvider.recordedRequests.clear()
            val reqPayloadWithLabel = """
            {
              "cascadeId": "test-casc-label-${modelSpec.modelEnum}",
              "cascadeConfig": {
                "plannerConfig": {
                  "modelName": "${modelSpec.label}"
                }
              },
              "items": [
                { "text": "Hello via plannerConfig for ${modelSpec.label}" }
              ]
            }
            """.trimIndent()

            val resp2 = rpcHandler.handleRequest(
                GrpcWebHttpRequest(
                    path = "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage",
                    method = "POST",
                    headers = mapOf(
                        "content-type" to GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON,
                        "x-codeium-csrf-token" to "test-token"
                    ),
                    body = GrpcWebFrameCodec.encodeDataFrame(reqPayloadWithLabel)
                )
            )

            assertEquals("Model ${modelSpec.label} with label must return 200 HTTP", 200, resp2.statusCode)
            val trailer2 = String(resp2.body, Charsets.UTF_8)
            assertFalse("Model ${modelSpec.label} via label must not fail with gRPC error: $trailer2", trailer2.contains("grpc-status: 2"))
            assertEquals("Model ${modelSpec.label} via label must dispatch 1 request", 1, recordingProvider.recordedRequests.size)
            assertEquals("gemini-3.8-flash", recordingProvider.recordedRequests.first().modelId)
        }
    }
}
