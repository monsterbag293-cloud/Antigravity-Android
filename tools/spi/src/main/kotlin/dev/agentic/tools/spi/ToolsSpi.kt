package dev.agentic.tools.spi

import dev.agentic.core.model.ToolCall
import kotlinx.serialization.Serializable

@Serializable
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
    val riskLevel: RiskLevel = RiskLevel.LOW
)

data class ToolResult(
    val callId: String,
    val success: Boolean,
    val output: String,
    val artifacts: List<String> = emptyList(),
    val structuredData: Map<String, String> = emptyMap()
)

interface ToolProvider {
    val providerName: String
    fun getAvailableTools(): List<ToolDefinition>
    suspend fun executeTool(call: ToolCall): ToolResult
}
