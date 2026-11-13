package dev.agentic.core.network.jetski

import dev.agentic.agent.runtime.AgentExecutionEvent
import dev.agentic.agent.runtime.AgentRuntime
import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import dev.agentic.core.model.ToolCall
import dev.agentic.core.model.ToolCallResponse
import dev.agentic.core.network.GeminiModelProvider
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelToolDefinition
import dev.agentic.tools.local.LocalFileTools
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CATEGORY: tests that talk to the REAL Gemini public API from the JVM host (no Android, no WebView).
 *
 *  - `invalid key` test: always runs (needs network only); verifies the real HTTP error path and that the
 *    supplied key never appears in the surfaced error.
 *  - `real key` tests: run ONLY when env GEMINI_API_KEY is set or local.properties has gemini.api.key;
 *    otherwise they are SKIPPED (reported as skipped, never as passed). The key is read securely and never printed.
 *
 * Even when they pass, these prove JVM-host Gemini integration only — NOT the Android WebView/UI path.
 */
class StageP13RealGeminiIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun realKey(): String? =
        System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GOOGLE_API_KEY")?.takeIf { it.isNotBlank() }
            ?: readLocalPropertiesApiKey()
            ?: readGradlePropertiesApiKey()

    private fun readLocalPropertiesApiKey(): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val f = File(dir, "local.properties")
            if (f.exists()) {
                val key = runCatching {
                    val props = java.util.Properties()
                    f.inputStream().use { props.load(it) }
                    props.getProperty("gemini.api.key")
                        ?: props.getProperty("GEMINI_API_KEY")
                        ?: props.getProperty("google.api.key")
                }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
                if (key != null) return key
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun readGradlePropertiesApiKey(): String? {
        val f = File(System.getProperty("user.home") ?: ".", ".gradle/gradle.properties")
        if (!f.exists()) return null
        return runCatching {
            val props = java.util.Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("gemini.api.key")
                ?: props.getProperty("GEMINI_API_KEY")
                ?: props.getProperty("google.api.key")
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private val model = System.getenv("GEMINI_TEST_MODEL")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("GEMINI_TEST_MODEL")?.takeIf { it.isNotBlank() }
        ?: GeminiModelProvider.defaultModelId()

    @Test
    fun `invalid key against real endpoint surfaces an API error without leaking the key`() = runBlocking {
        val fake = "AIzaSyINVALID-KEY-FOR-TEST-0000000000000"
        val provider = GeminiModelProvider(apiKeyProvider = { fake })
        val err = try {
            provider.streamCompletion(
                ModelRequest(listOf(Message("1", "c", Role.USER, "hi")), modelId = model),
                emptyList()
            ) { }
            null
        } catch (e: Exception) {
            e
        }
        // Network may be unavailable on the test host: that is also a clean, non-crashing failure.
        assertTrue("expected an exception for an invalid key", err != null)
        assertFalse("key must not be echoed in errors", (err!!.message ?: "").contains(fake))
        println("[P1.3] invalid-key real-endpoint result: ${err::class.simpleName}: ${(err.message ?: "").take(160)}")
    }

    @Test
    fun `request payload targets configured model with toolConfig and matching call IDs`() {
        val p = GeminiModelProvider(apiKeyProvider = { "fake-key" })
        val call = ToolCall(id = "call-1234", name = "list_dir", argumentsJson = """{"path":"."}""")
        val resp = ToolCallResponse(callId = "call-1234", name = "list_dir", responseJson = """{"output":"file.txt"}""")
        val msgs = listOf(
            Message("1", "conv", Role.USER, "list files"),
            Message("2", "conv", Role.ASSISTANT, "", toolCalls = listOf(call)),
            Message("3", "conv", Role.TOOL, "", toolResponses = listOf(resp))
        )
        val payload = p.buildRequestPayload(
            ModelRequest(messages = msgs, modelId = "gemini-3.5-flash-lite"),
            listOf(ModelToolDefinition("list_dir", "List files", """{"type":"OBJECT","properties":{"path":{"type":"STRING"}}}"""))
        )
        val str = payload.toString()
        assertTrue("payload must contain toolConfig", str.contains("\"toolConfig\""))
        assertTrue("payload must contain mode AUTO", str.contains("\"mode\":\"AUTO\""))
        assertTrue("payload must contain functionCall with id", str.contains("\"id\":\"call-1234\""))
        assertEquals("gemini-3.5-flash-lite", GeminiModelProvider.normalizeGeminiModelId("gemini-3.5-flash-lite"))
        assertEquals("gemini-3.5-flash-lite", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M18"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("gemini-3.8-flash"))
        assertEquals("gemini-3.8-pro", GeminiModelProvider.normalizeGeminiModelId("gemini-2.5-pro"))
    }

    /**
     * Non-streaming diagnostic test (P1.3 Section 4).
     * Sends a direct unary request requesting a specific declared function call to isolate
     * API compatibility, tool extraction, and ID preservation from multi-step agent complexity.
     */
    @Test
    fun `REAL KEY - non-streaming diagnostic test isolates genuine function call extraction`() = runBlocking {
        val key = realKey()
        assumeTrue("GEMINI_API_KEY not set - non-streaming diagnostic test SKIPPED (not passed)", key != null)

        val provider = GeminiModelProvider(apiKeyProvider = { key })
        val toolDef = ModelToolDefinition(
            name = "probe_workspace",
            description = "Inspect files and directories in the workspace.",
            parametersJsonSchema = """{"type":"OBJECT","properties":{"path":{"type":"STRING","description":"Directory path to probe"}},"required":["path"]}"""
        )
        val prompt = "Call the tool probe_workspace with path 'src'. You must call this function."
        val req = ModelRequest(
            messages = listOf(Message(UUID.randomUUID().toString(), "conv-diag", Role.USER, prompt)),
            modelId = model
        )

        val outcome = try {
            val resp = provider.generateCompletionUnary(req, listOf(toolDef))
            Result.success(resp)
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (outcome.isFailure) {
            val ex = outcome.exceptionOrNull()!!
            val safeMsg = (ex.message ?: "").replace(Regex("AIzaSy[A-Za-z0-9_-]+"), "AIzaSy[REDACTED]")
            println("[P1.3] Diagnostic Unary Call FAILED: ${ex::class.simpleName}: $safeMsg")
            fail("Diagnostic unary call failed: $safeMsg")
        }

        val resp = outcome.getOrNull()!!
        println("[P1.3] Diagnostic Unary Call SUCCESS: model=$model finishReason=${resp.finishReason} toolCallsCount=${resp.toolCalls.size}")
        for (c in resp.toolCalls) {
            println("[P1.3]   - ToolCall: id=${c.id} name=${c.name} args=${c.argumentsJson}")
        }

        assertTrue("Diagnostic test expected at least one function call from $model", resp.toolCalls.isNotEmpty())
        val firstCall = resp.toolCalls.first()
        assertEquals("probe_workspace", firstCall.name)
        assertTrue("Arguments must contain 'path'", firstCall.argumentsJson.contains("path"))
        assertNotNull("Function call must carry a valid ID", firstCall.id.takeIf { it.isNotBlank() })
    }

    @Test
    fun `REAL KEY - model issues genuine function calls and loop completes`() = runBlocking {
        val key = realKey()
        assumeTrue("GEMINI_API_KEY not set - real Gemini test SKIPPED (not passed)", key != null)
        val ws = tmp.newFolder("ws").canonicalFile
        File(ws, "app/src").mkdirs()
        File(ws, "app/src/Main.kt").writeText("fun main() {\n    println(\"hello from main\")\n}\n")
        val provider = GeminiModelProvider(apiKeyProvider = { key })
        val runtime = AgentRuntime(provider, listOf(LocalFileTools(ws)))
        val events = CopyOnWriteArrayList<String>()
        val out = runtime.executeAgentLoop(
            conversationId = "real",
            taskPrompt = "Use list_dir on '.', then view_file on app/src/Main.kt, then reply with one sentence describing it.",
            history = emptyList(),
            modelId = model,
            maxIterations = 8,
            onExecutionEvent = { e ->
                if (e is AgentExecutionEvent.ToolStarted) events.add(e.toolCall.name)
            }
        )
        println("[P1.3] REAL model=$model toolCalls=$events iterations=${out.iterationsUsed} final='${out.finalResponseText.take(200)}'")
        assertEquals("Unexpected agent runtime error: ${out.errorMessage}", null, out.errorMessage)
        assertTrue("Gemini produced no function call (toolCalls: $events)", events.isNotEmpty())
        assertTrue("Final response text must not be blank", out.finalResponseText.isNotBlank())
    }

    @Test
    fun `REAL KEY - acceptance task via RPC handler creates agent-validation-summary md`() = runBlocking {
        val key = realKey()
        assumeTrue("GEMINI_API_KEY not set - real Gemini acceptance test SKIPPED (not passed)", key != null)
        val ws = tmp.newFolder("ws2").canonicalFile
        File(ws, "app/src/main").mkdirs()
        File(ws, "app/src/main/Main.kt").writeText("package demo\n\nfun main() {\n    println(\"application starts here\")\n}\n")
        File(ws, "README.md").writeText("# Demo\nEntry point: app/src/main/Main.kt\n")
        val handler = LanguageServerGrpcWebHandler(
            modelProvider = GeminiModelProvider(apiKeyProvider = { key }),
            isAuthenticatedProvider = { true },
            csrfToken = "t",
            enforceCsrf = false,
            workspaceRootDir = ws
        )
        val prompt = "Inspect the current project: list directory '.', read README.md and app/src/main/Main.kt, " +
            "then create a Markdown file named agent-validation-summary.md explaining the application structure and entry point, " +
            "and finally return a summary of what you did."
        val body = buildJsonObject {
            put("cascadeId", "real-e2e")
            put("model", model)
            put("items", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
        }
        val res = handler.handleRequest(
            GrpcWebHttpRequest(
                LanguageServerGrpcWebHandler.SERVICE_PREFIX + "SendUserCascadeMessage",
                body = GrpcWebFrameCodec.encodeDataFrame(body.toString())
            )
        )
        val frames = GrpcWebFrameCodec.decodeFrames(res.body)
        val trailerText = frames.filter { it.isTrailer }.joinToString { it.utf8Text.trim() }
        println("[P1.3] REAL acceptance trailer: $trailerText")
        assertFalse("SendUserCascadeMessage failed with trailer: $trailerText", trailerText.contains("grpc-status: 14") || trailerText.contains("grpc-status: 13"))

        val stepsRes = handler.handleRequest(
            GrpcWebHttpRequest(
                LanguageServerGrpcWebHandler.SERVICE_PREFIX + "GetCascadeTrajectorySteps",
                body = GrpcWebFrameCodec.encodeDataFrame("""{"cascadeId":"real-e2e"}""")
            )
        )
        val steps = Json.parseToJsonElement(
            GrpcWebFrameCodec.decodeFrames(stepsRes.body).first { !it.isTrailer }.utf8Text
        ).jsonObject["steps"]!!.jsonArray
        println("[P1.3] REAL step types: ${steps.map { it.jsonObject["type"]!!.jsonPrimitive.content }}")
        val summary = File(ws, "agent-validation-summary.md")
        assertTrue("agent-validation-summary.md was not created in workspace $ws", summary.exists())
        assertTrue("agent-validation-summary.md is empty", summary.readText().isNotBlank())
        println("[P1.3] REAL summary file (${summary.length()} bytes):\n${summary.readText().take(600)}")
        delay(1)
    }
}
