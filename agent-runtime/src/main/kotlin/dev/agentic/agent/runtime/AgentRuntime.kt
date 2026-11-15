package dev.agentic.agent.runtime

import dev.agentic.core.model.AgentRun
import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import dev.agentic.core.model.RunStatus
import dev.agentic.core.model.ToolCall
import dev.agentic.core.model.ToolCallResponse
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelToolDefinition
import dev.agentic.tools.spi.RiskLevel
import dev.agentic.tools.spi.ToolProvider
import dev.agentic.tools.spi.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

sealed interface AgentExecutionEvent {
    data class PlannerStarted(val iteration: Int) : AgentExecutionEvent
    data class PlannerDelta(val iteration: Int, val textDelta: String) : AgentExecutionEvent
    data class PlannerCompleted(
        val iteration: Int,
        val responseText: String,
        val thinkingText: String,
        val toolCalls: List<ToolCall>
    ) : AgentExecutionEvent

    data class ToolStarted(val iteration: Int, val toolCall: ToolCall) : AgentExecutionEvent
    data class ToolCompleted(
        val iteration: Int,
        val toolCall: ToolCall,
        val toolResult: ToolResult
    ) : AgentExecutionEvent

    data class MaxIterationsReached(
        val maxIterations: Int,
        val pendingToolCalls: List<ToolCall>
    ) : AgentExecutionEvent

    data class Completed(val finalResponseText: String, val iterationsUsed: Int) : AgentExecutionEvent
    data class Cancelled(val reason: String) : AgentExecutionEvent
    data class Error(val errorMessage: String, val iteration: Int) : AgentExecutionEvent
}

data class ExecutedToolStep(
    val iteration: Int,
    val toolCall: ToolCall,
    val toolResult: ToolResult
)

