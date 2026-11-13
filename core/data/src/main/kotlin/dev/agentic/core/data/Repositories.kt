package dev.agentic.core.data

import dev.agentic.core.model.Conversation
import dev.agentic.core.model.Message
import dev.agentic.core.model.Project
import dev.agentic.core.model.Workspace
import dev.agentic.core.model.WorkspaceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface ProjectRepository {
    fun getProjects(): Flow<List<Project>>
    suspend fun createProject(name: String, rootPath: String): Project
    fun getWorkspaces(projectId: String): Flow<List<Workspace>>
    suspend fun upsertProject(project: Project, workspace: Workspace? = null)
    suspend fun setProjects(projects: List<Project>, workspaces: List<Workspace>)
}

class InMemoryProjectRepository : ProjectRepository {
    companion object {
        val DEFAULT_PROJECTS = listOf(
            Project(
                id = "f62d4d56-8673-4da6-a2c0-458e5e2eb5bd",
                name = "Antigravity Port",
                description = "Android port workspace",
                rootPath = "c:/Users/NEW0033/Downloads/Antigravity Port"
            ),
            Project(
                id = "5937e51d-c88e-4862-8f07-3ce0e2625228",
                name = "ChaosAI",
                description = "Python chaos mathematical research",
                rootPath = "c:/Users/NEW0033/OneDrive - St Helena Secondary College/da big 2026/Personal [ONLY ME!!!!]/ChaosAI"
            ),
            Project(
                id = "outside-of-project",
                name = "Outside of Project",
                description = "Standalone and unlinked tasks",
                rootPath = "/storage/emulated/0/AgenticProjects"
            ),
            Project(
                id = "proj-default",
                name = "Local Sample Project",
                description = "Standalone device workspace demo",
                rootPath = "/storage/emulated/0/AgenticProjects/Sample"
            )
        )

        val DEFAULT_WORKSPACES = listOf(
            Workspace(
                id = "ws-antigravity",
                projectId = "f62d4d56-8673-4da6-a2c0-458e5e2eb5bd",
                type = WorkspaceType.REMOTE_HOST,
                path = "file:///c:/Users/NEW0033/Downloads/Antigravity%20Port"
            ),
            Workspace(
                id = "ws-chaosai",
                projectId = "5937e51d-c88e-4862-8f07-3ce0e2625228",
                type = WorkspaceType.REMOTE_HOST,
                path = "file:///c:/Users/NEW0033/OneDrive%20-%20St%20Helena%20Secondary%20College/da%20big%202026/Personal%20%5BONLY%20ME%21%21%21%21%5D/ChaosAI"
            ),
            Workspace(
                id = "ws-default",
                projectId = "proj-default",
                type = WorkspaceType.DEVICE_APP_STORAGE,
                path = "/storage/emulated/0/AgenticProjects/Sample"
            )
        )
    }

    private val _projects = MutableStateFlow(DEFAULT_PROJECTS)
    private val _workspaces = MutableStateFlow(DEFAULT_WORKSPACES)

    override fun getProjects(): Flow<List<Project>> = _projects.asStateFlow()

    override suspend fun createProject(name: String, rootPath: String): Project {
        val newProj = Project(
            id = "proj-${UUID.randomUUID()}",
            name = name,
            rootPath = rootPath
        )
        val newWs = Workspace(
            id = "ws-${UUID.randomUUID()}",
            projectId = newProj.id,
            type = WorkspaceType.DEVICE_SAF,
            path = rootPath
        )
        _projects.value = _projects.value + newProj
        _workspaces.value = _workspaces.value + newWs
        return newProj
    }

    override fun getWorkspaces(projectId: String): Flow<List<Workspace>> {
        return _workspaces.map { list -> list.filter { it.projectId == projectId } }
    }

    override suspend fun upsertProject(project: Project, workspace: Workspace?) {
        val updatedProjects = _projects.value.filter { it.id != project.id } + project
        _projects.value = updatedProjects
        if (workspace != null) {
            val updatedWorkspaces = _workspaces.value.filter { it.id != workspace.id } + workspace
            _workspaces.value = updatedWorkspaces
        }
    }

    override suspend fun setProjects(projects: List<Project>, workspaces: List<Workspace>) {
        _projects.value = projects
        _workspaces.value = workspaces
    }
}

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    fun getConversations(projectId: String): Flow<List<Conversation>>
    fun getMessages(conversationId: String): Flow<List<Message>>
    suspend fun addMessage(conversationId: String, message: Message)
    suspend fun upsertConversation(conversation: Conversation)
    suspend fun setConversations(conversations: List<Conversation>)
    suspend fun setMessages(conversationId: String, messages: List<Message>)
}

