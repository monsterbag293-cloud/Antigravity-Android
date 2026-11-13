package dev.agentic.core.network.hub

import kotlinx.serialization.Serializable

/**
 * Configuration required to establish a secure, authenticated connection between the
 * Android frontend and an authorized desktop Antigravity Language Server Hub.
 *
 * Discovery source: `~/.gemini/antigravity/daemon/hub_<port>.json` (Windows/macOS/Linux).
 *
 * Security requirements:
 * 1. The `csrfToken` is an authorization secret (UUID). It MUST NEVER be written to logs,
 *    command-line outputs, or plaintext application diagnostics.
 * 2. [toString] explicitly redacts the `csrfToken`.
 */
@Serializable
data class DesktopHubConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_HTTPS_PORT,
    val csrfToken: String,
    val useTls: Boolean = true,
    val pinnedCertSha256: String? = null
) {
    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_HTTPS_PORT = 57733
        const val DEFAULT_HTTP_PORT = 57734
        const val REDACTED = "[REDACTED]"
    }

    init {
        require(host.isNotBlank()) { "Desktop Hub host must not be blank" }
        require(port in 1..65535) { "Desktop Hub port must be between 1 and 65535, was: $port" }
        require(csrfToken.isNotBlank()) { "Desktop Hub CSRF token must not be blank" }
    }

    val baseUrl: String
        get() = "${if (useTls) "https" else "http"}://$host:$port"

    override fun toString(): String {
        return "DesktopHubConfig(host='$host', port=$port, csrfToken=$REDACTED, useTls=$useTls, pinnedCertSha256=${pinnedCertSha256 ?: "none"})"
    }

    /**
     * Returns a copy suitable for safe diagnostic display.
     */
    fun toSafeDiagnosticsString(): String = toString()
}

/**
 * Connection states for [RemoteHubConnectionManager].
 */
sealed class HubConnectionState {
    object Disconnected : HubConnectionState()
    data class Connecting(val config: DesktopHubConfig) : HubConnectionState()
    data class Connected(val config: DesktopHubConfig, val status: HubServerStatus) : HubConnectionState()
    data class Error(val config: DesktopHubConfig?, val message: String, val cause: Throwable? = null) : HubConnectionState()
}

/**
 * Diagnostics and capability information verified from a genuine desktop Language Server.
 */
data class HubServerStatus(
    val connectedAtIso: String,
    val serverVersion: String? = null,
    val userName: String? = null,
    val userEmail: String? = null,
    val planName: String? = null,
    val teamsTier: String? = null,
    val availablePromptCredits: Long? = null,
    val cascadeWebSearchEnabled: Boolean = false,
    val availableModelsCount: Int = 0,
    val clientModelLabels: List<String> = emptyList()
) {
    override fun toString(): String {
        // Redact email in log strings
        val maskedEmail = userEmail?.let {
            val at = it.indexOf('@')
            if (at > 2) it.substring(0, 2) + "***" + it.substring(at) else "***"
        } ?: "none"
        return "HubServerStatus(serverVersion=$serverVersion, plan=$planName, tier=$teamsTier, models=$availableModelsCount, email=$maskedEmail)"
    }
}
