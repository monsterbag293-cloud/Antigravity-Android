package dev.agentic.core.network

import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import dev.agentic.core.model.ToolCall
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Public Google Gemini REST API model provider implementation (`generativelanguage.googleapis.com`).
 *
 * Operates strictly with user-supplied BYOK credentials via Android Keystore.
 * Supports multi-turn conversation history, tool/function declarations,
 * functionCall response parsing (including thoughtSignature preservation),
 * functionResponse turn construction matching functionCall IDs,
 * thinkingConfig configuration, and bounded retry with backoff for HTTP 429/503.
 */
class GeminiModelProvider internal constructor(
    private val apiKeyProvider: () -> String?,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val allowDemoFallbackWhenKeyMissing: Boolean = false,
    internal val retryPolicy: GeminiRetryPolicy = GeminiRetryPolicy()
) : ModelProvider {
    constructor(
        apiKeyProvider: () -> String?,
        allowDemoFallbackWhenKeyMissing: Boolean = false
    ) : this(
        apiKeyProvider = apiKeyProvider,
        httpClient = defaultHttpClient(),
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        allowDemoFallbackWhenKeyMissing = allowDemoFallbackWhenKeyMissing,
        retryPolicy = GeminiRetryPolicy()
    )

    override val providerId: String = "gemini"
    override val displayName: String = "Google Gemini (Public API)"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun streamCompletion(
        request: ModelRequest,
        tools: List<ModelToolDefinition>,
        onDelta: (String) -> Unit
    ): ModelResponse {
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrBlank()) {
            if (allowDemoFallbackWhenKeyMissing) {
                val guidance = "Notice: No Gemini API Key configured. Please go to Settings to add your key (stored securely in Android Keystore)."
                onDelta(guidance)
                delay(20)
                return ModelResponse(text = guidance)
            }
            throw IllegalStateException("UNAUTHENTICATED: No Gemini API key configured in native secure storage.")
        }

        val effectiveModel = normalizeGeminiModelId(request.modelId)
        val requestBodyJson = buildRequestPayload(request, tools)
        val url = "${baseUrl.trimEnd('/')}/models/$effectiveModel:streamGenerateContent?alt=sse"

        return executeWithRetry(url, requestBodyJson, apiKey, isStreaming = true) { response ->
            val fullText = StringBuilder()
            val fullThinking = StringBuilder()
            val collectedToolCalls = mutableListOf<ToolCall>()
            var finishReason = "STOP"

            val source = response.body?.source()
                ?: throw IOException("Gemini API returned empty response body")

            while (!source.exhausted()) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    val dataPayload = line.removePrefix("data:").trim()
                    if (dataPayload.isNotEmpty() && dataPayload != "[DONE]") {
                        val chunkDelta = extractDeltaFromChunk(dataPayload)
                        if (chunkDelta.thinking.isNotEmpty()) {
                            fullThinking.append(chunkDelta.thinking)
                        }
                        if (chunkDelta.text.isNotEmpty()) {
                            fullText.append(chunkDelta.text)
                            onDelta(chunkDelta.text)
                        }
                        if (chunkDelta.toolCalls.isNotEmpty()) {
                            collectedToolCalls.addAll(chunkDelta.toolCalls)
                        }
                        if (chunkDelta.finishReason != null) {
                            finishReason = chunkDelta.finishReason
                        }
                    }
                }
            }

            ModelResponse(
                text = fullText.toString(),
                toolCalls = collectedToolCalls,
                finishReason = finishReason.lowercase(),
                thinking = fullThinking.toString()
            )
        }
    }

    /**
     * Non-streaming unary execution hitting `:generateContent` directly.
     * Useful for diagnostic checks, single-turn tool calls, and API compatibility probes.
     */
    suspend fun generateCompletionUnary(
        request: ModelRequest,
        tools: List<ModelToolDefinition> = emptyList()
    ): ModelResponse {
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("UNAUTHENTICATED: No Gemini API key configured in native secure storage.")
        }

        val effectiveModel = normalizeGeminiModelId(request.modelId)
        val requestBodyJson = buildRequestPayload(request, tools)
        val url = "${baseUrl.trimEnd('/')}/models/$effectiveModel:generateContent"

        return executeWithRetry(url, requestBodyJson, apiKey, isStreaming = false) { response ->
            val bodyString = response.body?.string().orEmpty()
            if (bodyString.isBlank()) {
                throw IOException("Gemini API returned empty response body")
            }
            val chunkDelta = extractDeltaFromChunk(bodyString)
            ModelResponse(
                text = chunkDelta.text,
                toolCalls = chunkDelta.toolCalls,
                finishReason = (chunkDelta.finishReason ?: "STOP").lowercase(),
                thinking = chunkDelta.thinking
            )
        }
    }

    private suspend fun <T> executeWithRetry(
        url: String,
        requestBodyJson: JsonObject,
        apiKey: String,
        isStreaming: Boolean,
        parseResponse: suspend (okhttp3.Response) -> T
    ): T {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val httpRequest = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", if (isStreaming) "text/event-stream" else "application/json")
                .header("x-goog-api-key", apiKey)
                .post(requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val call = httpClient.newCall(httpRequest)
            val response = try {
                withContext(Dispatchers.IO) {
                    call.execute()
                }
            } catch (ioe: IOException) {
                if (attempt < retryPolicy.maxRetries) {
                    val backoff = retryPolicy.computeBackoffMs(503, null, attempt)
                    attempt++
                    delay(backoff)
                    continue
                }
                throw ioe
            }

            val statusCode = response.code
            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(500) ?: "HTTP $statusCode"
                val retryAfter = response.header("Retry-After")
                response.close()

                if (retryPolicy.isRetryableStatusCode(statusCode) && attempt < retryPolicy.maxRetries) {
                    val backoff = retryPolicy.computeBackoffMs(statusCode, retryAfter, attempt)
                    attempt++
                    delay(backoff)
                    continue
                }
                throw IOException("Gemini API HTTP $statusCode: $errBody")
            }

            return try {
                response.use { parseResponse(it) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                throw e
            }
        }
    }

    internal fun buildRequestPayload(
        request: ModelRequest,
        tools: List<ModelToolDefinition> = emptyList()
    ): JsonObject {
        val effectiveModel = normalizeGeminiModelId(request.modelId)
        return buildJsonObject {
            if (!request.systemInstruction.isNullOrBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", request.systemInstruction) })
                    })
                })
            }
            put("contents", buildJsonArray {
                for (msg in request.messages) {
                    when (msg.role) {
                        Role.USER, Role.SYSTEM -> {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject { put("text", msg.content) })
                                })
                            })
                        }
                        Role.ASSISTANT -> {
                            add(buildJsonObject {
                                put("role", "model")
                                put("parts", buildJsonArray {
                                    if (msg.content.isNotEmpty() || msg.toolCalls.isEmpty()) {
                                        add(buildJsonObject { put("text", msg.content) })
                                    }
                                    for (call in msg.toolCalls) {
                                        val argsElement: JsonElement = try {
                                            json.parseToJsonElement(call.argumentsJson).jsonObject
                                        } catch (_: Exception) {
                                            buildJsonObject {}
                                        }
                                        add(buildJsonObject {
                                            put("functionCall", buildJsonObject {
                                                if (call.id.isNotBlank()) {
                                                    put("id", call.id)
                                                }
                                                put("name", call.name)
                                                put("args", argsElement)
                                            })
                                            if (!call.thoughtSignature.isNullOrBlank()) {
                                                put("thoughtSignature", call.thoughtSignature)
                                            }
                                        })
                                    }
                                })
                            })
                        }
                        Role.TOOL -> {
                            if (msg.toolResponses.isNotEmpty()) {
                                add(buildJsonObject {
                                    put("role", "user")
                                    put("parts", buildJsonArray {
                                        for (tr in msg.toolResponses) {
                                            val responseObj: JsonObject = try {
                                                val parsed = json.parseToJsonElement(tr.responseJson)
                                                if (parsed is JsonObject) {
                                                    parsed
                                                } else {
                                                    buildJsonObject {
                                                        put("result", tr.responseJson)
                                                        put("isError", tr.isError)
                                                    }
                                                }
                                            } catch (_: Exception) {
                                                buildJsonObject {
                                                    put("result", tr.responseJson)
                                                    put("isError", tr.isError)
                                                }
                                            }
                                            add(buildJsonObject {
                                                put("functionResponse", buildJsonObject {
                                                    if (tr.callId.isNotBlank()) {
                                                        put("id", tr.callId)
                                                    }
                                                    put("name", tr.name)
                                                    put("response", responseObj)
                                                })
                                            })
                                        }
                                    })
                                })
                            } else {
                                add(buildJsonObject {
                                    put("role", "user")
                                    put("parts", buildJsonArray {
                                        add(buildJsonObject { put("text", msg.content) })
                                    })
                                })
                            }
                        }
                    }
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            for (tool in tools) {
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    val schemaObj = try {
                                        json.parseToJsonElement(tool.parametersJsonSchema).jsonObject
                                    } catch (_: Exception) {
                                        buildJsonObject {
                                            put("type", "OBJECT")
                                            put("properties", buildJsonObject {})
                                        }
                                    }
                                    put("parameters", schemaObj)
                                })
                            }
                        })
                    })
                })
                put("toolConfig", buildJsonObject {
                    put("functionCallingConfig", buildJsonObject {
                        put("mode", "AUTO")
                    })
                })
            }
            put("generationConfig", buildJsonObject {
                put("temperature", request.temperature)
                val thinking = request.thinkingLevel ?: defaultThinkingLevelForModel(effectiveModel)
                if (!thinking.isNullOrBlank() && !thinking.equals("NONE", ignoreCase = true)) {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", thinking)
                    })
                }
            })
        }
    }

    internal data class ChunkExtract(
        val text: String,
        val finishReason: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val thinking: String = ""
    )

    internal fun extractDeltaFromChunk(jsonText: String): ChunkExtract {
        return try {
            val root = json.parseToJsonElement(jsonText).jsonObject
            val candidates = root["candidates"]?.jsonArray
            val firstCandidate = candidates?.firstOrNull()?.jsonObject
            val finishReason = firstCandidate?.get("finishReason")?.jsonPrimitive?.content
            val parts = firstCandidate
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
            val deltaBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()

            if (parts != null) {
                for (partElement in parts) {
                    val part = partElement.jsonObject
                    val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                    val partText = part["text"]?.jsonPrimitive?.content
                    if (!partText.isNullOrEmpty()) {
                        if (isThought) {
                            thinkingBuilder.append(partText)
                        } else {
                            deltaBuilder.append(partText)
                        }
                    }

                    val fnCallElement = part["functionCall"]
                    if (fnCallElement != null) {
                        val thoughtSig = part["thoughtSignature"]?.jsonPrimitive?.content
                            ?: (fnCallElement as? JsonObject)?.get("thoughtSignature")?.jsonPrimitive?.content
                        val call = parseFunctionCallElement(fnCallElement, thoughtSig)
                        toolCalls.add(call)
                    }
                }
            }
            ChunkExtract(
                text = deltaBuilder.toString(),
                finishReason = finishReason,
                toolCalls = toolCalls,
                thinking = thinkingBuilder.toString()
            )
        } catch (_: Exception) {
            ChunkExtract("", null)
        }
    }

    private fun parseFunctionCallElement(
        fnCallElement: JsonElement,
        thoughtSignature: String?
    ): ToolCall {
        if (fnCallElement !is JsonObject) {
            val callId = "call-${UUID.randomUUID().toString().take(8)}"
            return ToolCall(
                id = callId,
                name = "malformed_function_call",
                argumentsJson = "{}",
                parseError = "functionCall part is not a JSON object",
                thoughtSignature = thoughtSignature
            )
        }
        val modelCallId = fnCallElement["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val callId = modelCallId ?: "call-${UUID.randomUUID().toString().take(8)}"
        val rawName = fnCallElement["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (rawName.isBlank()) {
            return ToolCall(
                id = callId,
                name = "unnamed_function_call",
                argumentsJson = fnCallElement.toString(),
                parseError = "functionCall is missing a valid 'name' field",
                thoughtSignature = thoughtSignature
            )
        }
        val argsElement = fnCallElement["args"]
        return when {
            argsElement == null -> ToolCall(
                id = callId,
                name = rawName,
                argumentsJson = "{}",
                thoughtSignature = thoughtSignature
            )
            argsElement is JsonObject -> ToolCall(
                id = callId,
                name = rawName,
                argumentsJson = argsElement.toString(),
                thoughtSignature = thoughtSignature
            )
            else -> {
                val rawStr = runCatching { argsElement.jsonPrimitive.content }.getOrNull()
                if (rawStr != null) {
                    val parsedObj = runCatching { json.parseToJsonElement(rawStr).jsonObject }.getOrNull()
                    if (parsedObj != null) {
                        ToolCall(
                            id = callId,
                            name = rawName,
                            argumentsJson = parsedObj.toString(),
                            thoughtSignature = thoughtSignature
                        )
                    } else {
                        ToolCall(
                            id = callId,
                            name = rawName,
                            argumentsJson = "{}",
                            parseError = "Malformed JSON string in functionCall.args: $rawStr",
                            thoughtSignature = thoughtSignature
                        )
                    }
                } else {
                    ToolCall(
                        id = callId,
                        name = rawName,
                        argumentsJson = "{}",
                        parseError = "functionCall.args must be a JSON object",
                        thoughtSignature = thoughtSignature
                    )
                }
            }
        }
    }

    internal class GeminiRetryPolicy(
        val maxRetries: Int = DEFAULT_MAX_RETRIES,
        val initialBackoffMs: Long = DEFAULT_INITIAL_BACKOFF_MS,
        val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
        private val jitterSource: () -> Double = { Math.random() }
    ) {
        fun isRetryableStatusCode(code: Int): Boolean =
            code == 429 || code == 503

        fun computeBackoffMs(statusCode: Int, retryAfterHeader: String?, attempt: Int): Long {
            if (!retryAfterHeader.isNullOrBlank()) {
                val seconds = retryAfterHeader.trim().toLongOrNull()
                if (seconds != null && seconds > 0) {
                    return (seconds * 1000L).coerceIn(100L, maxBackoffMs)
                }
            }
            val factor = 1L shl attempt.coerceIn(0, 10)
            val base = (initialBackoffMs * factor).coerceAtMost(maxBackoffMs)
            val jitter = (base * 0.25 * jitterSource()).toLong()
            return (base + jitter).coerceIn(initialBackoffMs, maxBackoffMs)
        }

        companion object {
            const val DEFAULT_MAX_RETRIES = 3
            const val DEFAULT_INITIAL_BACKOFF_MS = 1000L
            const val DEFAULT_MAX_BACKOFF_MS = 20000L
        }
    }

    companion object {
        const val DEFAULT_MODEL_ID = "gemini-3.8-flash"

        fun defaultModelId(): String =
            System.getenv("GEMINI_DEFAULT_MODEL")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("GEMINI_DEFAULT_MODEL")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_MODEL_ID

        fun defaultThinkingLevelForModel(modelId: String): String? {
            val lower = modelId.lowercase()
            return when {
                lower.contains("3.5-flash-lite") || lower.contains("flash-lite") -> "LOW"
                lower.contains("3.8-flash") -> "LOW"
                lower.contains("3.8-pro") -> "MEDIUM"
                else -> null
            }
        }

        fun normalizeGeminiModelId(rawModelId: String?): String {
            val cleaned = rawModelId?.trim().orEmpty()
            if (cleaned.isEmpty()) return defaultModelId()
            return when {
                cleaned.equals("MODEL_PLACEHOLDER_M19", ignoreCase = true) ||
                    cleaned.contains("3_8_PRO", ignoreCase = true) ||
                    cleaned.contains("2_5_PRO", ignoreCase = true) ||
                    cleaned.equals("gemini-3.8-pro", ignoreCase = true) ||
                    cleaned.equals("gemini-2.5-pro", ignoreCase = true) -> "gemini-3.8-pro"
                cleaned.equals("MODEL_PLACEHOLDER_M18", ignoreCase = true) ||
                    cleaned.contains("3_5_FLASH_LITE", ignoreCase = true) ||
                    cleaned.contains("FLASH_LITE", ignoreCase = true) ||
                    cleaned.equals("gemini-3.5-flash-lite", ignoreCase = true) ||
                    cleaned.equals("gemini-2.5-flash-lite", ignoreCase = true) -> "gemini-3.5-flash-lite"
                cleaned.equals("MODEL_PLACEHOLDER_M318", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M319", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M320", ignoreCase = true) ||
                    cleaned.equals("MODEL_GOOGLE_GEMINI_2_5_FLASH", ignoreCase = true) ||
                    cleaned.equals("GOOGLE_GEMINI_2_5_FLASH", ignoreCase = true) ||
                    cleaned.equals("312") ||
                    cleaned.contains("3_8_FLASH", ignoreCase = true) ||
                    cleaned.contains("2_5_FLASH", ignoreCase = true) ||
                    cleaned.startsWith("gemini-3.8-flash", ignoreCase = true) ||
                    cleaned.equals("gemini-2.5-flash", ignoreCase = true) -> "gemini-3.8-flash"
                cleaned.equals("MODEL_PLACEHOLDER_M417", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M418", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M419", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M670", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M400", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M401", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M402", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M403", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M404", ignoreCase = true) ||
                    cleaned.equals("MODEL_PLACEHOLDER_M405", ignoreCase = true) ||
                    cleaned.equals("MODEL_OPENAI_GPT_OSS_120B_MEDIUM", ignoreCase = true) ||
                    cleaned.contains("ARGON", ignoreCase = true) ||
                    cleaned.contains("CARBON", ignoreCase = true) -> "gemini-3.8-flash"
                cleaned.startsWith("gemini-", ignoreCase = true) -> {
                    val lower = cleaned.lowercase()
                    when (lower) {
                        "gemini-2.5-flash" -> "gemini-3.8-flash"
                        "gemini-2.5-pro" -> "gemini-3.8-pro"
                        "gemini-2.5-flash-lite" -> "gemini-3.5-flash-lite"
                        else -> lower
                    }
                }
                else -> defaultModelId()
            }
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