class InMemoryConversationRepository : ConversationRepository {
    companion object {
        val DEFAULT_CONVERSATIONS = listOf(
            Conversation(
                id = "c367db09-5d84-4822-8946-5d626af85692",
                projectId = "f62d4d56-8673-4da6-a2c0-458e5e2eb5bd",
                title = "REA Availability and Capabilities",
                stepCount = 8031,
                status = "CASCADE_RUN_STATUS_RUNNING",
                workspaceUri = "file:///c:/Users/NEW0033/Downloads/Antigravity%20Port"
            ),
            Conversation(
                id = "af4bd875-5f28-4b11-b783-d4954beed236",
                projectId = "5937e51d-c88e-4862-8f07-3ce0e2625228",
                title = "ChaosAI Project Architecture Design",
                stepCount = 72,
                status = "CASCADE_RUN_STATUS_IDLE",
                workspaceUri = "file:///c:/Users/NEW0033/OneDrive%20-%20St%20Helena%20Secondary%20College/da%20big%202026/Personal%20%5BONLY%20ME%21%21%21%21%5D/ChaosAI"
            ),
            Conversation(
                id = "b213d7c0-9bb6-4229-99be-7b7855c5c195",
                projectId = "5937e51d-c88e-4862-8f07-3ce0e2625228",
                title = "REA Availability and Reverse Engineering Capabilities",
                stepCount = 1,
                status = "CASCADE_RUN_STATUS_IDLE",
                workspaceUri = "file:///c:/Users/NEW0033/OneDrive%20-%20St%20Helena%20Secondary%20College/da%20big%202026/Personal%20%5BONLY%20ME%21%21%21%21%5D/ChaosAI"
            ),
            Conversation(
                id = "conv-welcome",
                projectId = "proj-default",
                title = "Welcome to Agentic Assistant",
                stepCount = 1,
                status = "CASCADE_RUN_STATUS_IDLE"
            )
        )

        val DEFAULT_MESSAGES = mapOf(
            "conv-welcome" to listOf(
                Message(
                    id = "msg-welcome-1",
                    conversationId = "conv-welcome",
                    role = dev.agentic.core.model.Role.ASSISTANT,
                    content = "Welcome to your independent on-device AI coding assistant! I can inspect local files, propose diffs, and perform git operations standalone without any server."
                )
            ),
            "af4bd875-5f28-4b11-b783-d4954beed236" to listOf(
                Message(
                    id = "msg-chaos-1",
                    conversationId = "af4bd875-5f28-4b11-b783-d4954beed236",
                    role = dev.agentic.core.model.Role.USER,
                    content = "We're starting ChaosAI, a Python-based mathematical chaos research project."
                ),
                Message(
                    id = "msg-chaos-2",
                    conversationId = "af4bd875-5f28-4b11-b783-d4954beed236",
                    role = dev.agentic.core.model.Role.ASSISTANT,
                    content = "ChaosAI project architecture initialized with Python chaos dynamic analysis workflows."
                )
            ),
            "b213d7c0-9bb6-4229-99be-7b7855c5c195" to listOf(
                Message(
                    id = "msg-rea-check-1",
                    conversationId = "b213d7c0-9bb6-4229-99be-7b7855c5c195",
                    role = dev.agentic.core.model.Role.USER,
                    content = "Check whether REA is available.\n\nList its reverse-engineering capabilities."
                ),
                Message(
                    id = "msg-rea-check-2",
                    conversationId = "b213d7c0-9bb6-4229-99be-7b7855c5c195",
                    role = dev.agentic.core.model.Role.ASSISTANT,
                    content = "REA (Reverse Engineer Anything) MCP tools are connected and available for native binary, APK, and Web analysis."
                )
            ),
            "c367db09-5d84-4822-8946-5d626af85692" to listOf(
                Message(
                    id = "msg-rea-port-1",
                    conversationId = "c367db09-5d84-4822-8946-5d626af85692",
                    role = dev.agentic.core.model.Role.USER,
                    content = "Check whether REA is available.\n\nList its reverse-engineering capabilities.\nDo not investigate or execute any other step yet."
                ),
                Message(
                    id = "msg-rea-port-2",
                    conversationId = "c367db09-5d84-4822-8946-5d626af85692",
                    role = dev.agentic.core.model.Role.ASSISTANT,
                    content = "REA MCP tools are verified and active on the Antigravity system."
                )
            )
        )
    }

    private val _conversations = MutableStateFlow(DEFAULT_CONVERSATIONS)
    private val _messages = MutableStateFlow<Map<String, List<Message>>>(DEFAULT_MESSAGES)

    override fun getAllConversations(): Flow<List<Conversation>> = _conversations.asStateFlow()

    override fun getConversations(projectId: String): Flow<List<Conversation>> {
        return _conversations.map { list ->
            if (projectId.isBlank() || projectId == "all") list else list.filter { it.projectId == projectId }
        }
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return _messages.map { it[conversationId] ?: emptyList() }
    }

    override suspend fun addMessage(conversationId: String, message: Message) {
        val current = _messages.value[conversationId] ?: emptyList()
        _messages.value = _messages.value + (conversationId to (current + message))
    }

    override suspend fun upsertConversation(conversation: Conversation) {
        val updated = _conversations.value.filter { it.id != conversation.id } + conversation
        _conversations.value = updated
    }

    override suspend fun setConversations(conversations: List<Conversation>) {
        _conversations.value = conversations
    }

    override suspend fun setMessages(conversationId: String, messages: List<Message>) {
        _messages.value = _messages.value + (conversationId to messages)
    }
}
