package dev.agentic.core.network.hub

import dev.agentic.core.network.jetski.GrpcStatus
import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import dev.agentic.core.network.jetski.GrpcWebHttpRequest
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
}