data class AgentExecutionOutcome(
    val run: AgentRun,
    val finalResponseText: String,
    val updatedHistory: List<Message>,
    val executedToolSteps: List<ExecutedToolStep>,
    val iterationsUsed: Int,
    val reachedMaxIterations: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Genuine Multi-Step Agent Execution Engine (P1.2 Phase 2).
 *
 * Orchestrates multi-turn model reasoning, tool validation, sandboxed tool execution,
 * functionResponse feedback, maximum-iteration enforcement, timeout handling, and cancellation.
 */
class AgentRuntime(
    private val modelProvider: ModelProvider,
    private val initialTools: List<ToolProvider> = emptyList(),
    private val defaultMaxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val defaultModelCallTimeoutMs: Long = DEFAULT_MODEL_CALL_TIMEOUT_MS
) {
    private val _toolProviders = mutableListOf<ToolProvider>().apply { addAll(initialTools) }
    private val _currentRun = MutableStateFlow<AgentRun?>(null)
    val currentRun: StateFlow<AgentRun?> = _currentRun.asStateFlow()

    fun registerToolProvider(provider: ToolProvider) {
        if (_toolProviders.none { it.providerName == provider.providerName }) {
            _toolProviders.add(provider)
        }
    }

    fun unregisterToolProvider(providerName: String) {
        _toolProviders.removeAll { it.providerName == providerName }
    }

    fun getRegisteredToolDefinitions(): List<ModelToolDefinition> =
        _toolProviders.flatMap { it.getAvailableTools() }.map {
            ModelToolDefinition(it.name, it.description, it.parametersJsonSchema)
        }

    suspend fun executeTask(
        conversationId: String,
        taskPrompt: String,
        history: List<Message>,
        onTokenDelta: (String) -> Unit
    ): AgentRun {
        val outcome = executeAgentLoop(
            conversationId = conversationId,
            taskPrompt = taskPrompt,
            history = history,
            onTokenDelta = onTokenDelta
        )
        return outcome.run
    }

    suspend fun executeAgentLoop(
        conversationId: String,
        taskPrompt: String,
        history: List<Message>,
        modelId: String = "gemini-3.8-flash",
        systemInstruction: String = DEFAULT_AGENT_SYSTEM_INSTRUCTION,
        maxIterations: Int = defaultMaxIterations,
        modelCallTimeoutMs: Long = defaultModelCallTimeoutMs,
        onTokenDelta: (String) -> Unit = {},
        onExecutionEvent: suspend (AgentExecutionEvent) -> Unit = {}
    ): AgentExecutionOutcome {
        val effectiveMaxIterations = maxIterations.coerceIn(1, 25)
        val runId = UUID.randomUUID().toString()
        var runState = AgentRun(
            id = runId,
            conversationId = conversationId,
            title = taskPrompt.take(60),
            status = RunStatus.RUNNING,
            currentStep = "Starting agent execution...",
            totalSteps = 0
        )
        _currentRun.value = runState

        val availableTools = _toolProviders.flatMap { it.getAvailableTools() }
        val modelTools = availableTools.map {
            ModelToolDefinition(it.name, it.description, it.parametersJsonSchema)
        }

        val workingMessages = history.toMutableList()
        workingMessages.add(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = Role.USER,
                content = taskPrompt
            )
        )

        val executedToolSteps = mutableListOf<ExecutedToolStep>()
        var lastAssistantText = ""
        var iterationsUsed = 0

        try {
            for (iteration in 1..effectiveMaxIterations) {
                currentCoroutineContext().ensureActive()
                iterationsUsed = iteration

                runState = runState.copy(
                    currentStep = "Reasoning (iteration $iteration of $effectiveMaxIterations)...",
                    totalSteps = runState.totalSteps + 1
                )
                _currentRun.value = runState
                onExecutionEvent(AgentExecutionEvent.PlannerStarted(iteration))

                val response = try {
                    if (modelCallTimeoutMs > 0L) {
                        withTimeout(modelCallTimeoutMs) {
                            modelProvider.streamCompletion(
                                request = ModelRequest(
                                    messages = workingMessages.toList(),
                                    systemInstruction = systemInstruction,
                                    modelId = modelId
                                ),
                                tools = modelTools,
                                onDelta = { delta ->
                                    onTokenDelta(delta)
                                }
                            )
                        }
                    } else {
                        modelProvider.streamCompletion(
                            request = ModelRequest(
                                messages = workingMessages.toList(),
                                systemInstruction = systemInstruction,
                                modelId = modelId
                            ),
                            tools = modelTools,
                            onDelta = { delta ->
                                onTokenDelta(delta)
                            }
                        )
                    }
                } catch (tce: TimeoutCancellationException) {
                    val timeoutMsg = "Model request timed out after ${modelCallTimeoutMs}ms on iteration $iteration."
                    onExecutionEvent(AgentExecutionEvent.Error(timeoutMsg, iteration))
                    runState = runState.copy(
                        status = RunStatus.FAILED,
                        currentStep = timeoutMsg
                    )
                    _currentRun.value = runState
                    return AgentExecutionOutcome(
                        run = runState,
                        finalResponseText = lastAssistantText,
                        updatedHistory = workingMessages,
                        executedToolSteps = executedToolSteps,
                        iterationsUsed = iterationsUsed,
                        errorMessage = timeoutMsg
                    )
                }

                if (response.text.isNotBlank()) {
                    lastAssistantText = response.text
                }

                onExecutionEvent(
                    AgentExecutionEvent.PlannerCompleted(
                        iteration = iteration,
                        responseText = response.text,
                        thinkingText = response.thinking,
                        toolCalls = response.toolCalls
                    )
                )

                workingMessages.add(
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = Role.ASSISTANT,
                        content = response.text,
                        toolCalls = response.toolCalls,
                        thinking = response.thinking.takeIf { it.isNotBlank() }
                    )
                )

                // If the model returned no tool calls, the task is complete!
                if (response.toolCalls.isEmpty()) {
                    runState = runState.copy(
                        status = RunStatus.COMPLETED,
                        currentStep = "Task completed"
                    )
                    _currentRun.value = runState
                    onExecutionEvent(AgentExecutionEvent.Completed(lastAssistantText, iterationsUsed))
                    return AgentExecutionOutcome(
                        run = runState,
                        finalResponseText = lastAssistantText,
                        updatedHistory = workingMessages,
                        executedToolSteps = executedToolSteps,
                        iterationsUsed = iterationsUsed,
                        reachedMaxIterations = false
                    )
                }

                // If the model requested tool calls on the final allowed iteration, stop to prevent infinite loops.
                if (iteration >= effectiveMaxIterations) {
                    val limitNotice = if (lastAssistantText.isBlank()) {
                        "Reached maximum agent execution iterations ($effectiveMaxIterations) after ${executedToolSteps.size} tool call(s)."
                    } else {
                        "$lastAssistantText\n\n[Reached maximum agent execution iterations ($effectiveMaxIterations).]"
                    }
                    onExecutionEvent(AgentExecutionEvent.MaxIterationsReached(effectiveMaxIterations, response.toolCalls))
                    runState = runState.copy(
                        status = RunStatus.COMPLETED,
                        currentStep = "Reached maximum iterations ($effectiveMaxIterations)"
                    )
                    _currentRun.value = runState
                    return AgentExecutionOutcome(
                        run = runState,
                        finalResponseText = limitNotice,
                        updatedHistory = workingMessages,
                        executedToolSteps = executedToolSteps,
                        iterationsUsed = iterationsUsed,
                        reachedMaxIterations = true
                    )
                }

                // Execute all requested tool calls in order and collect their responses
                val toolResponsesForTurn = mutableListOf<ToolCallResponse>()
                for (call in response.toolCalls) {
                    currentCoroutineContext().ensureActive()

                    val toolDef = availableTools.find { it.name == call.name }
                    if (toolDef?.riskLevel == RiskLevel.HIGH) {
                        runState = runState.copy(
                            status = RunStatus.AWAITING_APPROVAL,
                            currentStep = "Waiting for user confirmation to execute high-risk action: ${call.name}",
                            pendingApprovalMessage = "Approve execution of tool ${call.name} with parameters: ${call.argumentsJson}"
                        )
                        _currentRun.value = runState
                        return AgentExecutionOutcome(
                            run = runState,
                            finalResponseText = lastAssistantText,
                            updatedHistory = workingMessages,
                            executedToolSteps = executedToolSteps,
                            iterationsUsed = iterationsUsed
                        )
                    }

                    runState = runState.copy(
                        currentStep = "Executing tool ${call.name}...",
                        totalSteps = runState.totalSteps + 1
                    )
                    _currentRun.value = runState
                    onExecutionEvent(AgentExecutionEvent.ToolStarted(iteration, call))

                    val result: ToolResult = if (!call.parseError.isNullOrBlank()) {
                        ToolResult(
                            callId = call.id,
                            success = false,
                            output = "Error: Malformed tool call arguments for '${call.name}': ${call.parseError}"
                        )
                    } else {
                        val provider = _toolProviders.find { p ->
                            p.getAvailableTools().any { it.name == call.name } ||
                                // Support legacy aliases (read_file, write_file, list_files)
                                (p.providerName == "LocalFileTools" && call.name in LEGACY_LOCAL_TOOL_ALIASES)
                        }
                        if (provider == null) {
                            ToolResult(
                                callId = call.id,
                                success = false,
                                output = "Error: Unknown or unsupported tool '${call.name}'. Available tools: ${availableTools.joinToString { it.name }}"
                            )
                        } else {
                            provider.executeTool(call)
                        }
                    }

                    executedToolSteps.add(ExecutedToolStep(iteration, call, result))
                    onExecutionEvent(AgentExecutionEvent.ToolCompleted(iteration, call, result))

                    val responseJson = buildJsonObject {
                        put("success", result.success)
                        put("output", result.output)
                        if (!result.success) {
                            put("error", result.output)
                        }
                        for ((k, v) in result.structuredData) {
                            if (k != "content" && k != "originalContents" && k != "modifiedContents") {
                                put(k, v)
                            }
                        }
                    }.toString()

                    toolResponsesForTurn.add(
                        ToolCallResponse(
                            callId = call.id,
                            name = call.name,
                            responseJson = responseJson,
                            isError = !result.success
                        )
                    )
                }

                // Append functionResponse message to conversation history for the next model turn
                workingMessages.add(
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = Role.TOOL,
                        content = toolResponsesForTurn.joinToString("\n") { "${it.name}: ${it.responseJson}" },
                        toolResponses = toolResponsesForTurn
                    )
                )
            }

            runState = runState.copy(
                status = RunStatus.COMPLETED,
                currentStep = "Task completed"
            )
            _currentRun.value = runState
            return AgentExecutionOutcome(
                run = runState,
                finalResponseText = lastAssistantText,
                updatedHistory = workingMessages,
                executedToolSteps = executedToolSteps,
                iterationsUsed = iterationsUsed
            )
        } catch (ce: CancellationException) {
            runState = runState.copy(
                status = RunStatus.CANCELLED,
                currentStep = "Execution cancelled"
            )
            _currentRun.value = runState
            onExecutionEvent(AgentExecutionEvent.Cancelled(ce.message ?: "Cancelled"))
            throw ce
        } catch (e: Exception) {
            val errMsg = e.message ?: "Unexpected agent runtime error"
            runState = runState.copy(
                status = RunStatus.FAILED,
                currentStep = "Failed: $errMsg"
            )
            _currentRun.value = runState
            onExecutionEvent(AgentExecutionEvent.Error(errMsg, iterationsUsed))
            return AgentExecutionOutcome(
                run = runState,
                finalResponseText = lastAssistantText,
                updatedHistory = workingMessages,
                executedToolSteps = executedToolSteps,
                iterationsUsed = iterationsUsed,
                errorMessage = errMsg
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 10
        const val DEFAULT_MODEL_CALL_TIMEOUT_MS = 120_000L

        private val LEGACY_LOCAL_TOOL_ALIASES = setOf("read_file", "write_file", "list_files")

        const val DEFAULT_AGENT_SYSTEM_INSTRUCTION = """You are Antigravity, an autonomous AI coding agent running on Android inside an authorised workspace directory.
When the user asks you to inspect, search, create, or modify files, or run workspace commands:
1. Use the provided workspace tools (`list_dir`, `view_file`, `write_to_file`, `edit_file`, `grep_search`, `find_by_name`, `run_command`) to gather real evidence and perform actual file changes inside the workspace.
2. Never pretend you have read or created a file without calling the appropriate tool first.
3. Keep all file paths relative to the workspace root (e.g., `.` or `README.md` or `src/Main.kt`).
4. After completing all necessary tool calls, provide a clear, accurate summary of what you inspected and changed."""
    }
}
