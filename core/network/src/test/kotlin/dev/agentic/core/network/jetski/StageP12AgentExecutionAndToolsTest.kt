package dev.agentic.core.network.jetski

import dev.agentic.agent.runtime.AgentExecutionEvent
import dev.agentic.agent.runtime.AgentRuntime
import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import dev.agentic.core.model.ToolCall
import dev.agentic.core.model.ToolCallResponse
import dev.agentic.core.network.GeminiModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import dev.agentic.tools.local.LocalFileTools
import dev.agentic.tools.local.SandboxedCommandExecutor
import dev.agentic.tools.local.WorkspacePathResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P1.2 — Genuine Multi-Step Agent Execution Engine tests.
 *
 * Uses a scripted [ModelProvider] (no network, no fabricated success): every model turn is explicit,
 * while tools, the agent loop, the sandbox and the RPC handler are the real production code.
 */
class StageP12AgentExecutionAndToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- helpers

    private class ScriptedProvider(private val script: List<ModelResponse>) : ModelProvider {
        override val providerId = "scripted"
        override val displayName = "Scripted"
        val requests = CopyOnWriteArrayList<ModelRequest>()
        val toolsSeen = CopyOnWriteArrayList<List<ModelToolDefinition>>()

        override suspend fun streamCompletion(
            request: ModelRequest,
            tools: List<ModelToolDefinition>,
            onDelta: (String) -> Unit
        ): ModelResponse {
            val idx = requests.size
            requests.add(request)
            toolsSeen.add(tools)
            val r = script.getOrElse(idx) { script.last() }
            if (r.text.isNotEmpty()) onDelta(r.text)
            return r
        }
    }

    private fun call(id: String, name: String, args: JsonObject) =
        ToolCall(id = id, name = name, argumentsJson = args.toString())

    private fun toolTurn(vararg calls: ToolCall) =
        ModelResponse(text = "", toolCalls = calls.toList(), finishReason = "tool_calls")

    private fun finalTurn(text: String) = ModelResponse(text = text)

    private fun workspace(): File = tmp.newFolder("ws").canonicalFile

    private fun handler(
        provider: ModelProvider,
        ws: File,
        maxIterations: Int = AgentRuntime.DEFAULT_MAX_ITERATIONS
    ) = LanguageServerGrpcWebHandler(
        modelProvider = provider,
        isAuthenticatedProvider = { true },
        csrfToken = "t",
        enforceCsrf = false,
        workspaceRootDir = ws,
        maxAgentIterations = maxIterations
    )

    private fun rpc(h: LanguageServerGrpcWebHandler, method: String, body: JsonObject): JsonObject {
        val res = runBlocking {
            h.handleRequest(
                GrpcWebHttpRequest(
                    path = LanguageServerGrpcWebHandler.SERVICE_PREFIX + method,
                    body = GrpcWebFrameCodec.encodeDataFrame(body.toString())
                )
            )
        }
        val frames = GrpcWebFrameCodec.decodeFrames(res.body)
        val data = frames.firstOrNull { !it.isTrailer }
            ?: throw AssertionError("No data frame for $method: ${frames.map { it.utf8Text }}")
        return json.parseToJsonElement(data.utf8Text).jsonObject
    }

    private fun rpcTrailerText(h: LanguageServerGrpcWebHandler, method: String, body: JsonObject): String {
        val res = runBlocking {
            h.handleRequest(
                GrpcWebHttpRequest(
                    path = LanguageServerGrpcWebHandler.SERVICE_PREFIX + method,
                    body = GrpcWebFrameCodec.encodeDataFrame(body.toString())
                )
            )
        }
        return GrpcWebFrameCodec.decodeFrames(res.body).filter { it.isTrailer }.joinToString { it.utf8Text }
    }

    private fun sendMessage(h: LanguageServerGrpcWebHandler, cascadeId: String, text: String) = rpc(
        h, "SendUserCascadeMessage",
        buildJsonObject {
            put("cascadeId", cascadeId)
            put("items", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        }
    )

    private fun steps(h: LanguageServerGrpcWebHandler, cascadeId: String) =
        rpc(h, "GetCascadeTrajectorySteps", buildJsonObject { put("cascadeId", cascadeId) })["steps"]!!.jsonArray

    // ------------------------------------------------- 1. function-call parsing

    @Test
    fun `gemini functionCall parsing preserves thoughtSignature and tolerates malformed args`() {
        val p = GeminiModelProvider(apiKeyProvider = { "k" })
        val chunk = """{"candidates":[{"content":{"parts":[
            {"thought":true,"text":"thinking..."},
            {"functionCall":{"name":"view_file","args":{"path":"a.kt"}},"thoughtSignature":"SIG123"},
            {"text":"visible"}]},"finishReason":"STOP"}]}"""
        val ex = p.extractDeltaFromChunk(chunk)
        assertEquals("visible", ex.text)
        assertEquals("thinking...", ex.thinking)
        assertEquals(1, ex.toolCalls.size)
        assertEquals("view_file", ex.toolCalls[0].name)
        assertEquals("SIG123", ex.toolCalls[0].thoughtSignature)
        assertEquals("a.kt", json.parseToJsonElement(ex.toolCalls[0].argumentsJson).jsonObject["path"]!!.jsonPrimitive.content)

        // Garbage chunk must not throw
        val bad = p.extractDeltaFromChunk("{not json")
        assertEquals("", bad.text)
        assertTrue(bad.toolCalls.isEmpty())

        // functionCall with non-object args must not throw
        val weird = p.extractDeltaFromChunk(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"x","args":"oops"}}]}}]}"""
        )
        assertEquals(1, weird.toolCalls.size)
    }

    // ----------------------------------- 2/3/4. declarations, responses, multi-turn

    @Test
    fun `request payload serializes tool declarations, functionCall and functionResponse turns`() {
        val p = GeminiModelProvider(apiKeyProvider = { "k" })
        val c1 = ToolCall("c1", "list_dir", """{"path":"."}""", thoughtSignature = "SIG")
        val c2 = ToolCall("c2", "view_file", """{"path":"a.kt"}""")
        val msgs = listOf(
            Message("1", "conv", Role.USER, "hi"),
            Message("2", "conv", Role.ASSISTANT, "", toolCalls = listOf(c1)),
            Message("3", "conv", Role.TOOL, "", toolResponses = listOf(ToolCallResponse("c1", "list_dir", """{"output":"a.kt"}"""))),
            Message("4", "conv", Role.ASSISTANT, "", toolCalls = listOf(c2)),
            Message("5", "conv", Role.TOOL, "", toolResponses = listOf(ToolCallResponse("c2", "view_file", """{"output":"x"}""")))
        )
        val payload = p.buildRequestPayload(
            ModelRequest(messages = msgs, modelId = "gemini-2.5-pro"),
            listOf(ModelToolDefinition("list_dir", "List", """{"type":"OBJECT","properties":{}}"""))
        )
        val text = payload.toString()
        val decl = payload["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals("list_dir", decl[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(text.contains("\"functionCall\""))
        assertTrue(text.contains("\"functionResponse\""))
        assertTrue("thoughtSignature must be replayed", text.contains("\"thoughtSignature\":\"SIG\""))
        assertEquals(2, Regex("\"functionCall\"").findAll(text).count())
        assertEquals(2, Regex("\"functionResponse\"").findAll(text).count())
        assertEquals(5, payload["contents"]!!.jsonArray.size)
    }

    @Test
    fun `frontend model ids are normalised to supported Gemini ids`() {
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId(null))
        assertEquals("gemini-3.5-flash-lite", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M18"))
        assertEquals("gemini-3.5-flash-lite", GeminiModelProvider.normalizeGeminiModelId("gemini-3.5-flash-lite"))
        assertEquals("gemini-3.8-pro", GeminiModelProvider.normalizeGeminiModelId("gemini-2.5-pro"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("gemini-2.5-flash"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("gemini-3.8-flash"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M318"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M319"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M320"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M418"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M401"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M404"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_PLACEHOLDER_M670"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("gemini-4-carbon-disabled"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("Gemini 4 Carbon"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_OPENAI_GPT_OSS_120B_MEDIUM"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("MODEL_GOOGLE_GEMINI_2_5_FLASH"))
        assertEquals("gemini-3.8-flash", GeminiModelProvider.normalizeGeminiModelId("312"))
    }

    // ------------------------------------------------------- 5. multi-step loop

    @Test
    fun `agent loop executes consecutive tool calls then returns final text with functionResponse history`() = runBlocking {
        val ws = workspace()
        File(ws, "a.txt").writeText("hello")
        val provider = ScriptedProvider(
            listOf(
                toolTurn(call("1", "list_dir", buildJsonObject { put("path", ".") })),
                toolTurn(call("2", "view_file", buildJsonObject { put("path", "a.txt") })),
                finalTurn("done")
            )
        )
        val rt = AgentRuntime(provider, listOf(LocalFileTools(ws)))
        val events = CopyOnWriteArrayList<AgentExecutionEvent>()
        val out = rt.executeAgentLoop("c", "go", emptyList(), onExecutionEvent = { events.add(it) })
        assertEquals("done", out.finalResponseText)
        assertEquals(2, out.executedToolSteps.size)
        assertTrue(out.executedToolSteps.all { it.toolResult.success })
        assertEquals(3, provider.requests.size)
        // Third request must contain both functionResponse turns (multi-turn preservation)
        val last = provider.requests.last().messages
        assertEquals(2, last.count { it.role == Role.TOOL })
        assertEquals(2, last.count { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() })
        assertTrue(provider.toolsSeen.all { t -> t.any { it.name == "view_file" } })
        assertTrue(events.any { it is AgentExecutionEvent.Completed })
        assertFalse(out.reachedMaxIterations)
    }

    // ----------------------------------------------------- 6. max iterations

    @Test
    fun `maxIterations stops an infinite tool loop`() = runBlocking {
        val ws = workspace()
        val provider = ScriptedProvider(
            listOf(toolTurn(call("x", "list_dir", buildJsonObject { put("path", ".") })))
        )
        val out = AgentRuntime(provider, listOf(LocalFileTools(ws)))
            .executeAgentLoop("c", "loop", emptyList(), maxIterations = 3)
        assertTrue(out.reachedMaxIterations)
        assertEquals(3, provider.requests.size)
    }

    // ------------------------------------------------------- 7. cancellation

    @Test
    fun `coroutine cancellation aborts the agent loop`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val blocking = object : ModelProvider {
            override val providerId = "b"
            override val displayName = "b"
            override suspend fun streamCompletion(
                request: ModelRequest, tools: List<ModelToolDefinition>, onDelta: (String) -> Unit
            ): ModelResponse {
                started.complete(Unit)
                delay(60_000)
                return ModelResponse("never")
            }
        }
        val rt = AgentRuntime(blocking, emptyList())
        val job = async(Dispatchers.Default) { rt.executeAgentLoop("c", "x", emptyList()) }
        started.await()
        job.cancel()
        val cancelled = try {
            withTimeout(5_000) { job.await() }
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `CancelCascadeInvocation RPC is accepted and leaves cascade idle`() {
        val ws = workspace()
        val h = handler(ScriptedProvider(listOf(finalTurn("ok"))), ws)
        sendMessage(h, "casc-cancel", "hello")
        val r = rpc(h, "CancelCascadeInvocation", buildJsonObject { put("cascadeId", "casc-cancel") })
        assertTrue(r.isEmpty())
        assertEquals("CASCADE_RUN_STATUS_IDLE", h.getTrajectoriesSnapshot()["casc-cancel"]!!.status)
    }

    // ------------------------------------------------ 8. tool failure recovery

    @Test
    fun `tool failures are fed back to the model instead of crashing the loop`() = runBlocking {
        val ws = workspace()
        val provider = ScriptedProvider(
            listOf(
                toolTurn(
                    call("1", "view_file", buildJsonObject { put("path", "missing.txt") }),
                    call("2", "no_such_tool", buildJsonObject {}),
                    ToolCall("3", "view_file", "{broken json", parseError = "bad args")
                ),
                finalTurn("recovered")
            )
        )
        val out = AgentRuntime(provider, listOf(LocalFileTools(ws)))
            .executeAgentLoop("c", "x", emptyList())
        assertEquals("recovered", out.finalResponseText)
        assertEquals(3, out.executedToolSteps.size)
        assertTrue(out.executedToolSteps.none { it.toolResult.success })
        val toolMsg = provider.requests.last().messages.last { it.role == Role.TOOL }
        assertEquals(3, toolMsg.toolResponses.size)
        assertTrue(toolMsg.toolResponses.all { it.isError })
    }

    // ------------------------------------------------------ 9. path traversal

    @Test
    fun `path traversal and absolute outside paths are rejected, in-workspace absolute paths allowed`() {
        val ws = workspace()
        File(ws, "ok.txt").writeText("x")
        val isWindowsHost = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        // On a Windows host "/etc/passwd" is drive-relative (maps inside the workspace); on Android/POSIX it is absolute and rejected.
        val badPaths = mutableListOf("../../etc/passwd", "../outside.txt", "C:/Windows/win.ini".takeIf { isWindowsHost } ?: "/etc/passwd")
        if (!isWindowsHost) badPaths.add("file:///etc/passwd")
        for (bad in badPaths) {
            val rejected = try {
                WorkspacePathResolver.resolveSafeFile(ws, bad); false
            } catch (_: SecurityException) { true }
            assertTrue("should reject $bad", rejected)
        }
        // absolute path (POSIX-style leading slash preserved) inside workspace
        val abs = File(ws, "ok.txt").absolutePath
        assertEquals(File(ws, "ok.txt").canonicalFile, WorkspacePathResolver.resolveSafeFile(ws, abs).canonicalFile)
        assertEquals(
            File(ws, "ok.txt").canonicalFile,
            WorkspacePathResolver.resolveSafeFile(ws, File(ws, "ok.txt").toURI().toString()).canonicalFile
        )
        assertEquals(File(ws, "ok.txt").canonicalFile, WorkspacePathResolver.resolveSafeFile(ws, "ok.txt").canonicalFile)

        // via tool
        val r = runBlocking {
            LocalFileTools(ws).executeTool(call("1", "view_file", buildJsonObject { put("path", "../../etc/passwd") }))
        }
        assertFalse(r.success)
    }

    // ----------------------------------------------- 10. read / write / edit

    @Test
    fun `write respects overwrite flag and edit_file changes only the targeted block`() = runBlocking {
        val ws = workspace()
        val tools = LocalFileTools(ws)
        val w1 = tools.executeTool(call("1", "write_to_file", buildJsonObject {
            put("path", "sub/n.txt"); put("content", "line1\nline2\nline3\n")
        }))
        assertTrue(w1.output, w1.success)
        assertEquals("line1\nline2\nline3\n", File(ws, "sub/n.txt").readText())

        val w2 = tools.executeTool(call("2", "write_to_file", buildJsonObject {
            put("path", "sub/n.txt"); put("content", "CLOBBER"); put("overwrite", false)
        }))
        assertFalse(w2.success)
        assertEquals("line1\nline2\nline3\n", File(ws, "sub/n.txt").readText())

        val e = tools.executeTool(call("3", "edit_file", buildJsonObject {
            put("path", "sub/n.txt"); put("target_content", "line2"); put("replacement_content", "LINE-TWO")
        }))
        assertTrue(e.output, e.success)
        assertEquals("line1\nLINE-TWO\nline3\n", File(ws, "sub/n.txt").readText())

        val eMissing = tools.executeTool(call("4", "edit_file", buildJsonObject {
            put("path", "sub/n.txt"); put("target_content", "absent"); put("replacement_content", "z")
        }))
        assertFalse(eMissing.success)

        val v = tools.executeTool(call("5", "view_file", buildJsonObject {
            put("path", "sub/n.txt"); put("start_line", 2); put("end_line", 2)
        }))
        assertTrue(v.success)
        assertTrue(v.output.contains("LINE-TWO"))
        assertFalse(v.output.contains("line3"))
    }

    // ----------------------------------------------------- 11. directory/search

    @Test
    fun `list_dir grep_search find_by_name work and ReadDir StatUri use schema fields`() {
        val ws = workspace()
        File(ws, "src").mkdirs()
        File(ws, "src/Main.kt").writeText("fun main() { println(\"needle\") }\n")
        File(ws, "README.md").writeText("readme")
        val tools = LocalFileTools(ws)
        runBlocking {
            val ld = tools.executeTool(call("1", "list_dir", buildJsonObject { put("path", ".") }))
            assertTrue(ld.success); assertTrue(ld.output.contains("src")); assertTrue(ld.output.contains("README.md"))
            val g = tools.executeTool(call("2", "grep_search", buildJsonObject { put("query", "needle") }))
            assertTrue(g.success); assertTrue(g.output.contains("Main.kt"))
            val f = tools.executeTool(call("3", "find_by_name", buildJsonObject { put("pattern", "*.kt") }))
            assertTrue(f.success); assertTrue(f.output.contains("Main.kt"))
        }

        val h = handler(ScriptedProvider(listOf(finalTurn("x"))), ws)
        val rd = rpc(h, "ReadDir", buildJsonObject { put("uri", ws.toURI().toString()) })
        val entries = rd["entries"]!!.jsonArray
        assertEquals(2, entries.size)
        assertEquals("FILE_TYPE_DIRECTORY", entries[0].jsonObject["fileType"]!!.jsonPrimitive.content)
        assertTrue(entries[0].jsonObject["uri"]!!.jsonPrimitive.content.startsWith("file://"))

        val st = rpc(h, "StatUri", buildJsonObject { put("uri", File(ws, "README.md").toURI().toString()) })
        assertEquals("FILE_TYPE_FILE", st["fileType"]!!.jsonPrimitive.content)
        assertEquals("6", st["size"]!!.jsonPrimitive.content)
        assertTrue(st["normalizedUri"]!!.jsonPrimitive.content.endsWith("README.md"))
        assertNotNull(st["modTime"])
    }

    @Test
    fun `ReadFile and WriteFile RPCs round-trip and honour overwrite=false`() {
        val ws = workspace()
        val h = handler(ScriptedProvider(listOf(finalTurn("x"))), ws)
        val b64 = java.util.Base64.getEncoder().encodeToString("data".toByteArray())
        rpc(h, "WriteFile", buildJsonObject { put("uri", "f.txt"); put("content", b64) })
        assertEquals("data", File(ws, "f.txt").readText())
        val rf = rpc(h, "ReadFile", buildJsonObject { put("uri", "f.txt") })
        assertEquals(b64, rf["content"]!!.jsonPrimitive.content)
        val trailer = rpcTrailerText(h, "WriteFile", buildJsonObject {
            put("uri", "f.txt"); put("content", b64); put("overwrite", false)
        })
        assertTrue(trailer, trailer.contains("grpc-status: 6"))
        val esc = rpcTrailerText(h, "ReadFile", buildJsonObject { put("uri", "../../etc/passwd") })
        assertTrue(esc, esc.contains("grpc-status: 7"))
    }

    // --------------------------------------------------------- 12. commands

    @Test
    fun `command executor runs in workspace, blocks privileged and traversal commands, enforces timeout`() = runBlocking {
        assumeTrue("needs a POSIX or cmd shell", true)
        val ws = workspace()
        val ex = SandboxedCommandExecutor(ws)
        val ok = ex.executeCommand("echo hello-sandbox")
        assertEquals(0, ok.exitCode)
        assertTrue(ok.stdout.contains("hello-sandbox"))
        assertEquals(null, ok.blockedReason)

        val sudo = ex.executeCommand("sudo ls")
        assertNotNull(sudo.blockedReason)
        val trav = ex.executeCommand("cat ../../etc/passwd")
        assertNotNull(trav.blockedReason)
        val badCwd = ex.executeCommand("echo hi", cwdUriOrPath = "../..")
        assertTrue(badCwd.blockedReason != null || badCwd.exitCode != 0)

        val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val sleepCmd = if (isWin) "ping -n 20 127.0.0.1" else "sleep 20"
        val t = ex.executeCommand(sleepCmd, timeoutMs = 700)
        assertTrue(t.timedOut)
    }

    @Test
    fun `killCommand cancels a running command and GetActiveCommand reports it`() = runBlocking {
        val ws = workspace()
        val ex = SandboxedCommandExecutor(ws)
        val isWin = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        val cmd = if (isWin) "ping -n 30 127.0.0.1" else "sleep 30"
        val d = async(Dispatchers.IO) { ex.executeCommand(cmd, timeoutMs = 30_000, commandId = "kill-me") }
        var seen = false
        repeat(40) {
            if (ex.getActiveCommands().any { it.commandId == "kill-me" }) { seen = true; return@repeat }
            delay(50)
        }
        assertTrue(seen)
        assertTrue(ex.killCommand("kill-me"))
        withTimeout(10_000) { d.await() }
        assertTrue(ex.getActiveCommands().isEmpty())
    }

    @Test
    fun `RunCommand RPC returns RunCommandResponse schema and rejects sudo`() {
        val ws = workspace()
        val h = handler(ScriptedProvider(listOf(finalTurn("x"))), ws)
        val r = rpc(h, "RunCommand", buildJsonObject { put("command", "echo rpc-ok") })
        assertTrue(r["stdout"]!!.jsonPrimitive.content.contains("rpc-ok"))
        assertEquals(0, r["exitCode"]!!.jsonPrimitive.int)
        assertEquals("false", r["timedOut"]!!.jsonPrimitive.content)
        val t = rpcTrailerText(h, "RunCommand", buildJsonObject { put("command", "sudo id") })
        assertTrue(t, t.contains("grpc-status: 7"))
        val active = rpc(h, "GetActiveCommand", buildJsonObject {})
        assertTrue(active["commands"]!!.jsonArray.isEmpty())
        rpc(h, "KillCommand", buildJsonObject { put("commandId", "nope") })
    }

    // ------------------------------------- 13. step serialization / 15. schemas

    @Test
    fun `encodeStepJson emits schema oneof fields for each step type`() {
        val h = handler(ScriptedProvider(listOf(finalTurn("x"))), workspace())
        val now = "2026-01-01T00:00:00Z"
        fun step(type: String, text: String? = null, planner: String? = null, err: String? = null, tool: ToolCall? = null) =
            h.encodeStepJson(
                CascadeStepRecord(
                    stepIndex = 0, type = type, status = "CORTEX_STEP_STATUS_DONE", createdAtIso = now,
                    completedAtIso = now, userText = text, plannerResponseText = planner,
                    toolCall = tool, toolSummary = "s", toolAction = "a", errorMessage = err
                )
            )
        val expected = mapOf(
            "CORTEX_STEP_TYPE_USER_INPUT" to "userInput",
            "CORTEX_STEP_TYPE_PLANNER_RESPONSE" to "plannerResponse",
            "CORTEX_STEP_TYPE_VIEW_FILE" to "viewFile",
            "CORTEX_STEP_TYPE_LIST_DIRECTORY" to "listDirectory",
            "CORTEX_STEP_TYPE_FILE_CHANGE" to "fileChange",
            "CORTEX_STEP_TYPE_GREP_SEARCH" to "grepSearch",
            "CORTEX_STEP_TYPE_FIND" to "find",
            "CORTEX_STEP_TYPE_RUN_COMMAND" to "runCommand",
            "CORTEX_STEP_TYPE_ERROR_MESSAGE" to "errorMessage"
        )
        for ((type, field) in expected) {
            val j = step(type, text = "u", planner = "p", err = "boom", tool = ToolCall("1", "view_file", """{"path":"a"}"""))
            assertEquals(type, j["type"]!!.jsonPrimitive.content)
            assertEquals("CORTEX_STEP_STATUS_DONE", j["status"]!!.jsonPrimitive.content)
            assertTrue("$type should contain $field in $j", j.containsKey(field))
        }
    }

    // ------------------------------------- 5 (E2E). acceptance + 14 streaming

    @Test
    fun `end to end acceptance - inspect project, explain startup, write Markdown summary`() {
        val ws = workspace()
        File(ws, "app/src").mkdirs()
        File(ws, "app/src/Main.kt").writeText("fun main() {\n    println(\"start\")\n}\n")
        val provider = ScriptedProvider(
            listOf(
                toolTurn(call("1", "list_dir", buildJsonObject { put("path", ".") })),
                toolTurn(call("2", "view_file", buildJsonObject { put("path", "app/src/Main.kt") })),
                toolTurn(
                    call("3", "write_to_file", buildJsonObject {
                        put("path", "SUMMARY.md")
                        put("content", "# Summary\n\nThe app starts in `main()` in app/src/Main.kt.\n")
                    })
                ),
                finalTurn("The entry point is main() in app/src/Main.kt; I wrote SUMMARY.md.")
            )
        )
        val h = handler(provider, ws)
        val cascade = "casc-e2e"
        sendMessage(
            h, cascade,
            "Inspect the project files, find the main application entry point, explain how the application starts, and create a short Markdown summary in the workspace"
        )

        val summary = File(ws, "SUMMARY.md")
        assertTrue(summary.exists())
        assertTrue(summary.readText().contains("main()"))

        val types = steps(h, cascade).map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals("CORTEX_STEP_TYPE_USER_INPUT", types.first())
        val ordered = types.filter {
            it in setOf("CORTEX_STEP_TYPE_LIST_DIRECTORY", "CORTEX_STEP_TYPE_VIEW_FILE", "CORTEX_STEP_TYPE_FILE_CHANGE")
        }
        assertEquals(
            listOf("CORTEX_STEP_TYPE_LIST_DIRECTORY", "CORTEX_STEP_TYPE_VIEW_FILE", "CORTEX_STEP_TYPE_FILE_CHANGE"),
            ordered
        )
        assertEquals("CORTEX_STEP_TYPE_PLANNER_RESPONSE", types.last())

        val diff = rpc(h, "GetTurnDiff", buildJsonObject { put("conversationId", cascade) })
        assertTrue(diff["fileDiffs"]!!.jsonObject.isNotEmpty())
        assertTrue(diff["totalAdditions"]!!.jsonPrimitive.int >= 3)
        assertEquals(0, diff["totalDeletions"]!!.jsonPrimitive.int)

        // GetCascadeTrajectorySteps honours stepOffset
        val tail = rpc(h, "GetCascadeTrajectorySteps", buildJsonObject {
            put("cascadeId", cascade); put("stepOffset", types.size - 1)
        })["steps"]!!.jsonArray
        assertEquals(1, tail.size)

        // StreamAgentStateUpdates snapshot reflects completed run
        val st = rpc(h, "StreamAgentStateUpdates", buildJsonObject { put("conversationId", cascade) })
        assertTrue(st.toString().contains(cascade) || st.isNotEmpty())
    }

    @Test
    fun `live state update events are emitted while the agent runs`() {
        val ws = workspace()
        val provider = ScriptedProvider(
            listOf(
                toolTurn(call("1", "list_dir", buildJsonObject { put("path", ".") })),
                finalTurn("fin")
            )
        )
        val h = handler(provider, ws)
        val collected = CopyOnWriteArrayList<String>()
        runBlocking {
            val collector = async(Dispatchers.Default) {
                h.stateUpdateEvents.collect { collected.add(it.first) }
            }
            delay(200)
            sendMessage(h, "casc-live", "list files")
            delay(300)
            collector.cancel()
        }
        assertTrue("expected live updates", collected.isNotEmpty())
        assertTrue(collected.all { it == "casc-live" })
        assertTrue(h.getTrajectoriesSnapshot()["casc-live"]!!.incrementalUpdates.isNotEmpty())
    }

    @Test
    fun `server stream encoder keeps 0x00 data and 0x80 trailer frame headers`() {
        val bytes = GrpcWebFrameCodec.encodeServerStreamResponse(listOf("""{"a":1}""", """{"b":2}"""))
        val frames = GrpcWebFrameCodec.decodeFrames(bytes)
        assertEquals(3, frames.size)
        assertEquals(0x00, frames[0].flags)
        assertEquals(0x00, frames[1].flags)
        assertEquals(0x80, frames[2].flags and 0xFF)
        assertEquals(0x00, bytes[0].toInt())
    }
}
