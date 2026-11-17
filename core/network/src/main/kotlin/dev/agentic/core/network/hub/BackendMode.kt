package dev.agentic.core.network.hub

/**
 * Defines the active execution and RPC routing backend for the Antigravity Android port (Phase P3 / P3.7).
 *
 * - [STANDALONE_IN_APP_BACKEND]: (Mode B) The primary standalone architecture. Operates the genuine Google
 *   Antigravity Language Server (`language_server-linux-arm64`) directly on the Android device inside the
 *   application sandbox via PRoot Linux virtualization. No desktop host required.
 *
 * - [ORIGINAL_DESKTOP_HUB]: (Mode A) Connects to a running desktop Antigravity Language Server Hub over Wi-Fi/LAN.
 *
 * - [LOCAL_GEMINI_FALLBACK]: An isolated fallback engine (P1.2/P1.3). Serves RPC requests in-process on Android
 *   using local tools and a BYOK Gemini API key stored in Android Keystore.
 */
enum class BackendMode(val displayName: String, val description: String) {
    STANDALONE_IN_APP_BACKEND(
        displayName = "Standalone In-App Backend (Mode B)",
        description = "Runs the genuine Google Antigravity language server directly on-device inside this Android application via native virtualization."
    ),
    ORIGINAL_DESKTOP_HUB(
        displayName = "Original Antigravity Desktop Hub (Mode A)",
        description = "Relays to a running desktop Antigravity Language Server Hub over Wi-Fi/LAN with authentic Google authentication."
    ),
    LOCAL_GEMINI_FALLBACK(
        displayName = "Local Gemini Agent Engine (Fallback)",
        description = "In-process Android agent engine using an independent BYOK Gemini API key and sandboxed local tools."
    )
}
