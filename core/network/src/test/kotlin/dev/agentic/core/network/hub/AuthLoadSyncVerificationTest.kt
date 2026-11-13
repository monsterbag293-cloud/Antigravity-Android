package dev.agentic.core.network.hub

import dev.agentic.core.data.InMemoryConversationRepository
import dev.agentic.core.data.InMemoryProjectRepository
import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import dev.agentic.core.network.jetski.GrpcWebHttpRequest
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AuthLoadSyncVerificationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val csrf = "csrf-test-sync"

    private val dummyModelProvider = object : ModelProvider {
        override val providerId: String = "test-provider"
        override val displayName: String = "Test Provider"

        override suspend fun streamCompletion(
            request: ModelRequest,
            tools: List<ModelToolDefinition>,
            onDelta: (String) -> Unit
        ): ModelResponse = ModelResponse("Dummy response")
    }

    private fun decodeResponseJson(bodyBytes: ByteArray): kotlinx.serialization.json.JsonObject {
        val frames = GrpcWebFrameCodec.decodeFrames(bodyBytes)
        val dataFrame = frames.firstOrNull { !it.isTrailer } ?: frames.first()
        return json.parseToJsonElement(dataFrame.utf8Text).jsonObject
    }

    @Test
    fun `LanguageServerGrpcWebHandler returns official schemas for projects, conversations, skills, and MCP on auth load`() = runBlocking {
        val tempDir = Files.createTempDirectory("ls-auth-sync-test").toFile()
        try {
            val rpcHandler = LanguageServerGrpcWebHandler(
                modelProvider = dummyModelProvider,
                isAuthenticatedProvider = { true },
                csrfToken = csrf,
                workspaceRootDir = tempDir,
                initialBackendMode = BackendMode.LOCAL_GEMINI_FALLBACK
            )

            // 1. Verify ListProjects
            val listProjectsReq = GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/ListProjects",
                method = "POST",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
            val listResp = rpcHandler.handleRequest(listProjectsReq)
            assertEquals(200, listResp.statusCode)
            val listJson = decodeResponseJson(listResp.body)
            val projectIds = listJson["projectIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertTrue("Expected ChaosAI project in list", projectIds.contains("5937e51d-c88e-4862-8f07-3ce0e2625228"))
            assertTrue("Expected Antigravity Port project in list", projectIds.contains("f62d4d56-8673-4da6-a2c0-458e5e2eb5bd"))

            // 2. Verify ReadProjects returns JSON Array matching desktop language_server.proto
            val readProjectsReq = GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/ReadProjects",
                method = "POST",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{\"ids\": [\"5937e51d-c88e-4862-8f07-3ce0e2625228\", \"f62d4d56-8673-4da6-a2c0-458e5e2eb5bd\"]}")
            )
            val readResp = rpcHandler.handleRequest(readProjectsReq)
            assertEquals(200, readResp.statusCode)
            val readJson = decodeResponseJson(readResp.body)
            val projectsArray = readJson["projects"]?.jsonArray
            assertNotNull("ReadProjects must return a JSON array", projectsArray)
            assertEquals(2, projectsArray!!.size)

            val pNames = projectsArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            assertTrue("Expected ChaosAI project name", pNames.contains("ChaosAI"))
            assertTrue("Expected Antigravity Port project name", pNames.contains("Antigravity Port"))

            // 3. Verify GetAllCascadeTrajectories returns desktop conversation summaries
            val trajectoriesReq = GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetAllCascadeTrajectories",
                method = "POST",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
            val trajResp = rpcHandler.handleRequest(trajectoriesReq)
            assertEquals(200, trajResp.statusCode)
            val trajJson = decodeResponseJson(trajResp.body)
            val summaries = trajJson["trajectorySummaries"]?.jsonObject
            assertNotNull(summaries)
            assertTrue("Expected REA desktop conversation in summaries", summaries!!.containsKey("c367db09-5d84-4822-8946-5d626af85692"))
            assertTrue("Expected ChaosAI conversation in summaries", summaries.containsKey("af4bd875-5f28-4b11-b783-d4954beed236"))

            // 4. Verify GetCascadeTrajectorySteps for desktop conversation
            val stepsReq = GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetCascadeTrajectorySteps",
                method = "POST",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{\"cascadeId\": \"c367db09-5d84-4822-8946-5d626af85692\", \"stepOffset\": 0}")
            )
            val stepsResp = rpcHandler.handleRequest(stepsReq)
            assertEquals(200, stepsResp.statusCode)
            val stepsJson = decodeResponseJson(stepsResp.body)
            val steps = stepsJson["steps"]?.jsonArray
            assertNotNull(steps)
            assertTrue("Expected steps for c367db09 conversation", steps!!.isNotEmpty())

            // 5. Verify Skills and Customizations
            val skillsReq = GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetAllSkills",
                method = "POST",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
            val skillsResp = rpcHandler.handleRequest(skillsReq)
            assertEquals(200, skillsResp.statusCode)
            val skillsJson = decodeResponseJson(skillsResp.body)
            val skills = skillsJson["skills"]?.jsonArray
            assertNotNull(skills)
            assertEquals(10, skills!!.size)

            // 6. Verify MCP servers
            val mcpReq = GrpcWebHttpRequest(
                path = "/exa.language_server_pb.LanguageServerService/GetMcpServerStates",
                method = "POST",
                headers = mapOf("x-codeium-csrf-token" to csrf),
                body = GrpcWebFrameCodec.encodeDataFrame("{}")
            )
            val mcpResp = rpcHandler.handleRequest(mcpReq)
            assertEquals(200, mcpResp.statusCode)
            val mcpJson = decodeResponseJson(mcpResp.body)
            val mcpServers = mcpJson["mcpServers"]?.jsonArray
            assertNotNull(mcpServers)
            assertTrue("Expected rea MCP server", mcpServers!!.any { it.jsonObject["name"]?.jsonPrimitive?.content == "rea" })

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `InMemoryRepositories support desktop synchronized conversations and projects`() = runBlocking {
        val projRepo = InMemoryProjectRepository()
        val convRepo = InMemoryConversationRepository()

        // 1. Verify default desktop projects are seeded
        val projects = projRepo.getProjects().first()
        assertTrue("Projects must contain Antigravity Port", projects.any { it.name == "Antigravity Port" })
        assertTrue("Projects must contain ChaosAI", projects.any { it.name == "ChaosAI" })

        // 2. Verify default desktop conversations are seeded
        val conversations = convRepo.getAllConversations().first()
        assertTrue("Conversations must contain REA Availability and Capabilities", conversations.any { it.id == "c367db09-5d84-4822-8946-5d626af85692" })
        assertTrue("Conversations must contain ChaosAI Project Architecture Design", conversations.any { it.id == "af4bd875-5f28-4b11-b783-d4954beed236" })

        // 3. Verify messages are populated for c367db09
        val messages = convRepo.getMessages("c367db09-5d84-4822-8946-5d626af85692").first()
        assertTrue("Expected messages for c367db09", messages.isNotEmpty())
        assertEquals(dev.agentic.core.model.Role.USER, messages[0].role)
        assertTrue(messages[0].content.contains("Check whether REA is available"))
    }
}
