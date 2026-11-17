package dev.agentic.core.network

import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import dev.agentic.core.model.ToolCall
import dev.agentic.core.model.ToolCallResponse
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * OFFLINE controlled fixture unit tests for Gemini Function Calling (P1.3 Section 3).
 *
 * Verifies:
 * - Valid single function call parsing
 * - Multiple function calls in a single turn
 * - Function call ID preservation
 * - Function response turn serialization (including matching ID)
 * - Ordinary text responses without function calls
 * - Malformed function call recovery
 * - Streaming SSE multi-chunk extraction (thinking + text + functionCall)
 */
class GeminiFunctionCallingFixtureTest {

    private val provider = GeminiModelProvider(apiKeyProvider = { "fake-test-key" })

    @Test
    fun `valid single function call response parsed correctly`() {
        val jsonFixture = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "id": "call-func-001",
                      "name": "view_file",
                      "args": {
                        "path": "src/Main.kt",
                        "start_line": 1,
                        "end_line": 20
                      }
                    }
                  }
                ]
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        val extract = provider.extractDeltaFromChunk(jsonFixture)
        assertEquals("", extract.text)
        assertEquals("STOP", extract.finishReason)
        assertEquals(1, extract.toolCalls.size)

        val call = extract.toolCalls.first()
        assertEquals("call-func-001", call.id)
        assertEquals("view_file", call.name)
        assertTrue("Arguments must contain path", call.argumentsJson.contains("\"path\":\"src/Main.kt\""))
        assertTrue("Arguments must contain start_line", call.argumentsJson.contains("\"start_line\":1"))
        assertEquals(null, call.parseError)
    }

    @Test
    fun `multiple function calls in single candidate turn parsed correctly`() {
        val jsonFixture = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "id": "call-1",
                      "name": "list_dir",
                      "args": { "path": "." }
                    }
                  },
                  {
                    "functionCall": {
                      "id": "call-2",
                      "name": "view_file",
                      "args": { "path": "README.md" }
                    }
                  }
                ]
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        val extract = provider.extractDeltaFromChunk(jsonFixture)
        assertEquals(2, extract.toolCalls.size)
        assertEquals("call-1", extract.toolCalls[0].id)
        assertEquals("list_dir", extract.toolCalls[0].name)
        assertEquals("call-2", extract.toolCalls[1].id)
        assertEquals("view_file", extract.toolCalls[1].name)
    }

    @Test
    fun `function-call ID preservation`() {
        // Case 1: ID provided by Gemini
        val withId = """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [{ "functionCall": { "id": "gemini-call-999", "name": "list_dir", "args": {} } }]
            }
          }]
        }
        """.trimIndent()
        val extract1 = provider.extractDeltaFromChunk(withId)
        assertEquals("gemini-call-999", extract1.toolCalls.first().id)

        // Case 2: ID missing in response -> Fallback UUID generated with prefix "call-"
        val withoutId = """
        {
          "candidates": [{
            "content": {
              "role": "model",
              "parts": [{ "functionCall": { "name": "list_dir", "args": {} } }]
            }
          }]
        }
        """.trimIndent()
        val extract2 = provider.extractDeltaFromChunk(withoutId)
        val generatedId = extract2.toolCalls.first().id
        assertTrue("Generated ID must start with call-", generatedId.startsWith("call-"))
        assertTrue("Generated ID must not be blank", generatedId.isNotBlank())
    }

    @Test
    fun `function-response serialization preserves call ID and JSON object response`() {
        val callResponse = ToolCallResponse(
            callId = "call-corr-777",
            name = "write_to_file",
            responseJson = """{"success":true,"output":"Wrote 42 bytes to agent-validation-summary.md"}""",
            isError = false
        )
        val req = ModelRequest(
            messages = listOf(
                Message("1", "c", Role.USER, "Write summary"),
                Message("2", "c", Role.ASSISTANT, "", toolCalls = listOf(ToolCall("call-corr-777", "write_to_file", "{}"))),
                Message("3", "c", Role.TOOL, "", toolResponses = listOf(callResponse))
            ),
            modelId = "gemini-3.5-flash-lite"
        )

        val payload = provider.buildRequestPayload(req, emptyList())
        val contents = payload["contents"]!!.jsonArray
        assertEquals(3, contents.size)

        val toolTurn = contents[2].jsonObject
        assertEquals("user", toolTurn["role"]!!.jsonPrimitive.content)

        val parts = toolTurn["parts"]!!.jsonArray
        assertEquals(1, parts.size)

        val fnResp = parts[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("call-corr-777", fnResp["id"]!!.jsonPrimitive.content)
        assertEquals("write_to_file", fnResp["name"]!!.jsonPrimitive.content)

        val respObj = fnResp["response"]!!.jsonObject
        assertEquals(true, respObj["success"]!!.jsonPrimitive.boolean)
        assertTrue(respObj["output"]!!.jsonPrimitive.content.contains("Wrote 42 bytes"))
    }

    @Test
    fun `response containing ordinary text rather than a function call`() {
        val fixture = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  { "text": "Here is the direct analysis of the workspace without needing extra tool calls." }
                ]
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        val extract = provider.extractDeltaFromChunk(fixture)
        assertEquals("Here is the direct analysis of the workspace without needing extra tool calls.", extract.text)
        assertTrue("toolCalls must be empty for normal text responses", extract.toolCalls.isEmpty())
        assertEquals("STOP", extract.finishReason)
    }

    @Test
    fun `malformed response handled gracefully`() {
        // Case A: Missing name
        val noName = """{"candidates":[{"content":{"parts":[{"functionCall":{"args":{}}}]}}]}"""
        val extA = provider.extractDeltaFromChunk(noName)
        assertEquals(1, extA.toolCalls.size)
        assertEquals("unnamed_function_call", extA.toolCalls.first().name)
        assertNotNull(extA.toolCalls.first().parseError)

        // Case B: Non-object functionCall
        val nonObj = """{"candidates":[{"content":{"parts":[{"functionCall":"not an object"}]}}]}"""
        val extB = provider.extractDeltaFromChunk(nonObj)
        assertEquals(1, extB.toolCalls.size)
        assertEquals("malformed_function_call", extB.toolCalls.first().name)

        // Case C: Malformed JSON string inside args
        val badArgsStr = """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"cmd","args":"{ broken json"}}]}}]}"""
        val extC = provider.extractDeltaFromChunk(badArgsStr)
        assertEquals("cmd", extC.toolCalls.first().name)
        assertNotNull(extC.toolCalls.first().parseError)

        // Case D: Completely corrupt chunk text
        val corrupt = """not valid json at all <> {}"""
        val extD = provider.extractDeltaFromChunk(corrupt)
        assertEquals("", extD.text)
        assertTrue(extD.toolCalls.isEmpty())
    }

    @Test
    fun `streaming SSE function-call extraction`() = runBlocking {
        val sseStreamBody = """
        data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"Thinking: need to view file..."}]}}]}

        data: {"candidates":[{"content":{"parts":[{"text":"I will check the entry point.\n"}]}}]}

        data: {"candidates":[{"content":{"parts":[{"functionCall":{"id":"stream-call-1","name":"view_file","args":{"path":"Main.kt"}}}]},"finishReason":"STOP"}]}

        data: [DONE]
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sseStreamBody.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()

        val mockProvider = GeminiModelProvider(
            apiKeyProvider = { "test-key" },
            httpClient = mockClient
        )

        val deltas = mutableListOf<String>()
        val response = mockProvider.streamCompletion(
            request = ModelRequest(messages = listOf(Message("1", "c", Role.USER, "inspect")), modelId = "gemini-3.5-flash-lite"),
            tools = listOf(ModelToolDefinition("view_file", "View file", "{}")),
            onDelta = { deltas.add(it) }
        )

        assertEquals("Thinking: need to view file...", response.thinking)
        assertEquals("I will check the entry point.\n", response.text)
        assertEquals(listOf("I will check the entry point.\n"), deltas)
        assertEquals(1, response.toolCalls.size)
        assertEquals("stream-call-1", response.toolCalls[0].id)
        assertEquals("view_file", response.toolCalls[0].name)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `non-streaming generateCompletionUnary extracts function call correctly`() = runBlocking {
        val unaryJsonBody = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "id": "unary-call-1",
                      "name": "list_dir",
                      "args": { "path": "." }
                    }
                  }
                ]
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(unaryJsonBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val mockProvider = GeminiModelProvider(
            apiKeyProvider = { "test-key" },
            httpClient = mockClient
        )

        val response = mockProvider.generateCompletionUnary(
            request = ModelRequest(messages = listOf(Message("1", "c", Role.USER, "list files")), modelId = "gemini-3.5-flash-lite"),
            tools = listOf(ModelToolDefinition("list_dir", "List dir", "{}"))
        )

        assertEquals(1, response.toolCalls.size)
        assertEquals("unary-call-1", response.toolCalls[0].id)
        assertEquals("list_dir", response.toolCalls[0].name)
        assertEquals("stop", response.finishReason)
    }
}
