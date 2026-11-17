# Phase P3: Original Antigravity Integration — Overview & Architecture

## 1. Executive Summary

Phase P3 achieves the central objective of the Antigravity Android Port project: **connecting the original Antigravity Jetski Web React frontend running inside Android WebView to the genuine Antigravity AI agent backend (`language_server.exe`)**.

Rather than attempting to rebuild or simulate Google's complex proprietary AI infrastructure, Phase P3 implements the **Paired Desktop Architecture** discovered during the Phase P2 reverse-engineering investigation. The architecture connects the Android client to an active desktop Antigravity instance operating in Hub mode (`--subclient_type=hub`), granting the Android user immediate access to:
1. **The Genuine Original AI Models**: Unreleased Cloud Code models including `Gemini 4 Argon (Medium)` (`MODEL_PLACEHOLDER_M418`), `Gemini 4 Argon (High)` (`MODEL_PLACEHOLDER_M417`), `Claude Opus 5.5`, `Claude Sonnet 5.5`, and `Gemini 3.8 Flash (High)`.
2. **Original User Authentication**: Genuine Google user identity (`"Bean Bag"`, `monsterbag293@gmail.com`), Pro subscription tier (`TEAMS_TIER_PRO`), and real-time prompt credits (500 prompt credits).
3. **Genuine Cascade Multi-Step Agent Execution**: Real-time trajectory streaming, cortex step execution, tool invocation across desktop workspaces, and task cancellation.
4. **Original SQLite Trajectory Databases**: Direct integration with conversation databases (`conversations/<id>.db` and `conversation_summaries.db`).

Simultaneously, Phase P3 preserves the **Local Gemini Fallback** engine developed in P1.2/P1.3 as an isolated standalone mode for offline or un-paired mobile scenarios, while enforcing strict architectural boundaries to guarantee zero silent substitution.

---

## 2. Dual-Mode Architecture

The Android application operates in two strictly isolated backend modes, defined by `BackendMode`:

```
                       +---------------------------------------+
                       |       Android WebView Presentation    |
                       |      (Jetski Web React 19 Frontend)    |
                       +-------------------+-------------------+
                                           |
                                           v
                       +---------------------------------------+
                       |    JetskiWebViewRequestInterceptor    |
                       |       (gRPC-Web / Connect-RPC)        |
                       +-------------------+-------------------+
                                           |
                                           v
                       +---------------------------------------+
                       |     LanguageServerGrpcWebHandler      |
                       +-------------------+-------------------+
                                           |
                                [Mode Dispatcher]
                               /                 \
        [ORIGINAL_DESKTOP_HUB]/                   \[LOCAL_GEMINI_FALLBACK]
                             v                     v
         +-----------------------------+ +-----------------------------+
         |    RemoteHubProxyHandler    | |    AgentRuntime (Kotlin)    |
         | (HTTP/HTTPS Relay + TLS +   | | (Local Agent Execution Loop |
         |  CSRF Token Injection)      | |  + BYOK Gemini API Key)     |
         +--------------+--------------+ +--------------+--------------+
                        |                               |
                        v                               v
         +-----------------------------+ +-----------------------------+
         | Genuine Desktop Daemon      | | Google Generative AI API    |
         | language_server.exe         | | generativelanguage.         |
         | (Port 57734 / 57733)        | | googleapis.com (BYOK)       |
         | - Real Cloud Code Models    | +-----------------------------+
         | - Genuine Google Pro Auth   |
         | - Trajectory SQLite DBs     |
         +-----------------------------+
```

### Mode Comparison Matrix

| Dimension | `ORIGINAL_DESKTOP_HUB` (Primary Mode) | `LOCAL_GEMINI_FALLBACK` (Isolated Mobile Fallback) |
|---|---|---|
| **Backend Target** | Desktop daemon (`language_server.exe`, CL: 1015941558) | In-process Android Kotlin engine (`AgentRuntime`) |
| **Model Access** | Genuine Google Cloud Code (`daily-cloudcode-pa.googleapis.com`) | Public Google AI Studio (`generativelanguage.googleapis.com`) |
| **Available Models** | Gemini 4 Argon (High/Med), Claude Opus 5.5, Claude Sonnet 5.5, Gemini 3.8 Flash | User's BYOK model (e.g. `gemini-2.5-flash`, `gemini-2.5-pro`) |
| **Authentication** | Genuine Google Account (`userStatus.name`, `userStatus.email`, `teamsTier`) | Independent BYOK Gemini API Key (`AIzaSy...`) |
| **Trajectory Storage** | Desktop SQLite (`~/.gemini/antigravity/conversations/<id>.db`) | Local Android SQLite / memory |
| **Safety Monitors** | Shipped desktop safety subagents (`sir_flash_lite`, `agy_tool_safety`) | Local workspace boundary validator (`StageP13CommandSecurityTest`) |
| **Failure Behavior** | Returns explicit `GrpcStatus.UNAVAILABLE` with diagnostic code. **Never silently falls back.** | Returns local execution error. |

