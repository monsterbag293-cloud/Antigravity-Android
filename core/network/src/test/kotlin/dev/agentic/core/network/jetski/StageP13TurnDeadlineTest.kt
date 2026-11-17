package dev.agentic.core.network.jetski

import dev.agentic.core.model.ToolCall
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * CATEGORY: SCRIPTED integration test (fake model, JVM host). Not evidence of real Gemini behaviour.
 * Regression for a P1.3 finding: a multi-step agent turn longer than the ordinary RPC deadline must not be
 * aborted with DEADLINE_EXCEEDED.
 */
class StageP13TurnDeadlineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class SlowModel : ModelProvider {
        override val providerId = "slow"
        override val displayName = "slow"
        private val n = AtomicInteger()
        override suspend fun streamCompletion(
            request: ModelRequest, tools: List<ModelToolDefinition>, onDelta: (String) -> Unit
        ): ModelResponse {
            delay(300) // each call is well under the per-call limit
            return if (n.incrementAndGet() <= 3) {
                ModelResponse("", listOf(ToolCall("c${n.get()}", "list_dir", """{"path":"."}""")), "tool_calls")
            } else ModelResponse("all done")
        }
    }

    @Test
    fun `agent turn longer than the unary RPC deadline completes`() = runBlocking {
        val h = LanguageServerGrpcWebHandler(
            modelProvider = SlowModel(), isAuthenticatedProvider = { true }, csrfToken = "t", enforceCsrf = false,
            defaultTimeoutMs = 700, // ~4 calls * 300ms = 1.2s total > 700ms request deadline
            workspaceRootDir = tmp.newFolder("w").canonicalFile
        )
        val body = buildJsonObject {
            put("cascadeId", "slow")
            put("items", buildJsonArray { add(buildJsonObject { put("text", "go") }) })
        }
        val res = h.handleRequest(
            GrpcWebHttpRequest(
                LanguageServerGrpcWebHandler.SERVICE_PREFIX + "SendUserCascadeMessage",
                body = GrpcWebFrameCodec.encodeDataFrame(body.toString())
            )
        )
        val trailer = GrpcWebFrameCodec.decodeFrames(res.body).filter { it.isTrailer }.joinToString { it.utf8Text }
        assertTrue("turn was aborted: $trailer", !trailer.contains("grpc-status: 4"))
        val rec = h.getTrajectoriesSnapshot()["slow"]!!
        assertTrue(rec.steps.any { it.plannerResponseText == "all done" })
    }
}
