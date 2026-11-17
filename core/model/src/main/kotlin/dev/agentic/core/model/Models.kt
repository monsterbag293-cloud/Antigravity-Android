package dev.agentic.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceType {
    DEVICE_SAF,
    DEVICE_APP_STORAGE,
    REMOTE_HOST
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val description: String = "",
    val rootPath: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class Workspace(
    val id: String,
    val projectId: String,
    val type: WorkspaceType,
    val path: String,
    val activeBranch: String = "main"
)

@Serializable
enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

@Serializable
data class ToolCallResponse(
    val callId: String,
    val name: String,
    val responseJson: String,
    val isError: Boolean = false
)

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResponses: List<ToolCallResponse> = emptyList(),
    val thinking: String? = null
)

@Serializable
data class Conversation(
    val id: String,
    val projectId: String,
    val title: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val stepCount: Int = 0,
    val status: String = "CASCADE_RUN_STATUS_IDLE",
    val workspaceUri: String? = null
)

@Serializable
enum class RunStatus {
    IDLE,
    RUNNING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class AgentRun(
    val id: String,
    val conversationId: String,
    val title: String,
    val status: RunStatus = RunStatus.IDLE,
    val currentStep: String? = null,
    val totalSteps: Int = 0,
    val pendingApprovalMessage: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val parseError: String? = null,
    val thoughtSignature: String? = null
)

@Serializable
data class DiffHunk(
    val filePath: String,
    val oldStartLine: Int,
    val oldLineCount: Int,
    val newStartLine: Int,
    val newLineCount: Int,
    val content: String,
    val isAccepted: Boolean? = null
)
