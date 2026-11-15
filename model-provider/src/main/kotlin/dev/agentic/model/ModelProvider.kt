package dev.agentic.model

import dev.agentic.core.model.Message
import dev.agentic.core.model.ToolCall
import kotlinx.serialization.Serializable

@Serializable
sealed interface ContentBlock {
    @Serializable
    data class Text(val text: String) : ContentBlock
    
    @Serializable
    data class Image(val mimeType: String, val base64Data: String) : ContentBlock
    
    @Serializable
    data class ActionCall(val call: ToolCall) : ContentBlock
    
    @Serializable
    data class ActionResult(val callId: String, val resultJson: String) : ContentBlock
}

data class ModelRequest(
    val messages: List<Message>,
    val systemInstruction: String? = null,
    val modelId: String,
    val temperature: Float = 0.2f,
    val thinkingLevel: String? = null
)

data class ModelResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String = "stop",
    val thinking: String = ""
)

/**
 * Pluggable Model Provider SPI (ADR-02).
 * The agent runtime has zero dependency on any vendor SDK.
 */
interface ModelProvider {
    val providerId: String
    val displayName: String

    suspend fun streamCompletion(
        request: ModelRequest,
        tools: List<ModelToolDefinition> = emptyList(),
        onDelta: (String) -> Unit
    ): ModelResponse
}

data class ModelToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String
)
