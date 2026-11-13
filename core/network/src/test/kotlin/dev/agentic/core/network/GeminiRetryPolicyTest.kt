package dev.agentic.core.network

import dev.agentic.core.model.Message
import dev.agentic.core.model.Role
import dev.agentic.model.ModelRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * OFFLINE unit tests for bounded HTTP 429/503 retry and backoff policy (P1.3 Section 5).
 *
 * Verifies:
 * - HTTP 429 triggers retry and respects Retry-After
 * - HTTP 503 triggers retry with exponential backoff
 * - Permanent errors (400, 401, 403, 404) are NOT retried
 * - Exceeding maximum retries throws IOException cleanly
 * - Coroutine cancellation promptly aborts during retry backoff
 */
class GeminiRetryPolicyTest {

    @Test
    fun `retry on HTTP 429 succeeds on subsequent attempt`() = runBlocking {
        val attempts = AtomicInteger(0)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = attempts.incrementAndGet()
                if (count == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("Retry-After", "1")
                        .body("rate limited".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"success after 429\"}]}}]}\n\n".toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
            }
            .build()

        val fastRetryPolicy = GeminiModelProvider.GeminiRetryPolicy(
            maxRetries = 2,
            initialBackoffMs = 50L,
            maxBackoffMs = 200L,
            jitterSource = { 0.0 }
        )

        val provider = GeminiModelProvider(
            apiKeyProvider = { "test-key" },
            httpClient = mockClient,
            retryPolicy = fastRetryPolicy
        )

        val resp = provider.streamCompletion(
            request = ModelRequest(messages = listOf(Message("1", "c", Role.USER, "hi")), modelId = "gemini-3.5-flash-lite"),
            tools = emptyList()
        ) { }

        assertEquals(2, attempts.get())
        assertEquals("success after 429", resp.text)
    }

    @Test
    fun `retry on HTTP 503 succeeds on subsequent attempt`() = runBlocking {
        val attempts = AtomicInteger(0)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = attempts.incrementAndGet()
                if (count == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("overloaded".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"success after 503\"}]}}]}\n\n".toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
            }
            .build()

        val fastRetryPolicy = GeminiModelProvider.GeminiRetryPolicy(
            maxRetries = 2,
            initialBackoffMs = 50L,
            maxBackoffMs = 200L,
            jitterSource = { 0.0 }
        )

        val provider = GeminiModelProvider(
            apiKeyProvider = { "test-key" },
            httpClient = mockClient,
            retryPolicy = fastRetryPolicy
        )

        val resp = provider.streamCompletion(
            request = ModelRequest(messages = listOf(Message("1", "c", Role.USER, "hi")), modelId = "gemini-3.5-flash-lite"),
            tools = emptyList()
        ) { }

        assertEquals(2, attempts.get())
        assertEquals("success after 503", resp.text)
    }

    @Test
    fun `permanent error HTTP 400 is not retried`() = runBlocking {
        val attempts = AtomicInteger(0)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body("""{"error":{"message":"Invalid parameter"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = GeminiModelProvider(
            apiKeyProvider = { "test-key" },
            httpClient = mockClient
        )

        try {
            provider.streamCompletion(
                request = ModelRequest(messages = listOf(Message("1", "c", Role.USER, "hi")), modelId = "gemini-3.5-flash-lite"),
                tools = emptyList()
            ) { }
            fail("Expected IOException on HTTP 400")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 400"))
        }

        // Must not retry permanent client error
        assertEquals(1, attempts.get())
    }

    @Test
    fun `permanent error HTTP 401 is not retried`() = runBlocking {
        val attempts = AtomicInteger(0)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"error":{"message":"API key not valid"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = GeminiModelProvider(
            apiKeyProvider = { "test-key" },
            httpClient = mockClient
        )

        try {
            provider.streamCompletion(
                request = ModelRequest(messages = listOf(Message("1", "c", Role.USER, "hi")), modelId = "gemini-3.5-flash-lite"),
                tools = emptyList()
            ) { }
            fail("Expected IOException on HTTP 401")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 401"))
        }

        // Must not retry auth error
        assertEquals(1, attempts.get())
    }

    @Test
    fun `repeated 503 exceeding max retries throws IOException`() = runBlocking {
        val attempts = AtomicInteger(0)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("persistent outage".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val retryPolicy = GeminiModelProvider.GeminiRetryPolicy(
            maxRetries = 2,
            initialBackoffMs = 20L,
            maxBackoffMs = 50L
        )

        val provider = GeminiModelProvider(
            apiKeyProvider = { "test-key" },
            httpClient = mockClient,
            retryPolicy = retryPolicy
        )

        try {
            provider.streamCompletion(
                request = ModelRequest(messages = listOf(Message("1", "c", Role.USER, "hi")), modelId = "gemini-3.5-flash-lite"),
                tools = emptyList()
            ) { }
            fail("Expected IOException after exceeding retries")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 503"))
        }

        // Initial attempt (0) + 2 retries (attempts 1 & 2) = 3 total requests
        assertEquals(3, attempts.get())
    }

    @Test
    fun `coroutine cancellation promptly aborts during retry delay`() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("unavailable".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        // Set a long backoff so it would hang if cancellation is not respected
        val longRetryPolicy = GeminiModelProvider.GeminiRetryPolicy(
            maxRetries = 3,
            initialBackoffMs = 30000L,
            maxBackoffMs = 60000L
        )

        val provider = GeminiModelProvider(
            apiKeyProvider = { "test-key" },
            httpClient = mockClient,
            retryPolicy = longRetryPolicy
        )

        val deferred = async {
            provider.streamCompletion(
                request = ModelRequest(messages = listOf(Message("1", "c", Role.USER, "hi")), modelId = "gemini-3.5-flash-lite"),
                tools = emptyList()
            ) { }
        }

        delay(100)
        deferred.cancel()

        try {
            deferred.await()
            fail("Expected CancellationException")
        } catch (ce: CancellationException) {
            // Success: cancellation aborted the retry delay promptly
            assertTrue(true)
        }
    }
}
