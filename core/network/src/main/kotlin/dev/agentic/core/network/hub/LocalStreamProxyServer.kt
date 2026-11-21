package dev.agentic.core.network.hub

import dev.agentic.core.network.jetski.GrpcWebFrameCodec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback HTTP/1.1 chunked streaming proxy for long-lived Connect/gRPC-Web server streams
 * (e.g. `StreamAgentStateUpdates`).
 *
 * Android WebView's `shouldInterceptRequest` (`WebResourceResponse`) can buffer small trailing
 * `InputStream` chunks until more bytes arrive or EOF. Routing `StreamAgentStateUpdates` through
 * a real `127.0.0.1` HTTP/1.1 `Transfer-Encoding: chunked` socket causes Chromium's native network
 * stack to flush every chunk to `fetch()`'s `ReadableStream` immediately.
 */
internal class LocalStreamProxyServer(
    private val connectionManager: RemoteHubConnectionManager
) {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = serverSocket.localPort
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "agy-stream-proxy-worker").apply { isDaemon = true }
    }

    init {
        Thread({
            while (!closed.get() && !serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()
                    executor.execute { handleClient(client) }
                } catch (_: IOException) {
                    if (closed.get()) break
                }
            }
        }, "agy-stream-proxy-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { serverSocket.close() }
            runCatching { executor.shutdownNow() }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 10_000
                val input = BufferedInputStream(sock.getInputStream())
                val out = BufferedOutputStream(sock.getOutputStream())

                val requestLine = readLineAscii(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]

                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLineAscii(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim()
                        val value = line.substring(idx + 1).trim()
                        headers[name] = value
                    }
                }

                if (method == "OPTIONS") {
                    val resp = buildString {
                        append("HTTP/1.1 204 No Content\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
                        append("Access-Control-Allow-Headers: *\r\n")
                        append("Access-Control-Max-Age: 86400\r\n")
                        append("Content-Length: 0\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(resp.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }

                val contentLength = headers.entries
                    .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
                    ?.value?.toIntOrNull() ?: 0
                val bodyBytes = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)

                val config = connectionManager.getActiveConfig()
                val client = connectionManager.getActiveHttpClient()
                if (config == null || client == null) {
                    val resp = "HTTP/1.1 503 Service Unavailable\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(resp.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }

                // Long-lived stream: disable socket timeout once request is read.
                sock.soTimeout = 0

                val contentType = headers.entries
                    .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                    ?.value ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON

                val reqBuilder = Request.Builder()
                    .url("${config.baseUrl}$path")
                    .post(bodyBytes.toRequestBody(contentType.toMediaTypeOrNull()))

                for ((name, value) in headers) {
                    if (!name.equals("host", ignoreCase = true) &&
                        !name.equals("content-length", ignoreCase = true) &&
                        !name.equals("connection", ignoreCase = true) &&
                        !name.equals("accept-encoding", ignoreCase = true) &&
                        !name.equals("origin", ignoreCase = true) &&
                        !name.equals("referer", ignoreCase = true)
                    ) {
                        reqBuilder.header(name, value)
                    }
                }
                reqBuilder.header(RemoteHubConnectionManager.HEADER_CSRF_TOKEN, config.csrfToken)
                reqBuilder.header(RemoteHubConnectionManager.HEADER_CONNECT_PROTOCOL, "1")

                val streamingClient = client.newBuilder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
                val call = streamingClient.newCall(reqBuilder.build())
                try {
                    call.execute().use { response ->
                        val respCt = response.header("content-type") ?: GrpcWebFrameCodec.CONTENT_TYPE_GRPC_WEB_JSON
                        val headerText = buildString {
                            append("HTTP/1.1 ${response.code} OK\r\n")
                            append("Content-Type: $respCt\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("Access-Control-Expose-Headers: *\r\n")
                            append("Cache-Control: no-cache\r\n")
                            append("Transfer-Encoding: chunked\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(headerText.toByteArray(Charsets.US_ASCII))
                        out.flush()

                        val rawStream = response.body?.byteStream() ?: return
                        val buf = ByteArray(16384)
                        while (true) {
                            val n = rawStream.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write("${n.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
                            out.write(buf, 0, n)
                            out.write("\r\n".toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                } finally {
                    runCatching { call.cancel() }
                }
            } catch (_: IOException) {
                // Client disconnected or upstream closed.
            }
        }
    }

    private fun readLineAscii(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.deleteCharAt(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("Unexpected EOF reading request body")
            off += n
        }
        return buf
    }
}
