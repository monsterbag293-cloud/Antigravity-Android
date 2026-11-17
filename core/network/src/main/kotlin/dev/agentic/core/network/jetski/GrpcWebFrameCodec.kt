package dev.agentic.core.network.jetski

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class GrpcStatus(val code: Int) {
    OK(0),
    CANCELED(1),
    UNKNOWN(2),
    INVALID_ARGUMENT(3),
    DEADLINE_EXCEEDED(4),
    NOT_FOUND(5),
    ALREADY_EXISTS(6),
    PERMISSION_DENIED(7),
    RESOURCE_EXHAUSTED(8),
    FAILED_PRECONDITION(9),
    ABORTED(10),
    OUT_OF_RANGE(11),
    UNIMPLEMENTED(12),
    INTERNAL(13),
    UNAVAILABLE(14),
    DATA_LOSS(15),
    UNAUTHENTICATED(16)
}

class GrpcWebProtocolException(
    val status: GrpcStatus,
    override val message: String
) : Exception(message)

data class DecodedGrpcWebFrame(
    val flags: Int,
    val data: ByteArray
) {
    val isTrailer: Boolean get() = (flags and 0x80) == 0x80
    val isCompressed: Boolean get() = (flags and 0x01) == 0x01
    val utf8Text: String get() = data.toString(Charsets.UTF_8)
}

/**
 * Connect-RPC / gRPC-Web wire framing codec (`application/grpc-web+json`).
 *
 * Verified against `xYc`, `efa`, `ffa`, and `Cfa` in `main.js` (lines 1514-1523, 10430000).
 */
object GrpcWebFrameCodec {
    const val CONTENT_TYPE_GRPC_WEB_JSON = "application/grpc-web+json"
    const val HEADER_CSRF_TOKEN = "x-codeium-csrf-token"
    const val HEADER_GRPC_WEB = "x-grpc-web"
    const val HEADER_GRPC_TIMEOUT = "grpc-timeout"
    const val HEADER_GRPC_STATUS = "grpc-status"
    const val HEADER_GRPC_MESSAGE = "grpc-message"
    private const val MAX_FRAME_SIZE = 32 * 1024 * 1024

    fun encodeDataFrame(jsonPayload: String): ByteArray {
        val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)
        return encodeRawFrame(flags = 0x00, payloadBytes = payloadBytes)
    }

    fun encodeTrailerFrame(
        status: GrpcStatus = GrpcStatus.OK,
        message: String? = null,
        extraTrailers: Map<String, String> = emptyMap()
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("grpc-status: ").append(status.code).append("\r\n")
        if (!message.isNullOrBlank()) {
            val encodedMsg = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
            sb.append("grpc-message: ").append(encodedMsg).append("\r\n")
        }
        for ((k, v) in extraTrailers) {
            sb.append(k.lowercase()).append(": ").append(v).append("\r\n")
        }
        return encodeRawFrame(
            flags = 0x80,
            payloadBytes = sb.toString().toByteArray(Charsets.UTF_8)
        )
    }

    fun encodeUnaryResponse(
        jsonPayload: String,
        status: GrpcStatus = GrpcStatus.OK,
        message: String? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeDataFrame(jsonPayload))
        out.write(encodeTrailerFrame(status, message))
        return out.toByteArray()
    }

    fun encodeErrorResponse(
        status: GrpcStatus,
        message: String? = null
    ): ByteArray {
        return encodeTrailerFrame(status, message)
    }

    fun encodeServerStreamResponse(
        jsonPayloads: List<String>,
        status: GrpcStatus = GrpcStatus.OK,
        message: String? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        for (payload in jsonPayloads) {
            out.write(encodeDataFrame(payload))
        }
        out.write(encodeTrailerFrame(status, message))
        return out.toByteArray()
    }

    fun encodeErrorTrailerOnly(
        status: GrpcStatus,
        message: String
    ): ByteArray {
        return encodeTrailerFrame(status = status, message = message)
    }

    fun decodeFrames(bodyBytes: ByteArray): List<DecodedGrpcWebFrame> {
        if (bodyBytes.isEmpty()) return emptyList()
        val frames = mutableListOf<DecodedGrpcWebFrame>()
        var offset = 0
        while (offset < bodyBytes.size) {
            if (bodyBytes.size - offset < 5) {
                throw GrpcWebProtocolException(
                    GrpcStatus.INVALID_ARGUMENT,
                    "protocol error: incomplete envelope header"
                )
            }
            val flags = bodyBytes[offset].toInt() and 0xFF
            if ((flags and 0x01) == 0x01) {
                throw GrpcWebProtocolException(
                    GrpcStatus.INTERNAL,
                    "protocol error: received unsupported compressed frame"
                )
            }
            val len = ByteBuffer.wrap(bodyBytes, offset + 1, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            if (len < 0 || len > MAX_FRAME_SIZE) {
                throw GrpcWebProtocolException(
                    GrpcStatus.RESOURCE_EXHAUSTED,
                    "message size $len exceeds maximum allowed frame size"
                )
            }
            offset += 5
            if (bodyBytes.size - offset < len) {
                throw GrpcWebProtocolException(
                    GrpcStatus.INVALID_ARGUMENT,
                    "protocol error: incomplete envelope payload (expected $len bytes, available ${bodyBytes.size - offset})"
                )
            }
            val data = bodyBytes.copyOfRange(offset, offset + len)
            frames.add(DecodedGrpcWebFrame(flags = flags, data = data))
            offset += len
        }
        return frames
    }

    fun extractFirstRequestJson(bodyBytes: ByteArray): String {
        val frames = decodeFrames(bodyBytes)
        val firstData = frames.firstOrNull { !it.isTrailer } ?: return "{}"
        val text = firstData.utf8Text.trim()
        return text.ifEmpty { "{}" }
    }

    /**
     * Parses gRPC `Grpc-Timeout` header values (e.g. `10S`, `500m`, `2M`, `1H`).
     */
    fun parseGrpcTimeoutMs(headerValue: String?): Long? {
        if (headerValue.isNullOrBlank()) return null
        val trimmed = headerValue.trim()
        if (trimmed.length < 2) return null
        val unit = trimmed.last()
        val amount = trimmed.dropLast(1).toLongOrNull() ?: return null
        return when (unit) {
            'H' -> amount * 3_600_000L
            'M' -> amount * 60_000L
            'S' -> amount * 1_000L
            'm' -> amount
            'u' -> (amount / 1_000L).coerceAtLeast(1L)
            'n' -> (amount / 1_000_000L).coerceAtLeast(1L)
            else -> null
        }
    }

    private fun encodeRawFrame(flags: Int, payloadBytes: ByteArray): ByteArray {
        val frame = ByteArray(5 + payloadBytes.size)
        frame[0] = (flags and 0xFF).toByte()
        ByteBuffer.wrap(frame, 1, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payloadBytes.size)
        System.arraycopy(payloadBytes, 0, frame, 5, payloadBytes.size)
        return frame
    }
}