---

## 3. Strict Compliance with Architectural Requirements

### Requirement 1: Authentication Separation
- **No Fabricated Google Logins**: The Android host bridge explicitly separates local Android readiness (`userStatus = Ready (Android Host Session)`) from the genuine Google user status retrieved from the desktop hub.
- **BYOK Gemini Isolation**: Gemini API-Key validation is strictly isolated to `LOCAL_GEMINI_FALLBACK` mode and labeled clearly as an independent, alternative authentication method that confers no Google Cloud Code or Antigravity Pro entitlements.
- **Clear Settings Interface**: The Android Settings UI provides separate cards for "Backend Execution Architecture" (Hub connection status, host/port, TLS, CSRF) and "Local Gemini API Key (Standalone Fallback Only)".

### Requirement 2: Strict Isolation & No Silent Fallback
- When `BackendMode.ORIGINAL_DESKTOP_HUB` is active and the Desktop Hub is disconnected or unreachable, the proxy **never** redirects traffic to the local Gemini agent.
- Instead, it returns an explicit gRPC error frame:
  ```
  grpc-status: 14 (UNAVAILABLE)
  grpc-message: Desktop Hub disconnected or unavailable: Host 127.0.0.1:57734 unreachable. Verify language_server.exe is running.
  ```
- This ensures users are immediately aware of connection issues and guarantees that prompts intended for private desktop workspaces are never inadvertently routed to public cloud endpoints.

### Requirement 3: Binary Frame Integrity
- Connect-RPC and gRPC-Web binary frames require exact byte preservation:
  - Data frames: `0x00` header followed by 4-byte big-endian payload length.
  - Trailer frames: `0x80` header followed by 4-byte big-endian trailer length.
- Android WebView's `WebResourceResponse` automatically transcodes responses into UTF-8 if `encoding` is set to `"utf-8"`, which corrupts multi-byte binary sequences.
- `JetskiWebViewRequestInterceptor` explicitly passes `encoding = null` for all binary transports (`application/grpc-web+proto`, `application/grpc-web+json`, `application/grpc-web`), ensuring zero byte alteration across the WebView bridge.

### Requirement 4: Zero Credential Leakage
- All sensitive tokens, including `X-Codeium-Csrf-Token` and Gemini API keys, are redacted (`[REDACTED]`) in all logs, diagnostics, `toString()` implementations, and test assertions.
- The CSRF token is injected securely in outgoing HTTP/HTTPS headers by `RemoteHubProxyHandler` and is stripped from any diagnostic dumps.

---

## 4. Phase P3 Subphase Deliverables

The implementation and verification of Phase P3 is documented across five detailed reports:
1. **[P3.1: Desktop Hub Connection Report](p3.1-hub-connection-report.md)**: Proof of concept connecting to `language_server.exe` on ports 57734/57733, TLS self-signed certificate trust, CSRF authentication, and daemon discovery.
2. **[P3.2: Secure RPC Relay Report](p3.2-rpc-relay-report.md)**: Bidirectional gRPC-Web/Connect-RPC proxy, HTTP/2 streaming, chunked frame relay, header sanitation, and connection lifecycle management.
3. **[P3.3: Original Frontend Integration Report](p3.3-frontend-integration-report.md)**: WebView request interception, CORS negotiation, binary frame preservation (`encoding = null`), and Settings UI configuration.
4. **[P3.4: Genuine Cascade Validation Report](p3.4-cascade-validation-report.md)**: Execution of `SendUserCascadeMessage`, trajectory streaming, cortex step dispatch, cancellation (`CancelCascadeInvocation`), and SQLite persistence.
5. **[P3.5: Original Authentication & Model Selection Report](p3.5-auth-model-validation-report.md)**: Verification of genuine Google user profile, Pro plan subscription, prompt credit tracking, Cloud Code model catalog, and separation from local BYOK.
6. **[P3 Implementation Status & Verification Matrix](p3-implementation-status.md)**: Complete test results, code artifacts, and compliance checklist.
