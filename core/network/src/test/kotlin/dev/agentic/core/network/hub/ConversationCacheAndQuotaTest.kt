package dev.agentic.core.network.hub

import dev.agentic.core.network.jetski.GrpcStatus
import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import dev.agentic.core.network.jetski.GrpcWebHttpRequest
import dev.agentic.core.network.jetski.LanguageServerGrpcWebHandler
import dev.agentic.model.ModelProvider
import dev.agentic.model.ModelRequest
import dev.agentic.model.ModelResponse
import dev.agentic.model.ModelToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class ConversationCacheAndQuotaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ConversationCacheStore persists and restores zero-project conversations across restarts`() {
        val dir = Files.createTempDirectory("conv-cache-test").toFile()
        try {
            val cacheFile = File(dir, "zero_project_cache.json")
            val store1 = ConversationCacheStore(cacheFile)

            val summariesJson = json.parseToJsonElement(
                """
                {
                  "conv-zero-1": {
                    "summary": "Quick standalone chat",
                    "stepCount": 2,
                    "trajectoryMetadata": { "projectId": "outside-of-project" }
                  },
                  "conv-proj-1": {
                    "summary": "Project chat",
                    "stepCount": 4,
                    "trajectoryMetadata": { "projectId": "proj-123" }
                  }
                }
                """.trimIndent()
            ).jsonObject
            store1.updateFromSummariesMap(summariesJson)

            val stepsJson = json.parseToJsonElement(
                """
                {
                  "steps": [
                    { "type": "CORTEX_STEP_TYPE_USER_INPUT", "userInput": { "items": [{ "text": "Hello standalone" }] } },
                    { "type": "CORTEX_STEP_TYPE_PLANNER_RESPONSE", "plannerResponse": { "response": "Hi there!" } }
                  ]
                }
                """.trimIndent()
            ).jsonObject
            store1.updateFromStepsResponse("conv-zero-1", stepsJson)

            // Simulate process restart by instantiating a new store from disk
            val store2 = ConversationCacheStore(cacheFile)
            assertTrue(store2.isTrackedZeroProject("conv-zero-1"))
            assertFalse(store2.isTrackedZeroProject("conv-proj-1"))

            val emptyRemote = json.parseToJsonElement("{}").jsonObject
            val merged = store2.mergeIntoSummariesMap(emptyRemote)
            assertTrue(merged.containsKey("conv-zero-1"))
            assertFalse(merged.containsKey("conv-proj-1"))

            val cachedSteps = store2.getCachedStepsResponse("conv-zero-1", 0)
            assertNotNull(cachedSteps)
            assertEquals(2, cachedSteps!!["steps"]!!.jsonArray.size)

            val cachedSnap = store2.getCachedStreamSnapshotResponse("conv-zero-1")
            assertNotNull(cachedSnap)
            val snapSteps = cachedSnap!!["update"]!!.jsonObject["mainTrajectoryUpdate"]!!
                .jsonObject["stepsUpdate"]!!.jsonObject["steps"]!!.jsonArray
            assertEquals(2, snapSteps.size)

            // Deleting must survive across restarts
            store2.markDeleted("conv-zero-1")
            val store3 = ConversationCacheStore(cacheFile)
            assertFalse(store3.isTrackedZeroProject("conv-zero-1"))
            assertFalse(store3.mergeIntoSummariesMap(emptyRemote).containsKey("conv-zero-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `RemoteHubProxyHandler falls back on quota error and merges cached zero-project steps`() = runBlocking {
        val dir = Files.createTempDirectory("proxy-cache-test").toFile()
        val server = ServerSocket(0)
        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    Thread {
                        sock.use { s ->
                            val input = s.getInputStream().bufferedReader()
                            val reqLine = input.readLine() ?: return@use
                            val path = reqLine.split(" ").getOrNull(1) ?: "/"
                            var contentLength = 0
                            var line: String?
                            while (input.readLine().also { line = it } != null && line!!.isNotBlank()) {
                                if (line!!.lowercase().startsWith("content-length:")) {
                                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                            }
                            if (contentLength > 0) {
                                val buf = CharArray(contentLength)
                                var read = 0
                                while (read < contentLength) {
                                    val n = input.read(buf, read, contentLength - read)
                                    if (n < 0) break
                                    read += n
                                }
                            }

                            val out = s.getOutputStream()
                            if (path == RemoteHubConnectionManager.RPC_GET_USER_STATUS) {
                                val okJson = """{"userStatus":{"planStatus":{"planInfo":{"planName":"Pro"}}}}""".toByteArray(Charsets.UTF_8)
                                val hdr = buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Type: application/json\r\n")
                                    append("Content-Length: ${okJson.size}\r\n")
                                    append("Connection: close\r\n\r\n")
                                }
                                out.write(hdr.toByteArray(Charsets.US_ASCII))
                                out.write(okJson)
                                out.flush()
                                return@use
                            }
                            val bodyBytes = when {
                                path.endsWith("/RetrieveUserQuotaSummary") -> {
                                    // Upstream fails with trailer-only UNAVAILABLE
                                    GrpcWebFrameCodec.encodeTrailerFrame(GrpcStatus.UNAVAILABLE, "quota offline")
                                }
                                path.endsWith("/GetAllCascadeTrajectories") -> {
                                    GrpcWebFrameCodec.encodeUnaryResponse("""{"trajectorySummaries":{}}""")
                                }
                                path.endsWith("/GetCascadeTrajectorySteps") -> {
                                    GrpcWebFrameCodec.encodeTrailerFrame(GrpcStatus.NOT_FOUND, "not found")
                                }
                                path.endsWith("/StreamAgentStateUpdates") -> {
                                    GrpcWebFrameCodec.encodeServerStreamResponse(
                                        listOf(
                                            """{"update":{"conversationId":"live-1","status":"CASCADE_RUN_STATUS_RUNNING"}}""",
                                            """{"update":{"conversationId":"live-1","status":"CASCADE_RUN_STATUS_IDLE","fullyIdle":true}}"""
                                        )
                                    )
                                }
                                else -> GrpcWebFrameCodec.encodeUnaryResponse("{}")
                            }
                            val hdr = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: application/grpc-web+json\r\n")
                                append("Content-Length: ${bodyBytes.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            out.write(bodyBytes)
                            out.flush()
                        }
                    }.start()
                }
            } catch (_: Exception) {}
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val cache = ConversationCacheStore(File(dir, "cache.json"))
            cache.recordStartedZeroProject("zero-cached", "Cached Zero Chat")
            cache.updateFromStepsResponse(
                "zero-cached",
                json.parseToJsonElement("""{"steps":[{"type":"CORTEX_STEP_TYPE_USER_INPUT","userInput":{"items":[{"text":"Saved prompt"}]}}]}""").jsonObject
            )

            val connMgr = RemoteHubConnectionManager()
            val connRes = connMgr.connect(
                DesktopHubConfig(host = "127.0.0.1", port = server.localPort, csrfToken = "csrf", useTls = false)
            )
            assertTrue("Expected test hub connect to succeed", connRes.isSuccess)
            val proxy = RemoteHubProxyHandler(connMgr, conversationCache = cache)

            // 1. Quota fallback should synthesize non-empty default groups cleanly
            val quotaResp = proxy.relayRequest(
                GrpcWebHttpRequest(
                    path = "/exa.language_server_pb.LanguageServerService/RetrieveUserQuotaSummary",
                    body = GrpcWebFrameCodec.encodeDataFrame("{}")
                )
            )
            assertEquals(200, quotaResp.statusCode)
            val quotaFrames = GrpcWebFrameCodec.decodeFrames(quotaResp.body)
            val quotaObj = json.parseToJsonElement(quotaFrames.first { !it.isTrailer }.utf8Text).jsonObject
            val groups = quotaObj["response"]!!.jsonObject["groups"]!!.jsonArray
            assertTrue(groups.isNotEmpty())
            assertTrue(quotaFrames.last().utf8Text.contains("grpc-status: 0"))

            // 2. GetAllCascadeTrajectories merges cached zero-project conversation
            val allResp = proxy.relayRequest(
                GrpcWebHttpRequest(
                    path = "/exa.language_server_pb.LanguageServerService/GetAllCascadeTrajectories",
                    body = GrpcWebFrameCodec.encodeDataFrame("{}")
                )
            )
            val allJson = json.parseToJsonElement(GrpcWebFrameCodec.extractFirstRequestJson(allResp.body)).jsonObject
            assertTrue(allJson["trajectorySummaries"]!!.jsonObject.containsKey("zero-cached"))

            // 3. GetCascadeTrajectorySteps serves cached steps on upstream NOT_FOUND
            val stepsResp = proxy.relayRequest(
                GrpcWebHttpRequest(
                    path = "/exa.language_server_pb.LanguageServerService/GetCascadeTrajectorySteps",
                    body = GrpcWebFrameCodec.encodeDataFrame("""{"cascadeId":"zero-cached","stepOffset":0}""")
                )
            )
            val stepsJson = json.parseToJsonElement(GrpcWebFrameCodec.extractFirstRequestJson(stepsResp.body)).jsonObject
            assertEquals(1, stepsJson["steps"]!!.jsonArray.size)

            // 4. StreamAgentStateUpdates uses live pass-through bodyStream preserving multiple frames
            val streamResp = proxy.relayRequest(
                GrpcWebHttpRequest(
                    path = "/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates",
                    body = GrpcWebFrameCodec.encodeDataFrame("""{"conversationId":"live-1"}""")
                )
            )
            val streamBytes = streamResp.openStream().use { it.readBytes() }
            val streamFrames = GrpcWebFrameCodec.decodeFrames(streamBytes)
            assertEquals(3, streamFrames.size) // 2 data frames + 1 trailer
            assertTrue(streamFrames[0].utf8Text.contains("RUNNING"))
            assertTrue(streamFrames[1].utf8Text.contains("IDLE"))
        } finally {
            server.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Protobuf-ES zero-project cache with Timestamp objects restores without crashing`() {
        val dir = Files.createTempDirectory("proto-es-cache-test").toFile()
        try {
            val cacheFile = File(dir, "zero_project_cache.json")
            val store1 = ConversationCacheStore(cacheFile)
            store1.recordStartedZeroProject("proto-es-1", "Create Example Gsheets Data")
            val protoEsSteps = json.parseToJsonElement(
                """
                {
                  "steps": [
                    {
                      "${'$'}typeName": "gemini_coder.Step",
                      "type": 14,
                      "status": 3,
                      "step": {
                        "case": "userInput",
                        "value": {
                          "items": [
                            { "chunk": { "case": "text", "value": "Create a sheet of data" } }
                          ],
                          "userResponse": "Create a sheet of data"
                        }
                      },
                      "metadata": {
                        "createdAt": { "${'$'}typeName": "google.protobuf.Timestamp", "seconds": "1794811789", "nanos": 450394526 }
                      }
                    },
                    {
                      "${'$'}typeName": "gemini_coder.Step",
                      "type": 15,
                      "status": 3,
                      "step": {
                        "case": "plannerResponse",
                        "value": {
                          "response": "I have created an example spreadsheet!",
                          "thinking": "Planning sheet layout"
                        }
                      },
                      "metadata": {
                        "createdAt": { "${'$'}typeName": "google.protobuf.Timestamp", "seconds": "1794811790", "nanos": 0 },
                        "completedAt": { "${'$'}typeName": "google.protobuf.Timestamp", "seconds": "1794811795", "nanos": 0 }
                      }
                    }
                  ]
                }
                """.trimIndent()
            ).jsonObject
            store1.updateFromStepsResponse("proto-es-1", protoEsSteps)

            // Verify steps are normalized to Proto-JSON and LanguageServerGrpcWebHandler restores cleanly
            val normalized = store1.getCachedStepsResponse("proto-es-1", 0)
            assertNotNull(normalized)
            val arr = normalized!!["steps"]!!.jsonArray
            assertEquals(2, arr.size)
            assertEquals("CORTEX_STEP_TYPE_USER_INPUT", arr[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("I have created an example spreadsheet!", arr[1].jsonObject["plannerResponse"]!!.jsonObject["response"]!!.jsonPrimitive.content)

            val snap = store1.getCachedStreamSnapshotResponse("proto-es-1")
            val turns = snap!!["update"]!!.jsonObject["turnsUpdate"]!!.jsonObject["turns"]!!.jsonArray
            assertEquals(1, turns.size)

            val store2 = ConversationCacheStore(cacheFile)
            val dummyProvider = object : ModelProvider {
                override val providerId: String = "dummy"
                override val displayName: String = "Dummy"
                override suspend fun streamCompletion(
                    request: ModelRequest,
                    tools: List<ModelToolDefinition>,
                    onDelta: (String) -> Unit
                ): ModelResponse {
                    return ModelResponse(text = "ok")
                }
            }
            val handler = LanguageServerGrpcWebHandler(
                modelProvider = dummyProvider,
                isAuthenticatedProvider = { true },
                csrfToken = "csrf",
                workspaceRootDir = dir,
                conversationCache = store2
            )
            val rec = handler.getTrajectoriesSnapshot()["proto-es-1"]
            assertNotNull("Expected Protobuf-ES cached conversation to restore without crashing", rec)
            assertEquals(2, rec!!.steps.size)
            assertEquals("Create a sheet of data", rec.steps[0].userText)
            assertEquals("I have created an example spreadsheet!", rec.steps[1].plannerResponseText)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ConversationCacheStore persists project lists and all summaries for instant cold load`() {
        val dir = Files.createTempDirectory("proj-cache-test").toFile()
        try {
            val cacheFile = File(dir, "cache.json")
            val store1 = ConversationCacheStore(cacheFile)
            store1.cacheProjectListIds(listOf("proj-a", "proj-b"))
            val readResp = json.parseToJsonElement(
                """{"projects":[{"id":"proj-a","name":"Alpha"},{"id":"proj-b","name":"Beta"}]}"""
            ).jsonObject
            store1.cacheReadProjectsResponse(readResp)

            val store2 = ConversationCacheStore(cacheFile)
            assertEquals(listOf("proj-a", "proj-b"), store2.getCachedProjectListIds())
            val restoredRead = store2.getCachedReadProjectsResponse()
            assertNotNull(restoredRead)
            assertEquals(2, restoredRead!!["projects"]!!.jsonArray.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ConversationCacheStore isolates local vs cloud project IDs and merges ReadProjects entries`() {
        val dir = Files.createTempDirectory("cloud-local-cache-test").toFile()
        try {
            val cacheFile = File(dir, "cache.json")
            val store1 = ConversationCacheStore(cacheFile)
            store1.cacheProjectListIds(listOf("local-1", ConversationCacheStore.OUTSIDE_OF_PROJECT_ID), backend = 0)
            store1.cacheProjectListIds(listOf("cloud-1", "cloud-2"), backend = 2)

            // Transient empty/outside-only local poll must NOT overwrite cloud or local caches
            store1.cacheProjectListIds(listOf(ConversationCacheStore.OUTSIDE_OF_PROJECT_ID), backend = 0)
            store1.cacheProjectListIds(emptyList(), backend = 2)

            store1.cacheReadProjectsResponse(
                json.parseToJsonElement("""{"projects":[{"id":"local-1","name":"LocalProj"}]}""").jsonObject
            )
            store1.cacheReadProjectsResponse(
                json.parseToJsonElement("""{"projects":[{"id":"cloud-1","name":"CloudProj"}]}""").jsonObject
            )

            val store2 = ConversationCacheStore(cacheFile)
            assertEquals(listOf("local-1", ConversationCacheStore.OUTSIDE_OF_PROJECT_ID), store2.getCachedProjectListIds(0))
            assertEquals(listOf("cloud-1", "cloud-2"), store2.getCachedProjectListIds(2))
            assertEquals(emptyList<String>(), store2.getCachedProjectListIds(3))

            val allRead = store2.getCachedReadProjectsResponse()!!["projects"]!!.jsonArray
            assertEquals(2, allRead.size)
            val cloudOnly = store2.getCachedReadProjectsResponse(setOf("cloud-1"))!!["projects"]!!.jsonArray
            assertEquals(1, cloudOnly.size)
            assertEquals("CloudProj", cloudOnly[0].jsonObject["name"]!!.jsonPrimitive.content)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `JetskiBundleLoader boot script supports light theme, pointerover tooltips, fast stream signatures, and remote anti-flicker`() {
        val html = dev.agentic.core.network.jetski.JetskiBundleLoader().injectBootConfig(
            indexHtml = "<html><head></head><body><div id=\"root\"></div></body></html>",
            appConfig = dev.agentic.core.network.jetski.JetskiAppConfig(csrfToken = "test-csrf")
        )
        // 1. Light/system appearance must not lock html/body/.agy-shell to #000000 unconditionally
        assertTrue(html.contains("body.dark .agy-inset-shell"))
        assertTrue(html.contains("body:not(.dark) .agy-inset-shell"))
        assertTrue(html.contains("setDarkTheme"))
        assertFalse(html.contains("html, body, #root, .agy-shell, [data-testid=\"mobile-layout\"] {\n                background-color: #000000 !important;"))

        // 2. Notice (i) info badges inside menu items dispatch pointerover and suppress pointerout
        assertTrue(html.contains("new PointerEvent('pointerover'"))
        assertTrue(html.contains("findNestedTooltipBadge"))
        assertTrue(html.contains("pointerout"))

        // 3. Performance & cloud anti-flicker
        assertTrue(html.contains("fastAgentStateSig"))
        assertTrue(html.contains("watchVersionControlState: true"))
        assertTrue(html.contains("subscribeToAgentBrowserRequests: true"))
        assertTrue(html.contains("__agyLoadedRemoteProjectCount"))
        assertTrue(html.contains("const isProjectStream = (methodName === 'projectUpdatesStream')"))
        assertTrue(html.contains("__agyStreamWakeups"))
        assertTrue(html.contains("__agyRunFirstLoginSync"))

        // 4. Voice mic styling & Lexical speech insertion resilience + nativeStorage sanitization
        assertTrue(html.contains("body.agy-voice-recording"))
        assertTrue(html.contains("applyMicVisualToButton"))
        assertTrue(html.contains("tryInsertIntoEditor"))
        assertTrue(html.contains("sanitizeNativeStorageItems"))
    }

    @Test
    fun `ConversationCacheStore migrates remote backend projects, filters sidecars, and merges ReadProjects`() {
        val dir = Files.createTempDirectory("cache-backend-migrate-test").toFile()
        try {
            val cacheFile = File(dir, "zero_project_cache.json")
            val store1 = ConversationCacheStore(cacheFile)

            // Cache a real project + a sidecar project first
            store1.cacheReadProjectsResponse(
                json.parseToJsonElement(
                    """
                    {
                      "projects": [
                        {
                          "id": "proj-cloud-1",
                          "name": "ChaosAI",
                          "workspacePath": "github/monsterbag293-cloud/ChaosAI",
                          "backendType": "BACKEND_TYPE_REMOTE_AICODE"
                        },
                        {
                          "id": "proj-sidecar",
                          "name": "swarm/swarm_board",
                          "sidecar": {}
                        }
                      ]
                    }
                    """.trimIndent()
                ).jsonObject
            )
            assertTrue(store1.isSidecarProjectId("proj-sidecar"))
            assertFalse(store1.isSidecarProjectId("proj-cloud-1"))

            // Simulate older cache where remote ID + sidecar + outside-of-project landed in backend 0
            store1.cacheProjectListIds(listOf("proj-cloud-1", "proj-sidecar", "outside-of-project"), backend = 0)
            // Reload from disk -> migrateBackendProjectIds moves BACKEND_TYPE_REMOTE_AICODE to backend 2 and purges sidecar/outside
            val store2 = ConversationCacheStore(cacheFile)
            val cloudIds = store2.getCachedProjectListIds(backend = 2)
            val localIds = store2.getCachedProjectListIds(backend = 0)
            assertTrue("Expected proj-cloud-1 migrated to backend 2, got $cloudIds", cloudIds.contains("proj-cloud-1"))
            assertFalse("Did not expect proj-cloud-1 remaining in backend 0, got $localIds", localIds.contains("proj-cloud-1"))
            assertFalse(localIds.contains("proj-sidecar"))
            val diskJson = json.parseToJsonElement(cacheFile.readText(Charsets.UTF_8)).jsonObject
            val diskByBackend = diskJson["cachedProjectIdsByBackend"]!!.jsonObject
            assertTrue(diskByBackend["2"]!!.jsonArray.any { it.jsonPrimitive.content == "proj-cloud-1" })
            assertFalse(diskByBackend["0"]?.jsonArray?.any { it.jsonPrimitive.content == "proj-sidecar" } == true)
            assertFalse(diskByBackend["0"]?.jsonArray?.any { it.jsonPrimitive.content == "proj-cloud-1" } == true)
            val emptyOnlyStore = ConversationCacheStore(File(dir, "outside_only_cache.json"))
            emptyOnlyStore.cacheProjectListIds(listOf("outside-of-project"), backend = 0)
            assertTrue(emptyOnlyStore.getCachedProjectListIds(backend = 0).isEmpty())

            // mergeReadProjectsResponse should preserve cached proj-cloud-1 and strip it from notFoundIds
            val merged = store2.mergeReadProjectsResponse(
                upstreamObj = json.parseToJsonElement(
                    """
                    {
                      "projects": [],
                      "notFoundIds": ["proj-cloud-1", "truly-missing"]
                    }
                    """.trimIndent()
                ).jsonObject,
                requestedIds = setOf("proj-cloud-1", "truly-missing")
            )
            val mergedIds = merged["projects"]!!.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertTrue(mergedIds.contains("proj-cloud-1"))
            assertFalse(mergedIds.contains("proj-sidecar"))
            val remainingNotFound = merged["notFoundIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertFalse(remainingNotFound.contains("proj-cloud-1"))
            assertTrue(remainingNotFound.contains("truly-missing"))

            // Agent state snapshot cache round-trip (requires non-empty mainTrajectoryUpdate.stepsUpdate.steps)
            store2.cacheAgentStateSnapshot(
                "conv-snap-1",
                json.parseToJsonElement(
                    """{"update":{"conversationId":"conv-snap-1","status":"CASCADE_RUN_STATUS_IDLE","mainTrajectoryUpdate":{"stepsUpdate":{"steps":[{"type":"CORTEX_STEP_TYPE_USER_INPUT","userInput":{"items":[{"text":"Hello"}]}}]}}}}"""
                ).jsonObject
            )
            val cachedSnap = store2.getCachedStreamSnapshotResponse("conv-snap-1")
            assertTrue(cachedSnap != null && cachedSnap.toString().contains("conv-snap-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `RemoteHubProxyHandler synthesizes Connect+ cloud streams, Mendel flags, and finite agent state snapshots`() =
        kotlinx.coroutines.runBlocking {
            val dir = Files.createTempDirectory("remote-hub-proxy-cloud-test").toFile()
            val server = java.net.ServerSocket(0)
            val emptyCloudStreams = java.util.concurrent.atomic.AtomicBoolean(false)
            val failAgentStream = java.util.concurrent.atomic.AtomicBoolean(false)
            val keepOpenSockets = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())

            val connectTrailerBytes = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x02, '{'.code.toByte(), '}'.code.toByte())

            val serverThread = Thread {
                try {
                    while (!server.isClosed) {
                        val s = try {
                            server.accept()
                        } catch (_: Exception) {
                            break
                        }
                        Thread {
                            try {
                                val inp = s.getInputStream()
                                val headerBuf = java.io.ByteArrayOutputStream()
                                var matched = 0
                                while (true) {
                                    val b = inp.read()
                                    if (b < 0) break
                                    headerBuf.write(b)
                                    val expected = when (matched) {
                                        0, 2 -> '\r'.code
                                        1, 3 -> '\n'.code
                                        else -> -1
                                    }
                                    matched = if (b == expected) matched + 1 else if (b == '\r'.code) 1 else 0
                                    if (matched == 4) break
                                }
                                val headerText = headerBuf.toString("ISO_8859_1")
                                val lines = headerText.split("\r\n")
                                val path = lines.firstOrNull()?.split(" ")?.getOrNull(1) ?: ""
                                val contentLength = lines.firstOrNull {
                                    it.startsWith("Content-Length:", ignoreCase = true)
                                }?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                                if (contentLength > 0) {
                                    var read = 0
                                    val tmp = ByteArray(contentLength)
                                    while (read < contentLength) {
                                        val n = inp.read(tmp, read, contentLength - read)
                                        if (n < 0) break
                                        read += n
                                    }
                                }

                                val out = s.getOutputStream()
                                if (path == RemoteHubConnectionManager.RPC_GET_USER_STATUS) {
                                    val okJson = """{"userStatus":{"planStatus":{"planInfo":{"planName":"Pro"}}}}""".toByteArray(Charsets.UTF_8)
                                    out.write(
                                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${okJson.size}\r\nConnection: close\r\n\r\n")
                                            .toByteArray(Charsets.US_ASCII)
                                    )
                                    out.write(okJson)
                                    out.flush()
                                    s.close()
                                    return@Thread
                                }

                                when {
                                    path.endsWith("/GetMendelFlags") -> {
                                        val okJson = """{"experimentConfig":{"experiments":[{"keyString":"enable-remote-ai","disabled":true}]}}""".toByteArray(Charsets.UTF_8)
                                        out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${okJson.size}\r\nConnection: close\r\n\r\n")
                                                .toByteArray(Charsets.US_ASCII)
                                        )
                                        out.write(okJson)
                                        out.flush()
                                        s.close()
                                    }

                                    path.endsWith("/ProjectUpdatesStream") -> {
                                        val body = if (!emptyCloudStreams.get()) {
                                            GrpcWebFrameCodec.encodeDataFrame(
                                                """{"projectList":{"projectIds":["proj-cloud-99"],"backend":"BACKEND_TYPE_REMOTE_AICODE"}}"""
                                            ) + connectTrailerBytes
                                        } else {
                                            connectTrailerBytes
                                        }
                                        out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type: application/connect+json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                                .toByteArray(Charsets.US_ASCII)
                                        )
                                        out.write(body)
                                        out.flush()
                                        s.close()
                                    }

                                    path.endsWith("/JetboxSubscribeToSummaries") -> {
                                        val body = if (!emptyCloudStreams.get()) {
                                            GrpcWebFrameCodec.encodeDataFrame(
                                                """{"updates":{"cascade-cloud-1":{"summary":"Cloud Chaos Chat"}},"backend":"BACKEND_TYPE_REMOTE_AICODE"}"""
                                            ) + connectTrailerBytes
                                        } else {
                                            connectTrailerBytes
                                        }
                                        out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type: application/connect+json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                                .toByteArray(Charsets.US_ASCII)
                                        )
                                        out.write(body)
                                        out.flush()
                                        s.close()
                                    }

                                    path.endsWith("/ReadProjects") -> {
                                        val body = """{"projects":[],"notFoundIds":["proj-cloud-99"]}""".toByteArray(Charsets.UTF_8)
                                        out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                                .toByteArray(Charsets.US_ASCII)
                                        )
                                        out.write(body)
                                        out.flush()
                                        s.close()
                                    }

                                    path.endsWith("/StreamAgentStateUpdates") -> {
                                        if (!failAgentStream.get()) {
                                            keepOpenSockets.add(s)
                                            val frame1 = GrpcWebFrameCodec.encodeDataFrame(
                                                """{"update":{"conversationId":"conv-snap-live","status":"CASCADE_RUN_STATUS_IDLE","mainTrajectoryUpdate":{"stepsUpdate":{"steps":[{"type":"CORTEX_STEP_TYPE_USER_INPUT","userInput":{"items":[{"text":"SnapPrompt"}]}}]}}}}"""
                                            )
                                            // Simulate live language_server sending Frame 1 and holding the HTTP stream open indefinitely
                                            out.write(
                                                ("HTTP/1.1 200 OK\r\nContent-Type: application/grpc-web+json\r\nConnection: close\r\n\r\n")
                                                    .toByteArray(Charsets.US_ASCII)
                                            )
                                            out.write(frame1)
                                            out.flush()
                                        } else {
                                            val errBody = GrpcWebFrameCodec.encodeTrailerFrame(GrpcStatus.UNAVAILABLE, "offline")
                                            out.write(
                                                ("HTTP/1.1 200 OK\r\nContent-Type: application/grpc-web+json\r\nContent-Length: ${errBody.size}\r\nConnection: close\r\n\r\n")
                                                    .toByteArray(Charsets.US_ASCII)
                                            )
                                            out.write(errBody)
                                            out.flush()
                                            s.close()
                                        }
                                    }

                                    else -> {
                                        val ok = "{}".toByteArray(Charsets.UTF_8)
                                        out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${ok.size}\r\nConnection: close\r\n\r\n")
                                                .toByteArray(Charsets.US_ASCII)
                                        )
                                        out.write(ok)
                                        out.flush()
                                        s.close()
                                    }
                                }
                            } catch (_: Exception) {
                                runCatching { s.close() }
                            }
                        }.start()
                    }
                } catch (_: Exception) {}
            }
            serverThread.isDaemon = true
            serverThread.start()

            try {
                val cache = ConversationCacheStore(File(dir, "cache.json"))
                cache.cacheReadProjectsResponse(
                    json.parseToJsonElement(
                        """
                        {
                          "projects": [
                            {
                              "id": "proj-cloud-99",
                              "name": "ChaosAI",
                              "backendType": "BACKEND_TYPE_REMOTE_AICODE"
                            }
                          ]
                        }
                        """.trimIndent()
                    ).jsonObject
                )

                val connMgr = RemoteHubConnectionManager()
                val connRes = connMgr.connect(
                    DesktopHubConfig(host = "127.0.0.1", port = server.localPort, csrfToken = "csrf", useTls = false)
                )
                assertTrue("Expected connect to succeed", connRes.isSuccess)
                val proxy = RemoteHubProxyHandler(connMgr, conversationCache = cache)

                // 1. GetMendelFlags injects enable-remote-ai
                val flagsResp = proxy.relayRequest(
                    GrpcWebHttpRequest(
                        path = "/exa.language_server_pb.LanguageServerService/GetMendelFlags",
                        headers = mapOf("Content-Type" to "application/json"),
                        body = "{}".toByteArray(Charsets.UTF_8)
                    )
                )
                val flagsText = flagsResp.body.toString(Charsets.UTF_8)
                assertTrue(flagsText.contains("enable-remote-ai"))
                assertTrue("Expected disabled:false in $flagsText", flagsText.contains(""""disabled":false"""))

                // 2. Seed cloud ProjectUpdatesStream & JetboxSubscribeToSummaries via BACKEND_TYPE_REMOTE_AICODE string
                val cloudReqBody = GrpcWebFrameCodec.encodeDataFrame("""{"backend":"BACKEND_TYPE_REMOTE_AICODE"}""")
                val projLive = proxy.relayRequest(
                    GrpcWebHttpRequest(
                        path = "/exa.language_server_pb.LanguageServerService/ProjectUpdatesStream",
                        headers = mapOf("Content-Type" to "application/connect+json"),
                        body = cloudReqBody
                    )
                )
                assertTrue(GrpcWebFrameCodec.extractFirstRequestJson(projLive.body).contains("proj-cloud-99"))
                assertTrue(cache.getCachedProjectListIds(backend = 2).contains("proj-cloud-99"))

                val sumLive = proxy.relayRequest(
                    GrpcWebHttpRequest(
                        path = "/exa.language_server_pb.LanguageServerService/JetboxSubscribeToSummaries",
                        headers = mapOf("Content-Type" to "application/connect+json"),
                        body = cloudReqBody
                    )
                )
                assertTrue(GrpcWebFrameCodec.extractFirstRequestJson(sumLive.body).contains("cascade-cloud-1"))

                // 3. When upstream returns ONLY empty 0x02 Connect trailer, proxy must still synthesize cached cloud projects & summaries
                emptyCloudStreams.set(true)
                val projSynth = proxy.relayRequest(
                    GrpcWebHttpRequest(
                        path = "/exa.language_server_pb.LanguageServerService/ProjectUpdatesStream",
                        headers = mapOf("Content-Type" to "application/connect+json"),
                        body = cloudReqBody
                    )
                )
                val projSynthFrames = GrpcWebFrameCodec.decodeFrames(projSynth.body)
                assertTrue("Expected synthesized data frame + trailer, got ${projSynthFrames.size}", projSynthFrames.any { !it.isTrailer })
                assertTrue(GrpcWebFrameCodec.extractFirstRequestJson(projSynth.body).contains("proj-cloud-99"))

                val sumSynth = proxy.relayRequest(
                    GrpcWebHttpRequest(
                        path = "/exa.language_server_pb.LanguageServerService/JetboxSubscribeToSummaries",
                        headers = mapOf("Content-Type" to "application/connect+json"),
                        body = cloudReqBody
                    )
                )
                assertTrue(GrpcWebFrameCodec.extractFirstRequestJson(sumSynth.body).contains("cascade-cloud-1"))

                // 4. ReadProjects merges cached cloud project even when upstream reports it in notFoundIds
                val readProjResp = proxy.relayRequest(
                    GrpcWebHttpRequest(
                        path = "/exa.language_server_pb.LanguageServerService/ReadProjects",
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"ids":["proj-cloud-99"]}""".toByteArray(Charsets.UTF_8)
                    )
                )
                val readProjText = readProjResp.body.toString(Charsets.UTF_8)
                assertTrue(readProjText.contains("proj-cloud-99"))
                assertFalse(readProjText.contains(""""notFoundIds":["proj-cloud-99"]"""))

                // 5. StreamAgentStateUpdates streams live frames immediately even when upstream holds socket open
                val agentSnapResp = proxy.relayRequest(
                    GrpcWebHttpRequest(
                        path = "/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates",
                        headers = mapOf("Content-Type" to "application/grpc-web+json"),
                        body = GrpcWebFrameCodec.encodeDataFrame("""{"conversationId":"conv-snap-live"}""")
                    )
                )
                val firstChunk = agentSnapResp.openStream().use { stream ->
                    val buf = ByteArray(8192)
                    val n = stream.read(buf)
                    if (n > 0) buf.copyOf(n) else ByteArray(0)
                }
                val agentSnapFrames = GrpcWebFrameCodec.decodeFrames(firstChunk)
                assertTrue(agentSnapFrames.any { !it.isTrailer && it.utf8Text.contains("conv-snap-live") })

                failAgentStream.set(true)
                val agentFallbackResp = proxy.relayRequest(
                    GrpcWebHttpRequest(
                        path = "/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates",
                        headers = mapOf("Content-Type" to "application/grpc-web+json"),
                        body = GrpcWebFrameCodec.encodeDataFrame("""{"conversationId":"conv-snap-live"}""")
                    )
                )
                assertTrue(agentFallbackResp.statusCode != 200 || agentFallbackResp.openStream().use { it.readBytes() }.isNotEmpty())
            } finally {
                synchronized(keepOpenSockets) {
                    keepOpenSockets.forEach { runCatching { it.close() } }
                }
                server.close()
                dir.deleteRecursively()
            }
        }
}
